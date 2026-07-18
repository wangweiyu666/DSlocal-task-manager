package com.ds.localtaskmanager.data

import androidx.room.withTransaction
import com.ds.localtaskmanager.data.dao.AuditDao
import com.ds.localtaskmanager.data.dao.DefinitionDao
import com.ds.localtaskmanager.data.dao.InstanceDao
import com.ds.localtaskmanager.data.dao.ProfileDao
import com.ds.localtaskmanager.domain.RecordIdGenerator
import com.ds.localtaskmanager.domain.StepFingerprint
import com.ds.localtaskmanager.domain.TaskStateMachine
import com.ds.localtaskmanager.domain.TaskStatus
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
}

data class TaskImportChange(
    val taskId: String,
    val name: String,
    val types: Set<ImportChangeType>,
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
    private val auditDao: AuditDao get() = database.auditDao()

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
        val changes = tasks.map { task ->
            val oldDefinition = oldDefinitions[task.taskId]
            val oldInstance = oldInstances[task.taskId]
            val desired = task.toDefinition(oldDefinition, nowMillis())
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
            if (oldDefinition != null && oldDefinition.stepsFingerprint != desired.stepsFingerprint &&
                oldInstance?.status !in setOf(TaskStatus.COMPLETED.name, TaskStatus.CANCELLED.name)
            ) {
                types += ImportChangeType.STEP_RESET
            }
            TaskImportChange(task.taskId, task.name, types)
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
            val oldInstance = oldInstances[task.taskId]
            val fingerprintChanged = oldDefinition != null &&
                oldDefinition.stepsFingerprint != StepFingerprint.of(task.steps)
            val preserveCompleted = oldInstance?.status == TaskStatus.COMPLETED.name
            val restored = oldDefinition?.cancelled == true || oldInstance?.status == TaskStatus.CANCELLED.name
            val instance = when {
                preserveCompleted -> oldInstance.copy(updatedAtEpochMillis = now)
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
        val instances = instanceDao.getOnceInstances(batch.cancelledTaskIds)
        instanceDao.upsertInstances(instances.map {
            if (it.status == TaskStatus.COMPLETED.name) it else {
                it.copy(status = TaskStatus.CANCELLED.name, updatedAtEpochMillis = now)
            }
        })
        auditDao.insertLogs(instances.map {
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

    private fun DstTask.toDefinition(old: TaskDefinitionEntity?, now: Long) = TaskDefinitionEntity(
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
    )

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
        )
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
