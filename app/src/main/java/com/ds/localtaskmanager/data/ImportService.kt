package com.ds.localtaskmanager.data

import androidx.room.withTransaction
import com.ds.localtaskmanager.data.dao.AuditDao
import com.ds.localtaskmanager.data.dao.DefinitionDao
import com.ds.localtaskmanager.data.dao.ExecutionDao
import com.ds.localtaskmanager.data.dao.InstanceDao
import com.ds.localtaskmanager.data.dao.ProfileDao
import com.ds.localtaskmanager.data.recurrence.RoomInstanceGenerationService
import com.ds.localtaskmanager.data.result.HistoricalPointsTransferService
import com.ds.localtaskmanager.data.result.ResultRecalculationService
import com.ds.localtaskmanager.data.result.ResultRevisionReason
import com.ds.localtaskmanager.domain.RecordIdGenerator
import com.ds.localtaskmanager.domain.StepFingerprint
import com.ds.localtaskmanager.domain.TaskDay
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.TaskStateMachine
import com.ds.localtaskmanager.domain.execution.ExecutionSpec
import com.ds.localtaskmanager.domain.update.InstanceUpdatePlan
import com.ds.localtaskmanager.domain.update.InstanceUpdatePlanner
import com.ds.localtaskmanager.domain.update.InstanceUpdateRequest
import com.ds.localtaskmanager.domain.recurrence.RecurrenceDeadline
import com.ds.localtaskmanager.domain.recurrence.RecurrenceSpec
import com.ds.localtaskmanager.domain.recurrence.WeekdayMask
import com.ds.localtaskmanager.protocol.Dst1Decoder
import com.ds.localtaskmanager.protocol.Dst1Parser
import com.ds.localtaskmanager.protocol.DstBatch
import com.ds.localtaskmanager.protocol.DstGroupPatch
import com.ds.localtaskmanager.protocol.DstTask
import com.ds.localtaskmanager.protocol.DstOccurrenceException
import com.ds.localtaskmanager.protocol.Dst1ErrorCode
import com.ds.localtaskmanager.protocol.Dst1ValidationException
import com.ds.localtaskmanager.protocol.Field
import com.ds.localtaskmanager.protocol.allTasks
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

enum class ImportChangeType {
    NEW,
    UPDATED,
    UNCHANGED,
    CANCELLED,
    RESTORED,
    MOVED,
    DATE_MOVED,
    DEADLINE_EXTENDED,
    REOPENED,
    HISTORICAL_POINTS_MOVED,
    HISTORICAL_RESULT_CHANGED,
    STEP_RESET,
    EXECUTION_RESET,
    INFORMATION_REVIEW_REQUIRED,
    RECURRENCE_ENABLED,
    RECURRENCE_UPDATED,
    RECURRENCE_DISABLED,
    RECURRENCE_RESUMED,
    OCCURRENCE_UPDATED,
    OCCURRENCE_CANCELLED,
    OCCURRENCE_RESTORED,
    OCCURRENCE_CLEARED,
    OCCURRENCE_IGNORED,
}

data class TaskImportChange(
    val taskId: String,
    val name: String,
    val types: Set<ImportChangeType>,
    val generatedOccurrences: List<String> = emptyList(),
    val oldDate: String? = null,
    val newDate: String? = null,
    val oldDeadline: String? = null,
    val newDeadline: String? = null,
    val oldStatus: String? = null,
    val newStatus: String? = null,
    val historicalPointsMoved: Int = 0,
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
    private val resultService by lazy { ResultRecalculationService(database, clock, idGenerator) }
    private val pointsTransferService by lazy { HistoricalPointsTransferService(database, clock, idGenerator) }

    override suspend fun preview(encoded: String): ImportPreview {
        val decoded = Dst1Decoder.decodeEnvelope(encoded.trim())
        val batch = parser.parse(decoded.json, nowDateTime(), decoded.minorVersion)
        return preview(batch)
    }

    override suspend fun import(preview: ImportPreview): ImportPreview = database.withTransaction {
        if (profileDao.hasBatch(preview.batch.batchId)) throw DuplicateBatchException(preview.batch.batchId)
        val freshPreview = preview(preview.batch)
        val taskIds = (freshPreview.batch.allTasks().map { it.taskId } + freshPreview.batch.cancelledTaskIds +
            freshPreview.batch.exceptions.map { it.taskId }).distinct()
        val oldDefinitions = if (taskIds.isEmpty()) emptyMap() else {
            definitionDao.getDefinitions(taskIds).associateBy { it.taskId }
        }
        val beforeDates = if (taskIds.isEmpty()) emptyList() else {
            instanceDao.getInstancesForTasks(taskIds).map { it.taskDate }.distinct()
        }
        val before = resultService.capture(beforeDates)
        applyBatch(freshPreview)
        freshPreview.batch.allTasks().forEach { task ->
            val oldGroup = oldDefinitions[task.taskId]?.groupId
            val newGroup = definitionDao.getDefinition(task.taskId)?.groupId
            if (oldDefinitions.containsKey(task.taskId) && oldGroup != newGroup) {
                pointsTransferService.moveTaskToGroup(task.taskId, newGroup)
            }
        }
        val afterDates = if (taskIds.isEmpty()) emptyList() else {
            instanceDao.getInstancesForTasks(taskIds).map { it.taskDate }.distinct()
        }
        val changedTaskIds = freshPreview.taskChanges
            .filterNot { it.types == setOf(ImportChangeType.UNCHANGED) }
            .map { it.taskId }
        val resultReason = freshPreview.resultRevisionReason()
        resultService.writeChanges(
            before,
            afterDates,
            resultReason,
            freshPreview.batch.batchId,
            changedTaskIds,
        )
        freshPreview
    }

    private suspend fun preview(batch: DstBatch): ImportPreview {
        if (profileDao.hasBatch(batch.batchId)) throw DuplicateBatchException(batch.batchId)
        val tasks = batch.allTasks()
        val ids = (tasks.map { it.taskId } + batch.cancelledTaskIds + batch.exceptions.map { it.taskId }).distinct()
        val oldDefinitions = if (ids.isEmpty()) emptyMap() else {
            definitionDao.getDefinitions(ids).associateBy { it.taskId }
        }
        val oldInstances = if (ids.isEmpty()) emptyMap() else {
            instanceDao.getOnceInstances(ids).associateBy { it.taskId }
        }
        val updatePlans = tasks.associate { task ->
            task.taskId to task.toUpdatePlan(oldDefinitions[task.taskId], oldInstances[task.taskId])
        }
        val desiredDefinitions = tasks.associate { task ->
            task.taskId to task.toDefinition(
                oldDefinitions[task.taskId],
                nowMillis(),
                updatePlans.getValue(task.taskId),
            )
        }
        val generatedOccurrences = generationService.previewDefinitions(
            desiredDefinitions.values.filter { it.recurrenceFrequency != null },
            TaskDay.from(nowDateTime()),
        )
        val dateMovedTaskIds = tasks.filter { task ->
            val old = oldDefinitions[task.taskId]
            val desired = desiredDefinitions.getValue(task.taskId)
            old?.recurrenceFrequency == null && desired.recurrenceFrequency == null &&
                updatePlans.getValue(task.taskId).dateMoved
        }.map { it.taskId }
        val movedNetPoints = if (dateMovedTaskIds.isEmpty()) emptyMap() else {
            auditDao.getOnceNetPoints(dateMovedTaskIds).associate { it.taskId to it.netPoints }
        }
        val changes = tasks.map { task ->
            val oldDefinition = oldDefinitions[task.taskId]
            val oldInstance = oldInstances[task.taskId]
            val desired = desiredDefinitions.getValue(task.taskId)
            val updatePlan = updatePlans.getValue(task.taskId)
            val singleToSingle = oldDefinition?.recurrenceFrequency == null && desired.recurrenceFrequency == null
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
            if (singleToSingle && updatePlan.dateMoved) {
                types += ImportChangeType.DATE_MOVED
            }
            if (singleToSingle && updatePlan.deadlineExtended) {
                types += ImportChangeType.DEADLINE_EXTENDED
            }
            if (singleToSingle && updatePlan.reopened) {
                types += ImportChangeType.REOPENED
            }
            if (singleToSingle && oldInstance != null &&
                (updatePlan.dateMoved || oldInstance.status != updatePlan.status.name)
            ) {
                types += ImportChangeType.HISTORICAL_RESULT_CHANGED
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
                oldInstance?.isMutableForImport() == true
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
            val historicalPointsMoved = if (singleToSingle && updatePlan.dateMoved) {
                movedNetPoints[task.taskId] ?: 0
            } else {
                0
            }
            if (historicalPointsMoved != 0) types += ImportChangeType.HISTORICAL_POINTS_MOVED
            TaskImportChange(
                taskId = task.taskId,
                name = task.name,
                types = types,
                generatedOccurrences = generatedOccurrences[task.taskId].orEmpty().map(LocalDate::toString),
                oldDate = oldInstance?.taskDate,
                newDate = updatePlan.taskDate.toString(),
                oldDeadline = oldInstance?.deadline,
                newDeadline = updatePlan.deadline?.toString(),
                oldStatus = oldInstance?.status,
                newStatus = updatePlan.status.name,
                historicalPointsMoved = historicalPointsMoved,
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
        batch.exceptions.forEachIndexed { index, exception ->
            val definition = desiredDefinitions[exception.taskId] ?: oldDefinitions[exception.taskId]
            validateExceptionTarget(exception, definition, index)
            val instance = instanceDao.getInstance(exception.taskId, exception.occurrenceDate.toString())
            val stored = database.recurrenceExceptionDao().get(exception.taskId, exception.occurrenceDate.toString())
            val types = linkedSetOf(when {
                exception.clearsException -> ImportChangeType.OCCURRENCE_CLEARED
                instance?.status == TaskStatus.COMPLETED.name -> ImportChangeType.OCCURRENCE_IGNORED
                instance?.status == TaskStatus.MISSED.name && !exception.cancelled && !exception.reopensMissed() ->
                    ImportChangeType.OCCURRENCE_IGNORED
                exception.cancelled -> ImportChangeType.OCCURRENCE_CANCELLED
                instance?.status == TaskStatus.CANCELLED.name || stored?.cancelled == true ->
                    ImportChangeType.OCCURRENCE_RESTORED
                else -> ImportChangeType.OCCURRENCE_UPDATED
            })
            if (ImportChangeType.OCCURRENCE_IGNORED !in types) {
                if (exception.clearsException || exception.steps is Field.Value) types += ImportChangeType.STEP_RESET
                if (exception.clearsException || exception.cancelled || exception.execution is Field.Value) {
                    types += ImportChangeType.EXECUTION_RESET
                }
                if (!exception.cancelled && exception.description is Field.Value &&
                    (exception.execution.valueOr(definition?.toExecutionSpec()) is ExecutionSpec.Information)
                ) types += ImportChangeType.INFORMATION_REVIEW_REQUIRED
            }
            changes += TaskImportChange(
                taskId = exception.taskId,
                name = definition?.name ?: exception.taskId,
                types = types,
                oldDate = exception.occurrenceDate.toString(),
                newDate = exception.occurrenceDate.toString(),
                oldDeadline = instance?.deadline,
                newDeadline = (exception.deadline as? Field.Value)?.value?.toString() ?: instance?.deadline,
                oldStatus = instance?.status,
            )
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
        applyExceptions(batch, now)
        applyCancellations(batch, now)
    }

    private suspend fun validateExceptionTarget(
        exception: DstOccurrenceException,
        definition: TaskDefinitionEntity?,
        index: Int,
    ) {
        if (definition == null || definition.cancelled || definition.recurrenceFrequency == null) {
            throw Dst1ValidationException(
                Dst1ErrorCode.INVALID_VALUE,
                "e[$index].i",
                "单日例外目标必须是有效的重复模板",
            )
        }
        val date = exception.occurrenceDate
        val existing = instanceDao.getInstance(exception.taskId, date.toString())
        val effectiveDeadline = exception.deadline.valueOr(definition.deadlineForOccurrence(date))
        val effectiveReminders = exception.reminders.valueOr(definition.reminderMinutesJson.toReminderList())
        if (effectiveReminders.isNotEmpty() && effectiveDeadline == null) {
            throw Dst1ValidationException(
                Dst1ErrorCode.CONFLICTING_FIELDS,
                "e[$index].h",
                "单日提醒需要模板或例外提供截止时间",
            )
        }
        if (existing != null) return
        if (date < TaskDay.from(nowDateTime())) {
            throw Dst1ValidationException(Dst1ErrorCode.INVALID_DATE, "e[$index].y", "不能为未生成的过去日期补建单日例外")
        }
        if (date !in generationService.previewDefinition(definition, date)) {
            throw Dst1ValidationException(Dst1ErrorCode.INVALID_DATE, "e[$index].y", "日期不属于重复任务的有效计划或已超出剩余次数")
        }
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
                archived = existing?.archived ?: false,
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
        val updatePlans = tasks.associate { task ->
            task.taskId to task.toUpdatePlan(oldDefinitions[task.taskId], oldInstances[task.taskId])
        }
        val definitions = tasks.map {
            it.toDefinition(oldDefinitions[it.taskId], now, updatePlans.getValue(it.taskId))
        }
        definitionDao.upsertDefinitions(definitions)
        val groupNames = tasks.mapNotNull { it.groupId }.distinct().let { groupIds ->
            if (groupIds.isEmpty()) emptyMap() else definitionDao.getGroups(groupIds).associate { it.groupId to it.name }
        }
        definitionDao.deleteStepDefinitions(ids)
        definitionDao.insertStepDefinitions(tasks.flatMap { task ->
            task.steps.mapIndexed { index, step ->
                TaskStepDefinitionEntity(task.taskId, index, step.name, step.required)
            }
        })

        tasks.forEach { task ->
            val oldDefinition = oldDefinitions[task.taskId]
            val updatePlan = updatePlans.getValue(task.taskId)
            if (task.recurrence !is RecurrenceSpec.None) {
                val mutableInstances = allOldInstances[task.taskId].orEmpty()
                    .filter { it.isMutableForImport() }
                if (mutableInstances.isNotEmpty()) {
                    instanceDao.upsertInstances(
                        mutableInstances.map {
                            it.copy(
                                reminderMinutesJson = task.reminderMinutes.toStorageJson(),
                                updatedAtEpochMillis = now,
                            )
                        },
                    )
                }
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
                cleanupInvalidFutureExceptions(definitions.first { it.taskId == task.taskId }, batch.batchId, now)
                generationService.reconcileTask(task.taskId, TaskDay.from(nowDateTime()), batch.batchId)
                return@forEach
            }
            if (oldDefinition?.recurrenceFrequency != null) {
                deleteUngeneratedExceptions(task.taskId, batch.batchId, now)
                allOldInstances[task.taskId].orEmpty()
                    .filter { it.occurrenceKey != "once" }
                    .forEach { cancelForTemplateConversion(it, batch.batchId, now) }
            }
            val oldInstance = oldInstances[task.taskId]
            val fingerprintChanged = oldDefinition != null &&
                oldDefinition.stepsFingerprint != StepFingerprint.of(task.steps)
            val restored = oldDefinition?.cancelled == true || oldInstance?.status == TaskStatus.CANCELLED.name
            if (oldDefinition != null && oldInstance?.isMutableForImport() == true) {
                applyExecutionUpdate(task, oldDefinition, oldInstance, batch.batchId, now)
            }
            val instance = when {
                oldInstance?.status == TaskStatus.COMPLETED.name && updatePlan.dateMoved -> oldInstance.copy(
                    taskDate = updatePlan.taskDate.toString(),
                    deadline = updatePlan.deadline?.toString(),
                    updatedAtEpochMillis = now,
                )
                oldInstance?.status == TaskStatus.COMPLETED.name -> oldInstance.copy(updatedAtEpochMillis = now)
                oldInstance?.status == TaskStatus.MISSED.name &&
                    (updatePlan.deadlineExtended || updatePlan.dateMoved) -> oldInstance.copy(
                        taskDate = updatePlan.taskDate.toString(),
                    deadline = updatePlan.deadline?.toString(),
                    status = updatePlan.status.name,
                    reminderMinutesJson = task.reminderMinutes.toStorageJson(),
                    publishedAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                )
                oldInstance?.status == TaskStatus.MISSED.name -> oldInstance.copy(updatedAtEpochMillis = now)
                else -> task.toInstance(oldInstance, now, updatePlan, groupNames[task.groupId])
            }
            instanceDao.upsertInstances(listOf(instance))
            if (oldInstance == null || restored || (fingerprintChanged && oldInstance.isMutableForImport())) {
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
            val action = when {
                updatePlan.dateMoved -> "TASK_DATE_MOVED"
                updatePlan.reopened -> "TASK_REOPENED"
                updatePlan.deadlineExtended -> "DEADLINE_EXTENDED"
                oldDefinition == null -> "IMPORTED"
                else -> "UPDATED"
            }
            val detail = when (action) {
                "TASK_DATE_MOVED" -> "{\"oldDate\":\"${oldInstance?.taskDate}\",\"newDate\":\"${updatePlan.taskDate}\"," +
                    "\"oldStatus\":\"${oldInstance?.status}\",\"newStatus\":\"${instance.status}\"}"
                "TASK_REOPENED", "DEADLINE_EXTENDED" ->
                    "{\"oldDeadline\":${oldInstance?.deadline.jsonValue()},\"newDeadline\":${instance.deadline.jsonValue()}," +
                        "\"oldStatus\":\"${oldInstance?.status}\",\"newStatus\":\"${instance.status}\"}"
                else -> null
            }
            auditDao.insertLogs(
                listOf(
                    ActionLogEntity(
                        eventId = idGenerator.next(),
                        taskId = task.taskId,
                        occurrenceKey = "once",
                        batchId = batch.batchId,
                        action = action,
                        detail = detail,
                        createdAtEpochMillis = now,
                    ),
                ),
            )
        }
    }

    private suspend fun cleanupInvalidFutureExceptions(
        definition: TaskDefinitionEntity,
        batchId: String,
        now: Long,
    ) {
        val today = TaskDay.from(nowDateTime())
        val candidates = database.recurrenceExceptionDao().forTasks(listOf(definition.taskId))
        for (stored in candidates) {
            val date = LocalDate.parse(stored.occurrenceDate)
            val exists = instanceDao.getInstance(definition.taskId, stored.occurrenceDate) != null
            val stalePast = date < today && !exists
            val invalidFuture = date >= today && !exists && date !in generationService.previewDefinition(definition, date)
            if (stalePast || invalidFuture) {
                database.recurrenceExceptionDao().delete(definition.taskId, stored.occurrenceDate)
                auditDao.insertLogs(
                    listOf(
                        ActionLogEntity(
                            idGenerator.next(), definition.taskId, null, batchId,
                            "OCCURRENCE_EXCEPTION_INVALIDATED", "{\"date\":\"${stored.occurrenceDate}\"}", now,
                        ),
                    ),
                )
            }
        }
    }

    private suspend fun deleteUngeneratedExceptions(taskId: String, batchId: String, now: Long) {
        for (stored in database.recurrenceExceptionDao().forTask(taskId)) {
            if (instanceDao.getInstance(taskId, stored.occurrenceDate) != null) continue
            database.recurrenceExceptionDao().delete(taskId, stored.occurrenceDate)
            auditDao.insertLogs(
                listOf(
                    ActionLogEntity(
                        idGenerator.next(), taskId, null, batchId,
                        "OCCURRENCE_EXCEPTION_INVALIDATED",
                        "{\"date\":\"${stored.occurrenceDate}\",\"reason\":\"recurrence_removed\"}", now,
                    ),
                ),
            )
        }
    }

    private suspend fun applyExceptions(batch: DstBatch, now: Long) {
        for (exception in batch.exceptions) {
            val definition = checkNotNull(definitionDao.getDefinition(exception.taskId))
            val date = exception.occurrenceDate.toString()
            val existing = instanceDao.getInstance(exception.taskId, date)
            val stored = database.recurrenceExceptionDao().get(exception.taskId, date)

            if (exception.clearsException) {
                database.recurrenceExceptionDao().delete(exception.taskId, date)
                if (existing != null && existing.status in setOf(
                        TaskStatus.NOT_STARTED.name,
                        TaskStatus.PENDING.name,
                        TaskStatus.CANCELLED.name,
                    )
                ) {
                    applyExceptionToInstance(definition, exception, existing, batch.batchId, now, clearing = true)
                }
                auditOccurrence(exception, batch.batchId, "OCCURRENCE_EXCEPTION_CLEARED", now)
                continue
            }
            if (existing?.status == TaskStatus.COMPLETED.name ||
                (existing?.status == TaskStatus.MISSED.name && !exception.cancelled && !exception.reopensMissed())
            ) {
                auditOccurrence(exception, batch.batchId, "OCCURRENCE_EXCEPTION_IGNORED", now)
                continue
            }
            database.recurrenceExceptionDao().upsert(
                RecurrenceExceptionEntity(
                    taskId = exception.taskId,
                    occurrenceDate = date,
                    cancelled = exception.cancelled,
                    patchJson = exception.patchJson,
                    createdAtEpochMillis = stored?.createdAtEpochMillis ?: now,
                    updatedAtEpochMillis = now,
                ),
            )
            if (existing != null) {
                applyExceptionToInstance(definition, exception, existing, batch.batchId, now, clearing = false)
            }
            auditOccurrence(
                exception,
                batch.batchId,
                if (exception.cancelled) "OCCURRENCE_CANCELLED" else "OCCURRENCE_EXCEPTION_APPLIED",
                now,
            )
        }
    }

    private suspend fun applyExceptionToInstance(
        definition: TaskDefinitionEntity,
        exception: DstOccurrenceException,
        existing: TaskInstanceEntity,
        batchId: String,
        now: Long,
        clearing: Boolean,
    ) {
        val date = exception.occurrenceDate
        val deadline = if (clearing) definition.deadlineForOccurrence(date) else {
            exception.deadline.valueOr(definition.deadlineForOccurrence(date))
        }
        val execution = if (clearing) definition.toExecutionSpec() else {
            exception.execution.valueOr(definition.toExecutionSpec())
        }
        val reminderMinutes = if (clearing) definition.reminderMinutesJson.toReminderList() else {
            exception.reminders.valueOr(definition.reminderMinutesJson.toReminderList())
        }
        val status = when {
            !clearing && exception.cancelled -> TaskStatus.CANCELLED
            else -> TaskStateMachine.statusAt(date, deadline, nowDateTime())
        }
        val updated = existing.copy(
            name = if (clearing) definition.name else exception.name.valueOr(definition.name),
            description = if (clearing) definition.description else exception.description.valueOr(definition.description),
            deadline = deadline?.toString(),
            required = if (clearing) definition.required else exception.required.valueOr(definition.required),
            points = if (clearing) definition.points else exception.points.valueOr(definition.points),
            sortOrder = if (clearing) definition.sortOrder else exception.sortOrder.valueOr(definition.sortOrder),
            completionMessage = if (clearing) definition.completionMessage else
                exception.completionMessage.valueOr(definition.completionMessage) ?: "任务已完成",
            status = status.name,
            executionKind = execution.kindName(),
            executionAction = execution.actionValue(),
            executionTarget = execution.targetValue(),
            reminderMinutesJson = reminderMinutes.toStorageJson(),
            publishedAtEpochMillis = now,
            updatedAtEpochMillis = now,
            singleDayAdjusted = !clearing,
        )
        instanceDao.upsertInstances(listOf(updated))

        val resetSteps = clearing || exception.steps is Field.Value
        if (resetSteps) {
            val steps = if (clearing) {
                definitionDao.getStepDefinitions(definition.taskId).map {
                    com.ds.localtaskmanager.protocol.DstStep(it.name, it.required)
                }
            } else {
                exception.steps.valueOr(emptyList())
            }
            instanceDao.deleteInstanceSteps(existing.taskId, existing.occurrenceKey)
            instanceDao.insertInstanceSteps(steps.mapIndexed { index, step ->
                InstanceStepEntity(existing.taskId, existing.occurrenceKey, index, step.name, step.required, false, now)
            })
            auditOccurrence(exception, batchId, "OCCURRENCE_STEPS_RESET", now)
        }
        if (clearing || exception.cancelled || exception.execution is Field.Value) {
            executionDao.deleteProgress(existing.taskId, existing.occurrenceKey)
            auditOccurrence(exception, batchId, "OCCURRENCE_EXECUTION_RESET", now)
        }
    }

    private suspend fun auditOccurrence(
        exception: DstOccurrenceException,
        batchId: String,
        action: String,
        now: Long,
    ) {
        val date = exception.occurrenceDate.toString()
        val occurrenceKey = date.takeIf { instanceDao.getInstance(exception.taskId, it) != null }
        auditDao.insertLogs(
            listOf(
                ActionLogEntity(
                    eventId = idGenerator.next(),
                    taskId = exception.taskId,
                    occurrenceKey = occurrenceKey,
                    batchId = batchId,
                    action = action,
                    detail = if (occurrenceKey == null) "{\"date\":\"$date\"}" else null,
                    createdAtEpochMillis = now,
                ),
            ),
        )
    }

    private fun DstOccurrenceException.reopensMissed(): Boolean =
        (deadline as? Field.Value)?.value?.isAfter(nowDateTime()) == true

    private fun TaskDefinitionEntity.deadlineForOccurrence(date: LocalDate): LocalDateTime? =
        recurrenceDeadlineTime?.let { timeText ->
            val time = LocalTime.parse(timeText)
            (if (!time.isAfter(LocalTime.of(4, 0))) date.plusDays(1) else date).atTime(time)
        }

    private fun TaskDefinitionEntity.toExecutionSpec(): ExecutionSpec = when (executionKind) {
        "COUNTER" -> ExecutionSpec.Counter(
            action = if (executionAction == 1) com.ds.localtaskmanager.domain.execution.CounterAction.SLIDER
            else com.ds.localtaskmanager.domain.execution.CounterAction.CLICK,
            target = checkNotNull(executionTarget),
        )
        "TIMER" -> ExecutionSpec.Timer(checkNotNull(executionTarget))
        "INFORMATION" -> ExecutionSpec.Information
        else -> ExecutionSpec.Normal
    }

    private fun String?.toReminderList(): List<Int> = this
        ?.removePrefix("[")?.removeSuffix("]")
        ?.takeIf(String::isNotBlank)
        ?.split(',')?.map(String::toInt)
        .orEmpty()

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

    private fun DstTask.toDefinition(
        old: TaskDefinitionEntity?,
        now: Long,
        updatePlan: InstanceUpdatePlan,
    ): TaskDefinitionEntity {
        val storedRecurrence = recurrence.toStoredRecurrence(old, TaskDay.from(nowDateTime()))
        return TaskDefinitionEntity(
            taskId = taskId,
            name = name,
            description = description,
            groupId = groupId,
            required = required,
            taskDate = updatePlan.taskDate.toString(),
            deadline = updatePlan.deadline?.toString(),
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
            reminderMinutesJson = reminderMinutes.toStorageJson(),
        )
    }

    private fun DstTask.toInstance(
        old: TaskInstanceEntity?,
        now: Long,
        updatePlan: InstanceUpdatePlan,
        groupNameSnapshot: String?,
    ): TaskInstanceEntity {
        return TaskInstanceEntity(
            taskId = taskId,
            occurrenceKey = "once",
            name = name,
            description = description,
            taskDate = updatePlan.taskDate.toString(),
            deadline = updatePlan.deadline?.toString(),
            groupId = groupId,
            required = required,
            points = points,
            sortOrder = sortOrder,
            completionMessage = completionMessage,
            status = updatePlan.status.name,
            completedAtEpochMillis = null,
            createdAtEpochMillis = old?.createdAtEpochMillis ?: now,
            updatedAtEpochMillis = now,
            executionKind = execution.kindName(),
            executionAction = execution.actionValue(),
            executionTarget = execution.targetValue(),
            reminderMinutesJson = reminderMinutes.toStorageJson(),
            publishedAtEpochMillis = if (updatePlan.reopened || old == null) now else old.publishedAtEpochMillis,
            groupNameSnapshot = groupNameSnapshot,
        )
    }

    private fun DstTask.toUpdatePlan(
        oldDefinition: TaskDefinitionEntity?,
        oldInstance: TaskInstanceEntity?,
    ): InstanceUpdatePlan {
        val restored = oldDefinition?.cancelled == true || oldInstance?.status == TaskStatus.CANCELLED.name
        return InstanceUpdatePlanner.plan(
            InstanceUpdateRequest(
                oldDate = (oldInstance?.taskDate ?: oldDefinition?.taskDate)?.let(LocalDate::parse),
                oldDeadline = (oldInstance?.deadline ?: oldDefinition?.deadline)?.let(LocalDateTime::parse),
                oldStatus = oldInstance?.status?.let(TaskStatus::valueOf),
                inferredDate = taskDate,
                incomingDeadline = deadline,
                explicitDate = (taskDateDirective as? Field.Value)?.value,
                deadlineWasExplicit = deadlineDirective is Field.Value,
                restored = restored,
                now = nowDateTime(),
            ),
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

    private fun String?.jsonValue(): String = this?.let { "\"$it\"" } ?: "null"

    private fun List<Int>.toStorageJson(): String? =
        takeIf { it.isNotEmpty() }?.joinToString(prefix = "[", postfix = "]", separator = ",")

    private fun ImportPreview.resultRevisionReason(): ResultRevisionReason {
        val reasons = buildSet {
            taskChanges.forEach { change ->
                when {
                    ImportChangeType.DATE_MOVED in change.types -> add(ResultRevisionReason.TASK_DATE_MOVED)
                    ImportChangeType.REOPENED in change.types -> add(ResultRevisionReason.TASK_REOPENED)
                    ImportChangeType.DEADLINE_EXTENDED in change.types -> add(ResultRevisionReason.TASK_DELAYED)
                }
            }
        }
        return when (reasons.size) {
            0 -> ResultRevisionReason.TASK_IMPORTED
            1 -> reasons.single()
            else -> ResultRevisionReason.IMPORT_MIXED
        }
    }

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
