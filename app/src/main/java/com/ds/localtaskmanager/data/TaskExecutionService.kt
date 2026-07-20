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
    suspend fun setCounter(key: TaskInstanceKey, value: Int): ExecutionState.Counter
    suspend fun addTimerElapsed(key: TaskInstanceKey, elapsedMillis: Long): ExecutionState.Timer
    suspend fun saveInformationDraft(key: TaskInstanceKey, content: String): ExecutionState.Information
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
            val changed = instanceDao.updateStep(
                key.taskId,
                key.occurrenceKey,
                position,
                completed,
                clock.millis(),
            )
            if (changed != 1) fail(TaskOperationCode.STEP_NOT_FOUND, "步骤不存在")
            log(instance, if (completed) "STEP_COMPLETED" else "STEP_UNDONE", position.toString())
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
        requireExecutionTarget(instance)
        val now = clock.millis()
        if (instance.executionKind == "INFORMATION") {
            val submission = executionDao.getSubmission(key.taskId, key.occurrenceKey)
                ?: fail(TaskOperationCode.EXECUTION_TARGET_NOT_REACHED, "告知正文尚未填写")
            executionDao.upsertSubmission(submission.copy(submittedAtEpochMillis = now, updatedAtEpochMillis = now))
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

    private suspend fun isExecutionTargetReached(instance: TaskInstanceEntity): Boolean {
        val key = TaskInstanceKey(instance.taskId, instance.occurrenceKey)
        return when (instance.executionKind) {
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
