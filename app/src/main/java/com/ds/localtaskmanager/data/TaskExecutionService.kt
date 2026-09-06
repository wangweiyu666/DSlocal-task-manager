package com.ds.localtaskmanager.data

import androidx.room.withTransaction
import com.ds.localtaskmanager.data.dao.AuditDao
import com.ds.localtaskmanager.data.dao.ExecutionDao
import com.ds.localtaskmanager.data.dao.InstanceDao
import com.ds.localtaskmanager.data.result.ResultRecalculationService
import com.ds.localtaskmanager.data.result.ResultRevisionReason
import com.ds.localtaskmanager.domain.RecordIdGenerator
import com.ds.localtaskmanager.domain.TaskStateMachine
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.execution.CounterAction
import com.ds.localtaskmanager.domain.execution.CompletionReadiness
import com.ds.localtaskmanager.domain.execution.ExecutionState
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import com.ds.localtaskmanager.domain.execution.TaskOperationCode
import com.ds.localtaskmanager.domain.execution.StepState
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class TaskOperationException(
    val code: TaskOperationCode,
    message: String,
) : IllegalStateException(message)

interface TaskExecutionService {
    suspend fun getExecutionState(key: TaskInstanceKey): ExecutionState
    suspend fun getCompletionReadiness(key: TaskInstanceKey): CompletionReadiness
    suspend fun setStep(key: TaskInstanceKey, position: Int, completed: Boolean)
    suspend fun skipStep(key: TaskInstanceKey, position: Int) = setStep(key, position, false)
    suspend fun confirmStep(key: TaskInstanceKey, stepId: String) { throw UnsupportedOperationException("step confirmation unsupported") }
    suspend fun undoStep(key: TaskInstanceKey, stepId: String) { throw UnsupportedOperationException("step undo unsupported") }
    suspend fun setStepCounter(key: TaskInstanceKey, stepId: String, value: Int): StepState { throw UnsupportedOperationException("step counter unsupported") }
    suspend fun saveStepInformation(key: TaskInstanceKey, stepId: String, content: String): StepState { throw UnsupportedOperationException("step information unsupported") }
    suspend fun saveStepMood(key: TaskInstanceKey, stepId: String, rating: Int?, text: String): StepState { throw UnsupportedOperationException("step mood unsupported") }
    suspend fun setStepTimer(key: TaskInstanceKey, stepId: String, elapsedMillis: Long): StepState { throw UnsupportedOperationException("step timer unsupported") }
    suspend fun setCounter(key: TaskInstanceKey, value: Int): ExecutionState.Counter
    suspend fun addTimerElapsed(key: TaskInstanceKey, elapsedMillis: Long): ExecutionState.Timer
    suspend fun saveInformationDraft(key: TaskInstanceKey, content: String): ExecutionState.Information
    suspend fun saveMoodDraft(key: TaskInstanceKey, rating: Int?, text: String): ExecutionState.Mood
    suspend fun complete(key: TaskInstanceKey)
    suspend fun undoCompletion(key: TaskInstanceKey)
    suspend fun reconcile(key: TaskInstanceKey): TaskInstanceEntity

    suspend fun setStep(taskId: String, position: Int, completed: Boolean) =
        setStep(TaskInstanceKey(taskId), position, completed)

    suspend fun complete(taskId: String) = complete(TaskInstanceKey(taskId))
    suspend fun undoCompletion(taskId: String) = undoCompletion(TaskInstanceKey(taskId))
    suspend fun reconcile(taskId: String): TaskInstanceEntity = reconcile(TaskInstanceKey(taskId))
}

class RoomTaskExecutionService(
    private val database: AppDatabase,
    private val clock: Clock,
    private val idGenerator: RecordIdGenerator,
) : TaskExecutionService {
    private val instanceDao: InstanceDao get() = database.instanceDao()
    private val executionDao: ExecutionDao get() = database.executionDao()
    private val auditDao: AuditDao get() = database.auditDao()
    private val resultService by lazy { ResultRecalculationService(database, clock, idGenerator) }

    override suspend fun getExecutionState(key: TaskInstanceKey): ExecutionState =
        database.withTransaction { executionState(requireInstance(key)) }

    override suspend fun getCompletionReadiness(key: TaskInstanceKey): CompletionReadiness =
        database.withTransaction {
            val original = requireInstance(key)
            val before = resultService.capture(listOf(original.taskDate))
            val instance = reconcile(original)
            resultService.writeChanges(
                before,
                listOf(instance.taskDate),
                ResultRevisionReason.DEADLINE_RECONCILED,
                null,
                listOf(key.taskId),
            )
            val stepsComplete = instanceDao.countIncompleteRequiredSteps(key.taskId, key.occurrenceKey) == 0
            val targetReached = isExecutionTargetReached(instance)
            CompletionReadiness(
                requiredStepsComplete = stepsComplete,
                executionTargetReached = targetReached,
                canComplete = instance.status == TaskStatus.PENDING.name && stepsComplete && targetReached,
            )
        }

    override suspend fun setStep(key: TaskInstanceKey, position: Int, completed: Boolean) =
        database.withTransaction {
            val instance = requirePending(key)
            if (instance.executionKind == "STEPS") {
                val step = instanceDao.getInstanceSteps(key.taskId, key.occurrenceKey).getOrNull(position)
                    ?: fail(TaskOperationCode.STEP_NOT_FOUND, "步骤不存在")
                val id = step.stepId ?: fail(TaskOperationCode.STEP_NOT_FOUND, "步骤缺少稳定 ID")
                if (completed) confirmStep(key, id) else undoStep(key, id)
                return@withTransaction
            }
            val steps = instanceDao.getInstanceSteps(key.taskId, key.occurrenceKey)
            if (instance.executionKind == "STEPS" && completed && steps.take(position).any { !it.completed }) {
                fail(TaskOperationCode.STEP_NOT_FOUND, "必须按顺序完成步骤")
            }
            val changed = instanceDao.updateStep(
                key.taskId,
                key.occurrenceKey,
                position,
                completed,
                clock.millis(),
            )
            if (changed != 1) fail(TaskOperationCode.STEP_NOT_FOUND, "步骤不存在")
            instanceDao.updateStepStatus(key.taskId, key.occurrenceKey, position, completed, if (completed) "CONFIRMED" else "PENDING", clock.millis())
            log(instance, if (completed) "STEP_COMPLETED" else "STEP_UNDONE", position.toString())
        }

    override suspend fun skipStep(key: TaskInstanceKey, position: Int) = database.withTransaction {
        val instance = requirePending(key)
        if (instance.executionKind != "STEPS") fail(TaskOperationCode.EXECUTION_KIND_MISMATCH, "任务不是分步骤任务")
        val steps = instanceDao.getInstanceSteps(key.taskId, key.occurrenceKey)
        val step = steps.getOrNull(position) ?: fail(TaskOperationCode.STEP_NOT_FOUND, "步骤不存在")
        if (step.stepStatus != "PENDING") fail(TaskOperationCode.STEP_NOT_FOUND, "步骤已经处理")
        if (step.required) fail(TaskOperationCode.REQUIRED_STEP_INCOMPLETE, "必需步骤不能跳过")
        if (steps.take(position).any { it.stepStatus == "PENDING" }) fail(TaskOperationCode.STEP_NOT_FOUND, "必须按顺序处理步骤")
        if (instanceDao.updateStepStatus(key.taskId, key.occurrenceKey, position, false, "SKIPPED", clock.millis()) != 1) {
            fail(TaskOperationCode.STEP_NOT_FOUND, "步骤不存在")
        }
        touchInstance(instance)
        log(instance, "STEP_SKIPPED", position.toString())
    }

    override suspend fun confirmStep(key: TaskInstanceKey, stepId: String) = database.withTransaction {
        val instance = requirePending(key)
        if (instance.executionKind != "STEPS") fail(TaskOperationCode.EXECUTION_KIND_MISMATCH, "任务不是分步骤任务")
        val steps = instanceDao.getInstanceSteps(key.taskId, key.occurrenceKey)
        val step = steps.firstOrNull { it.stepId == stepId } ?: fail(TaskOperationCode.STEP_NOT_FOUND, "步骤不存在")
        if (step.stepStatus != "PENDING") fail(TaskOperationCode.STEP_NOT_FOUND, "步骤已经处理")
        if (steps.take(step.position).any { it.stepStatus == "PENDING" }) fail(TaskOperationCode.STEP_NOT_FOUND, "必须按顺序确认步骤")
        if (!stepSatisfied(step)) fail(TaskOperationCode.EXECUTION_TARGET_NOT_REACHED, "步骤目标尚未达成")
        instanceDao.insertInstanceSteps(listOf(step.copy(completed = true, stepStatus = "CONFIRMED", updatedAtEpochMillis = clock.millis())))
        touchInstance(instance)
        log(instance, "STEP_CONFIRMED", stepId)
    }

    override suspend fun undoStep(key: TaskInstanceKey, stepId: String) = database.withTransaction {
        val instance = requirePending(key)
        if (instance.executionKind != "STEPS") fail(TaskOperationCode.EXECUTION_KIND_MISMATCH, "任务不是分步骤任务")
        val steps = instanceDao.getInstanceSteps(key.taskId, key.occurrenceKey)
        val step = steps.firstOrNull { it.stepId == stepId } ?: fail(TaskOperationCode.STEP_NOT_FOUND, "步骤不存在")
        val now = clock.millis()
        instanceDao.insertInstanceSteps(steps.filter { it.position >= step.position }.map { it.copy(completed = false, stepStatus = "PENDING", updatedAtEpochMillis = now) })
        touchInstance(instance)
        log(instance, "STEP_UNDONE", stepId)
    }

    override suspend fun setStepCounter(key: TaskInstanceKey, stepId: String, value: Int): StepState = database.withTransaction {
        val instance = requirePending(key); val step = requireEditableStep(key, stepId)
        if (step.executionKind != "COUNTER" || value < 0 || value > (step.executionTarget ?: Int.MAX_VALUE)) fail(TaskOperationCode.COUNTER_OUT_OF_RANGE, "步骤计数无效")
        val updated = step.copy(counterValue = value, updatedAtEpochMillis = clock.millis())
        instanceDao.insertInstanceSteps(listOf(updated)); touchInstance(instance); updated.toStepState()
    }

    override suspend fun saveStepInformation(key: TaskInstanceKey, stepId: String, content: String): StepState = database.withTransaction {
        val instance = requirePending(key); val step = requireEditableStep(key, stepId)
        if (step.executionKind != "INFORMATION" || content.codePointCount(0, content.length) > INFORMATION_MAX_CODE_POINTS) fail(TaskOperationCode.INFORMATION_TOO_LONG, "步骤正文不能超过 2000 个字符")
        val updated = step.copy(informationContent = content, updatedAtEpochMillis = clock.millis())
        instanceDao.insertInstanceSteps(listOf(updated)); touchInstance(instance); updated.toStepState()
    }

    override suspend fun saveStepMood(key: TaskInstanceKey, stepId: String, rating: Int?, text: String): StepState = database.withTransaction {
        val instance = requirePending(key); val step = requireEditableStep(key, stepId)
        if (step.executionKind != "MOOD" || (rating != null && rating !in 1..5) || text.codePointCount(0, text.length) > INFORMATION_MAX_CODE_POINTS) fail(TaskOperationCode.MOOD_OUT_OF_RANGE, "步骤心情无效")
        val updated = step.copy(moodRating = rating, moodText = text, updatedAtEpochMillis = clock.millis())
        instanceDao.insertInstanceSteps(listOf(updated)); touchInstance(instance); updated.toStepState()
    }

    override suspend fun setStepTimer(key: TaskInstanceKey, stepId: String, elapsedMillis: Long): StepState = database.withTransaction {
        val instance = requirePending(key); val step = requireEditableStep(key, stepId)
        if (step.executionKind != "TIMER" || elapsedMillis < 0L) fail(TaskOperationCode.TIMER_OUT_OF_RANGE, "步骤计时无效")
        val updated = step.copy(elapsedMillis = elapsedMillis.coerceAtMost((step.executionTarget ?: Int.MAX_VALUE) * 1_000L), updatedAtEpochMillis = clock.millis())
        instanceDao.insertInstanceSteps(listOf(updated)); touchInstance(instance); updated.toStepState()
    }

    override suspend fun setCounter(key: TaskInstanceKey, value: Int): ExecutionState.Counter =
        database.withTransaction {
            val instance = requirePending(key)
            if (instance.executionKind != "COUNTER") {
                fail(TaskOperationCode.EXECUTION_KIND_MISMATCH, "任务不是计数任务")
            }
            val target = instance.requireTarget()
            if (value !in 0..target) {
                fail(TaskOperationCode.COUNTER_OUT_OF_RANGE, "计数必须在 0..$target")
            }
            val now = clock.millis()
            val old = executionDao.getProgress(key.taskId, key.occurrenceKey)
            executionDao.upsertProgress(
                ExecutionProgressEntity(
                    taskId = key.taskId,
                    occurrenceKey = key.occurrenceKey,
                    executionKind = "COUNTER",
                    counterValue = value,
                    elapsedMillis = null,
                    createdAtEpochMillis = old?.createdAtEpochMillis ?: now,
                    updatedAtEpochMillis = now,
                ),
            )
            if (old?.counterValue != value) {
                log(instance, "COUNTER_CHANGED", "{\"old\":${old?.counterValue ?: 0},\"new\":$value,\"target\":$target}")
            }
            counterState(instance, value)
        }

    override suspend fun addTimerElapsed(
        key: TaskInstanceKey,
        elapsedMillis: Long,
    ): ExecutionState.Timer = database.withTransaction {
        val instance = requirePending(key)
        if (instance.executionKind != "TIMER") {
            fail(TaskOperationCode.EXECUTION_KIND_MISMATCH, "任务不是计时任务")
        }
        if (elapsedMillis <= 0) {
            fail(TaskOperationCode.TIMER_OUT_OF_RANGE, "计时增量必须为正数")
        }
        val targetMillis = instance.requireTarget() * 1_000L
        val now = clock.millis()
        val old = executionDao.getProgress(key.taskId, key.occurrenceKey)
        val oldElapsed = old?.elapsedMillis ?: 0L
        val updatedElapsed = (oldElapsed + elapsedMillis).coerceAtMost(targetMillis)
        executionDao.upsertProgress(
            ExecutionProgressEntity(
                taskId = key.taskId,
                occurrenceKey = key.occurrenceKey,
                executionKind = "TIMER",
                counterValue = null,
                elapsedMillis = updatedElapsed,
                createdAtEpochMillis = old?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
            ),
        )
        if (updatedElapsed != oldElapsed) {
            log(instance, "TIMER_ELAPSED_ADDED", "{\"delta\":${updatedElapsed - oldElapsed},\"total\":$updatedElapsed}")
        }
        ExecutionState.Timer(updatedElapsed, targetMillis)
    }

    override suspend fun saveInformationDraft(
        key: TaskInstanceKey,
        content: String,
    ): ExecutionState.Information = database.withTransaction {
        val instance = requirePending(key)
        if (instance.executionKind != "INFORMATION") {
            fail(TaskOperationCode.EXECUTION_KIND_MISMATCH, "任务不是信息告知任务")
        }
        val normalized = content.trim()
        if (normalized.isEmpty()) {
            fail(TaskOperationCode.INFORMATION_EMPTY, "告知正文不能为空")
        }
        val length = normalized.codePointCount(0, normalized.length)
        if (length > INFORMATION_MAX_CODE_POINTS) {
            fail(TaskOperationCode.INFORMATION_TOO_LONG, "告知正文不能超过 2000 个字符")
        }
        val now = clock.millis()
        val old = executionDao.getSubmission(key.taskId, key.occurrenceKey)
        executionDao.upsertSubmission(
            InformationSubmissionEntity(
                taskId = key.taskId,
                occurrenceKey = key.occurrenceKey,
                content = normalized,
                createdAtEpochMillis = old?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
                submittedAtEpochMillis = null,
            ),
        )
        log(instance, "INFORMATION_DRAFT_SAVED", "{\"codePoints\":$length}")
        ExecutionState.Information(normalized, null)
    }

    override suspend fun saveMoodDraft(key: TaskInstanceKey, rating: Int?, text: String): ExecutionState.Mood = database.withTransaction {
        val instance = requirePending(key)
        if (instance.executionKind != "MOOD") fail(TaskOperationCode.EXECUTION_KIND_MISMATCH, "任务不是心情任务")
        if (rating != null && rating !in 1..5) fail(TaskOperationCode.MOOD_OUT_OF_RANGE, "请选择五档心情之一")
        if (text.codePointCount(0, text.length) > 2000) fail(TaskOperationCode.MOOD_TEXT_TOO_LONG, "感受不能超过 2000 个字符")
        val old = executionDao.getMood(key.taskId, key.occurrenceKey)
        val now = clock.millis()
        executionDao.upsertMood(MoodSubmissionEntity(key.taskId, key.occurrenceKey, rating, text,
            old?.createdAtEpochMillis ?: now, now, null))
        ExecutionState.Mood(rating, text, null)
    }

    override suspend fun complete(key: TaskInstanceKey) = database.withTransaction {
        val original = requireInstance(key)
        val before = resultService.capture(listOf(original.taskDate))
        val instance = reconcile(original)
        if (instance.status != TaskStatus.PENDING.name) {
            fail(TaskOperationCode.INSTANCE_NOT_PENDING, "只有待完成任务可以完成")
        }
        if (instanceDao.countIncompleteRequiredSteps(key.taskId, key.occurrenceKey) > 0) {
            fail(TaskOperationCode.REQUIRED_STEP_INCOMPLETE, "仍有必需步骤未完成")
        }
        if (instance.executionKind == "STEPS") {
            val steps = instanceDao.getInstanceSteps(key.taskId, key.occurrenceKey)
            validateStepSnapshot(steps)
            val pendingRequired = instanceDao.getInstanceSteps(key.taskId, key.occurrenceKey).any {
                it.required && it.stepStatus == "PENDING"
            }
            if (pendingRequired) fail(TaskOperationCode.REQUIRED_STEP_INCOMPLETE, "仍有必需步骤未完成")
            // Optional steps are explicitly skipped as part of the final completion
            // transaction; their draft answers are never part of the result.
            val optionalPending = instanceDao.getInstanceSteps(key.taskId, key.occurrenceKey)
                .filter { !it.required && it.stepStatus == "PENDING" }
            if (optionalPending.isNotEmpty()) {
                val stamp = clock.millis()
                instanceDao.insertInstanceSteps(optionalPending.map { it.copy(completed = false, stepStatus = "SKIPPED", updatedAtEpochMillis = stamp) })
                touchInstance(instance)
            }
        }
        requireExecutionTarget(instance)
        val now = clock.millis()
        if (instance.executionKind == "INFORMATION") {
            val submission = executionDao.getSubmission(key.taskId, key.occurrenceKey)
                ?: fail(TaskOperationCode.EXECUTION_TARGET_NOT_REACHED, "告知正文尚未填写")
            executionDao.upsertSubmission(submission.copy(submittedAtEpochMillis = now, updatedAtEpochMillis = now))
        }
        if (instance.executionKind == "MOOD") {
            val mood = requireNotNull(executionDao.getMood(key.taskId, key.occurrenceKey))
            executionDao.upsertMood(mood.copy(submittedAtEpochMillis = now, updatedAtEpochMillis = now))
        }
        instanceDao.upsertInstances(
            listOf(
                instance.copy(
                    status = TaskStatus.COMPLETED.name,
                    completedAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ),
            ),
        )
        auditDao.insertLedger(
            PointsLedgerEntity(
                ledgerId = idGenerator.next(),
                taskId = key.taskId,
                occurrenceKey = key.occurrenceKey,
                groupId = currentGroup(key.taskId),
                delta = instance.points,
                reason = "COMPLETED",
                createdAtEpochMillis = now,
            ),
        )
        log(instance, "COMPLETED", null)
        resultService.writeChanges(
            before,
            listOf(instance.taskDate),
            ResultRevisionReason.TASK_COMPLETED,
            null,
            listOf(key.taskId),
        )
    }

    override suspend fun undoCompletion(key: TaskInstanceKey) = database.withTransaction {
        val instance = requireInstance(key)
        val before = resultService.capture(listOf(instance.taskDate))
        if (instance.status != TaskStatus.COMPLETED.name) {
            fail(TaskOperationCode.INSTANCE_NOT_COMPLETED, "任务尚未完成")
        }
        val completionEntry = auditDao.getLedger(key.taskId, key.occurrenceKey)
            .lastOrNull { it.reason == "COMPLETED" }
            ?: fail(TaskOperationCode.COMPLETION_LEDGER_MISSING, "缺少完成积分流水")
        val now = clock.millis()
        val nextStatus = TaskStateMachine.statusAt(
            LocalDate.parse(instance.taskDate),
            instance.deadline?.let(LocalDateTime::parse),
            nowDateTime(),
        )
        instanceDao.upsertInstances(
            listOf(
                instance.copy(
                    status = nextStatus.name,
                    completedAtEpochMillis = null,
                    updatedAtEpochMillis = now,
                ),
            ),
        )
        auditDao.insertLedger(
            PointsLedgerEntity(
                ledgerId = idGenerator.next(),
                taskId = key.taskId,
                occurrenceKey = key.occurrenceKey,
                groupId = currentGroup(key.taskId),
                delta = -completionEntry.delta,
                reason = "COMPLETION_UNDONE",
                createdAtEpochMillis = now,
            ),
        )
        executionDao.getMood(key.taskId, key.occurrenceKey)?.let {
            executionDao.upsertMood(it.copy(submittedAtEpochMillis = null, updatedAtEpochMillis = now))
        }
        log(instance, "COMPLETION_UNDONE", null)
        resultService.writeChanges(
            before,
            listOf(instance.taskDate),
            ResultRevisionReason.COMPLETION_UNDONE,
            null,
            listOf(key.taskId),
        )
    }

    override suspend fun reconcile(key: TaskInstanceKey): TaskInstanceEntity =
        database.withTransaction {
            val instance = requireInstance(key)
            val before = resultService.capture(listOf(instance.taskDate))
            val updated = reconcile(instance)
            resultService.writeChanges(
                before,
                listOf(instance.taskDate),
                ResultRevisionReason.DEADLINE_RECONCILED,
                null,
                listOf(key.taskId),
            )
            updated
        }

    private suspend fun requireExecutionTarget(instance: TaskInstanceEntity) {
        if (!isExecutionTargetReached(instance)) {
            fail(TaskOperationCode.EXECUTION_TARGET_NOT_REACHED, "执行目标尚未达成")
        }
    }

    private fun validateStepSnapshot(steps: List<InstanceStepEntity>) {
        if (steps.isEmpty() || steps.size > 50 || steps.map { it.position } != steps.indices.toList()) {
            fail(TaskOperationCode.STEP_NOT_FOUND, "步骤快照无效")
        }
        val ids = steps.map { it.stepId }
        if (ids.any { it.length != 16 || !it.all { ch -> ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '-' } } || ids.distinct().size != ids.size) {
            fail(TaskOperationCode.STEP_NOT_FOUND, "步骤 ID 无效")
        }
        var pendingSeen = false
        steps.forEach { step ->
            when (step.stepStatus) {
                "PENDING" -> {
                    if (step.required) fail(TaskOperationCode.REQUIRED_STEP_INCOMPLETE, "必需步骤未完成")
                    pendingSeen = true
                }
                "CONFIRMED" -> {
                    if (pendingSeen || !stepSatisfied(step)) fail(TaskOperationCode.EXECUTION_TARGET_NOT_REACHED, "步骤状态或目标无效")
                }
                "SKIPPED" -> if (step.required || pendingSeen) fail(TaskOperationCode.STEP_NOT_FOUND, "步骤跳过状态无效")
                else -> fail(TaskOperationCode.STEP_NOT_FOUND, "步骤状态无效")
            }
        }
    }

    private suspend fun isExecutionTargetReached(instance: TaskInstanceEntity): Boolean {
        val key = TaskInstanceKey(instance.taskId, instance.occurrenceKey)
        return when (instance.executionKind) {
            "STEPS" -> instanceDao.getInstanceSteps(instance.taskId, instance.occurrenceKey).let { steps ->
                runCatching { validateStepSnapshot(steps) }.isSuccess &&
                    steps.filter { it.required }.all { it.stepStatus == "CONFIRMED" && stepSatisfied(it) }
            }
            "MOOD" -> executionDao.getMood(key.taskId, key.occurrenceKey)?.let {
                it.rating in 1..5 && it.text.codePointCount(0, it.text.length) <= 2000
            } ?: false
            "NORMAL" -> true
            "COUNTER" -> (executionDao.getProgress(key.taskId, key.occurrenceKey)?.counterValue ?: 0) >=
                instance.requireTarget()
            "TIMER" -> (executionDao.getProgress(key.taskId, key.occurrenceKey)?.elapsedMillis ?: 0L) >=
                instance.requireTarget() * 1_000L
            "INFORMATION" -> {
                val content = executionDao.getSubmission(key.taskId, key.occurrenceKey)?.content?.trim().orEmpty()
                content.isNotEmpty() && content.codePointCount(0, content.length) <= INFORMATION_MAX_CODE_POINTS
            }
            else -> false
        }
    }

    private suspend fun executionState(instance: TaskInstanceEntity): ExecutionState =
        when (instance.executionKind) {
            "STEPS" -> ExecutionState.Steps(instanceDao.getInstanceSteps(instance.taskId, instance.occurrenceKey).map {
                StepState(it.stepId, it.position, it.name, it.required, it.completed, it.executionKind,
                    it.executionAction, it.executionTarget, it.stepStatus, it.counterValue, it.elapsedMillis,
                    it.informationContent, it.moodRating, it.moodText)
            })
            "MOOD" -> executionDao.getMood(instance.taskId, instance.occurrenceKey).let {
                ExecutionState.Mood(it?.rating, it?.text.orEmpty(), it?.submittedAtEpochMillis)
            }
            "NORMAL" -> ExecutionState.Normal
            "COUNTER" -> counterState(
                instance,
                executionDao.getProgress(instance.taskId, instance.occurrenceKey)?.counterValue ?: 0,
            )
            "TIMER" -> ExecutionState.Timer(
                elapsedMillis = executionDao.getProgress(instance.taskId, instance.occurrenceKey)?.elapsedMillis ?: 0,
                targetMillis = instance.requireTarget() * 1_000L,
            )
            "INFORMATION" -> executionDao.getSubmission(instance.taskId, instance.occurrenceKey).let {
                ExecutionState.Information(it?.content.orEmpty(), it?.submittedAtEpochMillis)
            }
            else -> fail(TaskOperationCode.EXECUTION_KIND_MISMATCH, "未知执行方式")
        }

    private fun counterState(instance: TaskInstanceEntity, value: Int): ExecutionState.Counter =
        ExecutionState.Counter(
            value = value,
            target = instance.requireTarget(),
            action = when (instance.executionAction) {
                1 -> CounterAction.SLIDER
                2 -> CounterAction.CLICK
                else -> fail(TaskOperationCode.EXECUTION_KIND_MISMATCH, "未知计数交互方式")
            },
        )

    private suspend fun requirePending(key: TaskInstanceKey): TaskInstanceEntity {
        val instance = reconcile(requireInstance(key))
        if (instance.status != TaskStatus.PENDING.name) {
            fail(TaskOperationCode.INSTANCE_NOT_PENDING, "只有待完成任务可以修改执行数据")
        }
        return instance
    }

    private suspend fun reconcile(instance: TaskInstanceEntity): TaskInstanceEntity {
        if (instance.status !in setOf(TaskStatus.NOT_STARTED.name, TaskStatus.PENDING.name)) return instance
        val expected = TaskStateMachine.statusAt(
            LocalDate.parse(instance.taskDate),
            instance.deadline?.let(LocalDateTime::parse),
            nowDateTime(),
        )
        if (expected.name == instance.status) return instance
        val updated = instance.copy(status = expected.name, updatedAtEpochMillis = clock.millis())
        instanceDao.upsertInstances(listOf(updated))
        log(instance, "STATUS_RECONCILED", "${instance.status}->${expected.name}")
        return updated
    }

    private suspend fun requireInstance(key: TaskInstanceKey): TaskInstanceEntity =
        instanceDao.getInstance(key.taskId, key.occurrenceKey)
            ?: fail(TaskOperationCode.INSTANCE_NOT_FOUND, "任务不存在")

    private suspend fun findStep(key: TaskInstanceKey, stepId: String): InstanceStepEntity =
        instanceDao.getInstanceSteps(key.taskId, key.occurrenceKey).firstOrNull { it.stepId == stepId }
            ?: fail(TaskOperationCode.STEP_NOT_FOUND, "步骤不存在")

    private suspend fun touchInstance(instance: TaskInstanceEntity) {
        instanceDao.upsertInstances(listOf(instance.copy(updatedAtEpochMillis = clock.millis())))
    }

    private suspend fun requireEditableStep(key: TaskInstanceKey, stepId: String): InstanceStepEntity {
        val instance = requirePending(key)
        if (instance.executionKind != "STEPS") fail(TaskOperationCode.EXECUTION_KIND_MISMATCH, "任务不是分步骤任务")
        val step = findStep(key, stepId)
        if (step.stepStatus != "PENDING") fail(TaskOperationCode.STEP_NOT_FOUND, "步骤已经确认")
        val prior = instanceDao.getInstanceSteps(key.taskId, key.occurrenceKey).filter { it.position < step.position }
        if (prior.any { it.stepStatus == "PENDING" }) fail(TaskOperationCode.STEP_NOT_FOUND, "步骤尚未解锁")
        return step
    }

    private fun stepSatisfied(step: InstanceStepEntity): Boolean = when (step.executionKind) {
        "NORMAL" -> true
        "COUNTER" -> (step.counterValue ?: 0) == (step.executionTarget ?: Int.MAX_VALUE)
        "TIMER" -> (step.elapsedMillis ?: 0L) == (step.executionTarget ?: Int.MAX_VALUE) * 1_000L
        "INFORMATION" -> !step.informationContent.isNullOrBlank() && step.informationContent!!.codePointCount(0, step.informationContent.length) <= INFORMATION_MAX_CODE_POINTS
        "MOOD" -> step.moodRating in 1..5 && (step.moodText ?: "").codePointCount(0, (step.moodText ?: "").length) <= INFORMATION_MAX_CODE_POINTS
        else -> false
    }

    private fun InstanceStepEntity.toStepState() = StepState(
        stepId, position, name, required, completed, executionKind, executionAction, executionTarget, stepStatus,
        counterValue, elapsedMillis, informationContent, moodRating, moodText,
    )

    private suspend fun currentGroup(taskId: String): String? {
        val definition = database.definitionDao().getDefinition(taskId)
            ?: fail(TaskOperationCode.INSTANCE_NOT_FOUND, "Task definition does not exist")
        return definition.groupId
    }

    private fun TaskInstanceEntity.requireTarget(): Int =
        executionTarget ?: fail(TaskOperationCode.EXECUTION_KIND_MISMATCH, "执行目标缺失")

    private suspend fun log(instance: TaskInstanceEntity, action: String, detail: String?) {
        auditDao.insertLogs(
            listOf(
                ActionLogEntity(
                    eventId = idGenerator.next(),
                    taskId = instance.taskId,
                    occurrenceKey = instance.occurrenceKey,
                    batchId = null,
                    action = action,
                    detail = detail,
                    createdAtEpochMillis = clock.millis(),
                ),
            ),
        )
    }

    private fun nowDateTime(): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(clock.millis()), clock.zone)

    private fun fail(code: TaskOperationCode, message: String): Nothing =
        throw TaskOperationException(code, message)

    private companion object {
        const val INFORMATION_MAX_CODE_POINTS = 2_000
    }
}
