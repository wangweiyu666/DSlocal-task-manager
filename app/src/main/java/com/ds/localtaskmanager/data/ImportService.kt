package com.ds.localtaskmanager.data

import androidx.room.withTransaction
import com.ds.localtaskmanager.data.dao.AuditDao
import com.ds.localtaskmanager.data.dao.DefinitionDao
import com.ds.localtaskmanager.data.dao.ExecutionDao
import com.ds.localtaskmanager.data.dao.InstanceDao
import com.ds.localtaskmanager.data.dao.ProfileDao
import com.ds.localtaskmanager.data.recurrence.RoomInstanceGenerationService
import com.ds.localtaskmanager.domain.RecordIdGenerator
import com.ds.localtaskmanager.domain.StepFingerprint
import com.ds.localtaskmanager.domain.TaskDay
import com.ds.localtaskmanager.domain.TaskStateMachine
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.execution.ExecutionSpec
import com.ds.localtaskmanager.domain.recurrence.RecurrenceDeadline
import com.ds.localtaskmanager.domain.recurrence.RecurrenceSpec
import com.ds.localtaskmanager.domain.recurrence.WeekdayMask
import com.ds.localtaskmanager.protocol.Dst1Decoder
import com.ds.localtaskmanager.protocol.Dst1Parser
import com.ds.localtaskmanager.protocol.DstBatch
import com.ds.localtaskmanager.protocol.DstGroupPatch
import com.ds.localtaskmanager.protocol.DstTask
import com.ds.localtaskmanager.protocol.Field
import com.ds.localtaskmanager.protocol.allTasks
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

enum class ImportChangeType {
    NEW,
    UPDATED,
    UNCHANGED,
    CANCELLED,
    RESTORED,
    MOVED,
    STEP_RESET,
    EXECUTION_RESET,
    INFORMATION_REVIEW_REQUIRED,
    RECURRENCE_ENABLED,
    RECURRENCE_UPDATED,
    RECURRENCE_DISABLED,
    RECURRENCE_RESUMED,
}

data class TaskImportChange(
    val taskId: String,
    val name: String,
    val types: Set<ImportChangeType>,
    val generatedOccurrences: List<String> = emptyList(),
)

data class ImportPreview(
    val batch: DstBatch,
    val taskChanges: List<TaskImportChange>,
    val groupUpdates: Int,
    val updatesDomName: Boolean,
) {
    val summary: String
        get() {
            val counts = ImportChangeType.entries.mapNotNull { type ->
                taskChanges.count { type in it.types }.takeIf { it > 0 }?.let { "$type=$it" }
            }
            return (counts + "积分组=$groupUpdates").joinToString("，")
        }
}

class DuplicateBatchException(batchId: String) :
    IllegalStateException("批次 $batchId 已经导入")

interface ImportService {
    suspend fun preview(encoded: String): ImportPreview
    suspend fun import(preview: ImportPreview): ImportPreview
}

class RoomImportService(
    private val database: AppDatabase,
    private val parser: Dst1Parser,
    private val clock: Clock,
    private val idGenerator: RecordIdGenerator,
) : ImportService {
    private val profileDao: ProfileDao get() = database.profileDao()
    private val definitionDao: DefinitionDao get() = database.definitionDao()
    private val instanceDao: InstanceDao get() = database.instanceDao()
    private val executionDao: ExecutionDao get() = database.executionDao()
    private val auditDao: AuditDao get() = database.auditDao()
    private val generationService by lazy {
        RoomInstanceGenerationService(database, clock, idGenerator)
    }

    override suspend fun preview(encoded: String): ImportPreview {
        val batch = parser.parse(Dst1Decoder.decode(encoded.trim()), nowDateTime())
        return preview(batch)
    }

    override suspend fun import(preview: ImportPreview): ImportPreview = database.withTransaction {
        if (profileDao.hasBatch(preview.batch.batchId)) throw DuplicateBatchException(preview.batch.batchId)
        val freshPreview = preview(preview.batch)
        applyBatch(freshPreview)
        freshPreview
    }

    private suspend fun preview(batch: DstBatch): ImportPreview {
        if (profileDao.hasBatch(batch.batchId)) throw DuplicateBatchException(batch.batchId)
        val tasks = batch.allTasks()
        val ids = (tasks.map { it.taskId } + batch.cancelledTaskIds).distinct()
        val oldDefinitions = if (ids.isEmpty()) emptyMap() else {
            definitionDao.getDefinitions(ids).associateBy { it.taskId }
        }
        val oldInstances = if (ids.isEmpty()) emptyMap() else {
            instanceDao.getOnceInstances(ids).associateBy { it.taskId }
        }
        val desiredDefinitions = tasks.associate { task ->
            task.taskId to task.toDefinition(oldDefinitions[task.taskId], nowMillis())
        }
        val generatedOccurrences = generationService.previewDefinitions(
            desiredDefinitions.values.filter { it.recurrenceFrequency != null },
            TaskDay.from(nowDateTime()),
        )
        val changes = tasks.map { task ->
            val oldDefinition = oldDefinitions[task.taskId]
            val oldInstance = oldInstances[task.taskId]
            val desired = desiredDefinitions.getValue(task.taskId)
            val types = linkedSetOf<ImportChangeType>()
            when {
                oldDefinition == null -> types += ImportChangeType.NEW
                oldDefinition.cancelled -> types += ImportChangeType.RESTORED
                oldDefinition.sameContent(desired) -> types += ImportChangeType.UNCHANGED
                else -> types += ImportChangeType.UPDATED
            }
            if (oldDefinition != null && oldDefinition.groupId != desired.groupId) {
                types += ImportChangeType.MOVED
            }
            when {
                oldDefinition == null && desired.recurrenceFrequency != null ->
                    types += ImportChangeType.RECURRENCE_ENABLED
                oldDefinition?.cancelled == true && desired.recurrenceFrequency != null ->
                    types += ImportChangeType.RECURRENCE_RESUMED
                oldDefinition?.recurrenceFrequency == null && desired.recurrenceFrequency != null ->
                    types += ImportChangeType.RECURRENCE_ENABLED
                oldDefinition?.recurrenceFrequency != null && desired.recurrenceFrequency == null ->
                    types += ImportChangeType.RECURRENCE_DISABLED
                oldDefinition != null && oldDefinition.recurrenceSignature() != desired.recurrenceSignature() ->
                    types += ImportChangeType.RECURRENCE_UPDATED
            }
            if (oldDefinition != null && oldDefinition.stepsFingerprint != desired.stepsFingerprint &&
                oldInstance?.status !in setOf(TaskStatus.COMPLETED.name, TaskStatus.CANCELLED.name)
            ) {
                types += ImportChangeType.STEP_RESET
            }
            if (oldDefinition != null && oldInstance?.isMutableForImport() == true) {
                if (oldDefinition.executionSignature() != desired.executionSignature()) {
                    types += ImportChangeType.EXECUTION_RESET
                } else if (oldDefinition.executionKind == "INFORMATION" &&
                    oldDefinition.description != desired.description
                ) {
                    types += ImportChangeType.INFORMATION_REVIEW_REQUIRED
                }
            }
            TaskImportChange(
                task.taskId,
                task.name,
                types,
                generatedOccurrences[task.taskId].orEmpty().map(LocalDate::toString),
            )
        }.toMutableList()
        batch.cancelledTaskIds.forEach { id ->
            val definition = oldDefinitions[id]
            val type = if (definition != null && !definition.cancelled) {
                ImportChangeType.CANCELLED
            } else {
                ImportChangeType.UNCHANGED
            }
            changes += TaskImportChange(id, definition?.name ?: id, setOf(type))
        }
        return ImportPreview(
            batch = batch,
            taskChanges = changes,
            groupUpdates = batch.groups.size,
            updatesDomName = batch.domName is Field.Value,
        )
    }

    private suspend fun applyBatch(preview: ImportPreview) {
        val batch = preview.batch
        val now = nowMillis()
        profileDao.insertBatch(ImportBatchEntity(batch.batchId, batch.note, now))
        applyProfile(batch, now)
        applyGroups(batch.groups, now)
        applyTasks(batch, now)
        applyCancellations(batch, now)
    }

    private suspend fun applyProfile(batch: DstBatch, now: Long) {
        val domName = (batch.domName as? Field.Value)?.value ?: return
        profileDao.upsertProfile(AppProfileEntity(domName = domName, updatedAtEpochMillis = now))
    }

    private suspend fun applyGroups(groups: List<DstGroupPatch>, now: Long) {
        if (groups.isEmpty()) return
        val old = definitionDao.getGroups(groups.map { it.groupId }).associateBy { it.groupId }
        val entities = groups.map { patch ->
            val existing = old[patch.groupId]
            TaskGroupEntity(
                groupId = patch.groupId,
                name = patch.name.valueOr(existing?.name ?: "未命名积分组"),
                completeMessage = patch.completeMessage.valueOrDefault(
                    existing?.completeMessage ?: "全部完成",
                    "全部完成",
                ),
                incompleteMessage = patch.incompleteMessage.valueOrDefault(
                    existing?.incompleteMessage ?: "未完成",
                    "未完成",
                ),
                archived = false,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
            )
        }
        definitionDao.upsertGroups(entities)
    }

    private suspend fun applyTasks(batch: DstBatch, now: Long) {
        val tasks = batch.allTasks()
        if (tasks.isEmpty()) return
        val ids = tasks.map { it.taskId }
        val oldDefinitions = definitionDao.getDefinitions(ids).associateBy { it.taskId }
        val oldInstances = instanceDao.getOnceInstances(ids).associateBy { it.taskId }
        val allOldInstances = instanceDao.getInstancesForTasks(ids).groupBy { it.taskId }
        val definitions = tasks.map { it.toDefinition(oldDefinitions[it.taskId], now) }
        definitionDao.upsertDefinitions(definitions)
        definitionDao.deleteStepDefinitions(ids)
        definitionDao.insertStepDefinitions(tasks.flatMap { task ->
            task.steps.mapIndexed { index, step ->
                TaskStepDefinitionEntity(task.taskId, index, step.name, step.required)
            }
        })

        tasks.forEach { task ->
            val oldDefinition = oldDefinitions[task.taskId]
            if (task.recurrence !is RecurrenceSpec.None) {
                if (oldDefinition?.recurrenceFrequency == null) {
                    allOldInstances[task.taskId].orEmpty()
                        .filter { it.occurrenceKey == "once" }
                        .forEach { cancelForTemplateConversion(it, batch.batchId, now) }
                }
                auditDao.insertLogs(
                    listOf(
                        ActionLogEntity(
                            eventId = idGenerator.next(),
                            taskId = task.taskId,
                            occurrenceKey = null,
                            batchId = batch.batchId,
                            action = if (oldDefinition == null) "RECURRENCE_IMPORTED" else "RECURRENCE_UPDATED",
                            detail = null,
                            createdAtEpochMillis = now,
                        ),
                    ),
                )
                generationService.reconcileTask(task.taskId, TaskDay.from(nowDateTime()), batch.batchId)
                return@forEach
            }
            if (oldDefinition?.recurrenceFrequency != null) {
                allOldInstances[task.taskId].orEmpty()
                    .filter { it.occurrenceKey != "once" }
                    .forEach { cancelForTemplateConversion(it, batch.batchId, now) }
            }
            val oldInstance = oldInstances[task.taskId]
            val fingerprintChanged = oldDefinition != null &&
                oldDefinition.stepsFingerprint != StepFingerprint.of(task.steps)
            val preserveEnded = oldInstance?.status in setOf(
                TaskStatus.COMPLETED.name,
                TaskStatus.MISSED.name,
            )
            val restored = oldDefinition?.cancelled == true || oldInstance?.status == TaskStatus.CANCELLED.name
            if (oldDefinition != null && oldInstance?.isMutableForImport() == true) {
                applyExecutionUpdate(task, oldDefinition, oldInstance, batch.batchId, now)
            }
            val instance = when {
                preserveEnded -> oldInstance!!.copy(updatedAtEpochMillis = now)
                else -> task.toInstance(oldInstance, restored, now)
            }
            instanceDao.upsertInstances(listOf(instance))
            if (oldInstance == null || restored || fingerprintChanged) {
                instanceDao.deleteInstanceSteps(task.taskId)
                instanceDao.insertInstanceSteps(task.steps.mapIndexed { index, step ->
                    InstanceStepEntity(
                        taskId = task.taskId,
                        occurrenceKey = "once",
                        position = index,
                        name = step.name,
                        required = step.required,
                        completed = false,
                        updatedAtEpochMillis = now,
                    )
                })
            }
            auditDao.insertLogs(
                listOf(
                    ActionLogEntity(
                        eventId = idGenerator.next(),
                        taskId = task.taskId,
                        occurrenceKey = "once",
                        batchId = batch.batchId,
                        action = if (oldDefinition == null) "IMPORTED" else "UPDATED",
                        detail = null,
                        createdAtEpochMillis = now,
                    ),
                ),
            )
        }
    }

    private suspend fun applyCancellations(batch: DstBatch, now: Long) {
        if (batch.cancelledTaskIds.isEmpty()) return
        val definitions = definitionDao.getDefinitions(batch.cancelledTaskIds)
        definitionDao.upsertDefinitions(definitions.map { it.copy(cancelled = true, updatedAtEpochMillis = now) })
        auditDao.insertLogs(definitions.filter { it.recurrenceFrequency != null }.map {
            ActionLogEntity(
                eventId = idGenerator.next(),
                taskId = it.taskId,
                occurrenceKey = null,
                batchId = batch.batchId,
                action = "RECURRENCE_CANCELLED",
                detail = null,
                createdAtEpochMillis = now,
            )
        })
        val instances = instanceDao.getInstancesForTasks(batch.cancelledTaskIds)
        val changedInstances = instances.filter { it.status != TaskStatus.COMPLETED.name && it.status != TaskStatus.CANCELLED.name }
        instanceDao.upsertInstances(changedInstances.map {
            it.copy(status = TaskStatus.CANCELLED.name, updatedAtEpochMillis = now)
        })
        auditDao.insertLogs(changedInstances.map {
            ActionLogEntity(
                eventId = idGenerator.next(),
                taskId = it.taskId,
                occurrenceKey = it.occurrenceKey,
                batchId = batch.batchId,
                action = "CANCELLED",
                detail = null,
                createdAtEpochMillis = now,
            )
        })
    }

    private suspend fun cancelForTemplateConversion(
        instance: TaskInstanceEntity,
        batchId: String,
        now: Long,
    ) {
        if (instance.status in setOf(TaskStatus.COMPLETED.name, TaskStatus.CANCELLED.name)) return
        instanceDao.upsertInstances(
            listOf(instance.copy(status = TaskStatus.CANCELLED.name, updatedAtEpochMillis = now)),
        )
        auditDao.insertLogs(
            listOf(
                ActionLogEntity(
                    eventId = idGenerator.next(),
                    taskId = instance.taskId,
                    occurrenceKey = instance.occurrenceKey,
                    batchId = batchId,
                    action = "TEMPLATE_KIND_CHANGED",
                    detail = null,
                    createdAtEpochMillis = now,
                ),
            ),
        )
    }

    private fun DstTask.toDefinition(old: TaskDefinitionEntity?, now: Long): TaskDefinitionEntity {
        val storedRecurrence = recurrence.toStoredRecurrence(old, TaskDay.from(nowDateTime()))
        return TaskDefinitionEntity(
            taskId = taskId,
            name = name,
            description = description,
            groupId = groupId,
            required = required,
            taskDate = taskDate.toString(),
            deadline = deadline?.toString(),
            points = points,
            sortOrder = sortOrder,
            completionMessage = completionMessage,
            stepsFingerprint = StepFingerprint.of(steps),
            cancelled = false,
            createdAtEpochMillis = old?.createdAtEpochMillis ?: now,
            updatedAtEpochMillis = now,
            recurrenceFrequency = storedRecurrence.frequency,
            recurrenceStartDate = storedRecurrence.startDate,
            recurrenceEndDate = storedRecurrence.endDate,
            recurrenceCount = storedRecurrence.count,
            recurrenceWeekdaysMask = storedRecurrence.weekdaysMask,
            recurrenceDeadlineTime = storedRecurrence.deadlineTime,
            executionKind = execution.kindName(),
            executionAction = execution.actionValue(),
            executionTarget = execution.targetValue(),
        )
    }

    private fun DstTask.toInstance(
        old: TaskInstanceEntity?,
        restored: Boolean,
        now: Long,
    ): TaskInstanceEntity {
        val timeStatus = TaskStateMachine.statusAt(taskDate, deadline, nowDateTime()).name
        val status = when {
            old == null || restored -> timeStatus
            old.status == TaskStatus.MISSED.name -> TaskStatus.MISSED.name
            else -> timeStatus
        }
        return TaskInstanceEntity(
            taskId = taskId,
            occurrenceKey = "once",
            name = name,
            description = description,
            taskDate = taskDate.toString(),
            deadline = deadline?.toString(),
            groupId = groupId,
            required = required,
            points = points,
            sortOrder = sortOrder,
            completionMessage = completionMessage,
            status = status,
            completedAtEpochMillis = null,
            createdAtEpochMillis = old?.createdAtEpochMillis ?: now,
            updatedAtEpochMillis = now,
            executionKind = execution.kindName(),
            executionAction = execution.actionValue(),
            executionTarget = execution.targetValue(),
        )
    }

    private suspend fun applyExecutionUpdate(
        task: DstTask,
        oldDefinition: TaskDefinitionEntity,
        oldInstance: TaskInstanceEntity,
        batchId: String,
        now: Long,
    ) {
        if (oldDefinition.executionSignature() == task.execution.signature()) {
            if (oldDefinition.executionKind == "INFORMATION" && oldDefinition.description != task.description) {
                logImportExecution(
                    oldInstance,
                    batchId,
                    "INFORMATION_REQUIREMENT_CHANGED",
                    "{\"draftPreserved\":true}",
                    now,
                )
            }
            return
        }

        val progress = executionDao.getProgress(oldInstance.taskId, oldInstance.occurrenceKey)
        val detail = "{\"oldKind\":\"${oldDefinition.executionKind}\"," +
            "\"newKind\":\"${task.execution.kindName()}\"," +
            "\"counterValue\":${progress?.counterValue ?: "null"}," +
            "\"elapsedMillis\":${progress?.elapsedMillis ?: "null"}}"
        executionDao.deleteProgress(oldInstance.taskId, oldInstance.occurrenceKey)
        if (oldDefinition.executionKind == "INFORMATION" && task.execution.kindName() != "INFORMATION") {
            executionDao.deleteSubmission(oldInstance.taskId, oldInstance.occurrenceKey)
        }
        logImportExecution(oldInstance, batchId, "EXECUTION_RESET", detail, now)
    }

    private suspend fun logImportExecution(
        instance: TaskInstanceEntity,
        batchId: String,
        action: String,
        detail: String,
        now: Long,
    ) {
        auditDao.insertLogs(
            listOf(
                ActionLogEntity(
                    eventId = idGenerator.next(),
                    taskId = instance.taskId,
                    occurrenceKey = instance.occurrenceKey,
                    batchId = batchId,
                    action = action,
                    detail = detail,
                    createdAtEpochMillis = now,
                ),
            ),
        )
    }

    private fun TaskInstanceEntity.isMutableForImport(): Boolean =
        status in setOf(TaskStatus.NOT_STARTED.name, TaskStatus.PENDING.name)

    private data class StoredRecurrence(
        val frequency: Int?,
        val startDate: String?,
        val endDate: String?,
        val count: Int?,
        val weekdaysMask: Int?,
        val deadlineTime: String?,
    )

    private fun RecurrenceSpec.toStoredRecurrence(
        old: TaskDefinitionEntity?,
        currentTaskDate: LocalDate,
    ): StoredRecurrence {
        if (this is RecurrenceSpec.None) {
            return StoredRecurrence(null, null, null, null, null, null)
        }
        val frequency = if (this is RecurrenceSpec.Daily) 1 else 2
        val start = when (this) {
            is RecurrenceSpec.Daily -> startDate
            is RecurrenceSpec.Weekly -> startDate
            RecurrenceSpec.None -> null
        }
        val end = when (this) {
            is RecurrenceSpec.Daily -> endDate
            is RecurrenceSpec.Weekly -> endDate
            RecurrenceSpec.None -> null
        }
        val count = when (this) {
            is RecurrenceSpec.Daily -> maxOccurrences
            is RecurrenceSpec.Weekly -> maxOccurrences
            RecurrenceSpec.None -> null
        }
        val weekdaysMask = (this as? RecurrenceSpec.Weekly)?.weekdays?.let(WeekdayMask::encode)
        val deadline = when (val value = when (this) {
            is RecurrenceSpec.Daily -> deadline
            is RecurrenceSpec.Weekly -> deadline
            RecurrenceSpec.None -> RecurrenceDeadline.Default
        }) {
            RecurrenceDeadline.Default -> "04:00"
            RecurrenceDeadline.None -> null
            is RecurrenceDeadline.At -> value.time.toString()
        }
        val sameRule = old != null && !old.cancelled &&
            old.recurrenceFrequency == frequency &&
            old.recurrenceEndDate == end?.toString() &&
            old.recurrenceCount == count &&
            old.recurrenceWeekdaysMask == weekdaysMask &&
            old.recurrenceDeadlineTime == deadline &&
            (start == null || old.recurrenceStartDate == start.toString())
        val effectiveStart = if (sameRule) {
            checkNotNull(old?.recurrenceStartDate).let(LocalDate::parse)
        } else {
            maxOf(start ?: currentTaskDate, currentTaskDate)
        }
        return StoredRecurrence(
            frequency = frequency,
            startDate = effectiveStart.toString(),
            endDate = end?.toString(),
            count = count,
            weekdaysMask = weekdaysMask,
            deadlineTime = deadline,
        )
    }

    private fun TaskDefinitionEntity.recurrenceSignature(): String =
        listOf(
            recurrenceFrequency,
            recurrenceStartDate,
            recurrenceEndDate,
            recurrenceCount,
            recurrenceWeekdaysMask,
            recurrenceDeadlineTime,
        ).joinToString(":")

    private fun TaskDefinitionEntity.executionSignature(): String =
        "$executionKind:${executionAction ?: ""}:${executionTarget ?: ""}"

    private fun ExecutionSpec.signature(): String =
        "${kindName()}:${actionValue() ?: ""}:${targetValue() ?: ""}"

    private fun ExecutionSpec.kindName(): String = when (this) {
        ExecutionSpec.Normal -> "NORMAL"
        is ExecutionSpec.Counter -> "COUNTER"
        is ExecutionSpec.Timer -> "TIMER"
        ExecutionSpec.Information -> "INFORMATION"
    }

    private fun ExecutionSpec.actionValue(): Int? =
        (this as? ExecutionSpec.Counter)?.action?.protocolValue

    private fun ExecutionSpec.targetValue(): Int? = when (this) {
        is ExecutionSpec.Counter -> target
        is ExecutionSpec.Timer -> targetSeconds
        else -> null
    }

    private fun TaskDefinitionEntity.sameContent(other: TaskDefinitionEntity): Boolean =
        copy(createdAtEpochMillis = 0, updatedAtEpochMillis = 0) ==
            other.copy(createdAtEpochMillis = 0, updatedAtEpochMillis = 0)

    private fun <T> Field<T>.valueOr(fallback: T): T = when (this) {
        Field.Missing -> fallback
        is Field.Value -> value
    }

    private fun Field<String>.valueOrDefault(current: String, default: String): String = when (this) {
        Field.Missing -> current
        is Field.Value -> value.ifEmpty { default }
    }

    private fun nowMillis(): Long = clock.millis()

    private fun nowDateTime(): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(clock.millis()), clock.zone)
}
