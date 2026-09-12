package com.ds.localtaskmanager.backup

import com.ds.localtaskmanager.data.ActionLogEntity
import com.ds.localtaskmanager.data.AppProfileEntity
import com.ds.localtaskmanager.data.ExecutionProgressEntity
import com.ds.localtaskmanager.data.ImportBatchEntity
import com.ds.localtaskmanager.data.InformationSubmissionEntity
import com.ds.localtaskmanager.data.MoodSubmissionEntity
import com.ds.localtaskmanager.data.InstanceStepEntity
import com.ds.localtaskmanager.data.PointsLedgerEntity
import com.ds.localtaskmanager.data.RecurrenceExceptionEntity
import com.ds.localtaskmanager.data.ResultRevisionEntity
import com.ds.localtaskmanager.data.TaskDefinitionEntity
import com.ds.localtaskmanager.data.TaskGroupEntity
import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.data.TaskNoteEntity
import com.ds.localtaskmanager.data.TaskStepDefinitionEntity
import kotlinx.serialization.Serializable

@Serializable
data class BackupPayload(
    val schemaVersion: Int = 5,
    val settings: PortableSettings = PortableSettings(),
    val profiles: List<ProfileBackup> = emptyList(),
    val importBatches: List<ImportBatchBackup> = emptyList(),
    val groups: List<GroupBackup> = emptyList(),
    val definitions: List<DefinitionBackup> = emptyList(),
    val definitionSteps: List<DefinitionStepBackup> = emptyList(),
    val recurrenceExceptions: List<RecurrenceExceptionBackup> = emptyList(),
    val instances: List<InstanceBackup> = emptyList(),
    val instanceSteps: List<InstanceStepBackup> = emptyList(),
    val progress: List<ProgressBackup> = emptyList(),
    val information: List<InformationBackup> = emptyList(),
    val moods: List<MoodBackup> = emptyList(),
    val notes: List<NoteBackup> = emptyList(),
    val ledger: List<LedgerBackup> = emptyList(),
    val actionLogs: List<ActionLogBackup> = emptyList(),
    val resultRevisions: List<ResultRevisionBackup> = emptyList(),
)

@Serializable
data class PortableSettings(
    val themeMode: String = "SYSTEM",
    val reduceMotion: Boolean = false,
    val lastStatisticsPeriod: String = "SEVEN_DAYS",
)

@Serializable
data class BackupMetadata(
    val createdAtEpochMillis: Long,
    val appVersion: String,
    val sourceTimeZone: String,
    val payloadSchemaVersion: Int = 5,
    val counts: BackupCounts,
)

@Serializable
data class BackupCounts(
    val groups: Int,
    val tasks: Int,
    val instances: Int,
    val ledgerEntries: Int,
    val actionLogs: Int,
    val resultRevisions: Int,
)

@Serializable data class ProfileBackup(val id: Int, val domName: String, val updatedAtEpochMillis: Long)
@Serializable data class ImportBatchBackup(val batchId: String, val note: String?, val importedAtEpochMillis: Long)
@Serializable data class GroupBackup(
    val groupId: String,
    val name: String,
    val completeMessage: String,
    val incompleteMessage: String,
    val archived: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Serializable
data class DefinitionBackup(
    val taskId: String,
    val name: String,
    val description: String,
    val groupId: String?,
    val required: Boolean,
    val taskDate: String,
    val deadline: String?,
    val points: Int,
    val sortOrder: Int?,
    val completionMessage: String,
    val stepsFingerprint: String,
    val cancelled: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val recurrenceFrequency: Int? = null,
    val recurrenceStartDate: String? = null,
    val recurrenceEndDate: String? = null,
    val recurrenceCount: Int? = null,
    val recurrenceWeekdaysMask: Int? = null,
    val recurrenceDeadlineTime: String? = null,
    val executionKind: String = "NORMAL",
    val executionAction: Int? = null,
    val executionTarget: Int? = null,
    val reminderMinutesJson: String? = null,
    val executionConfigJson: String? = null,
)

@Serializable data class DefinitionStepBackup(
    val taskId: String, val position: Int, val name: String, val required: Boolean,
    val stepId: String? = null, val executionKind: String = "NORMAL",
    val executionAction: Int? = null, val executionTarget: Int? = null,
    val executionConfigJson: String? = null, val conditionStepId: String? = null, val conditionOptionId: String? = null,
)

@Serializable data class RecurrenceExceptionBackup(
    val taskId: String,
    val occurrenceDate: String,
    val cancelled: Boolean,
    val patchJson: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Serializable
data class InstanceBackup(
    val taskId: String,
    val occurrenceKey: String,
    val name: String,
    val description: String,
    val taskDate: String,
    val deadline: String?,
    val groupId: String?,
    val required: Boolean,
    val points: Int,
    val sortOrder: Int?,
    val completionMessage: String,
    val status: String,
    val completedAtEpochMillis: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val category: String,
    val executionKind: String,
    val executionAction: Int?,
    val executionTarget: Int?,
    val reminderMinutesJson: String?,
    val publishedAtEpochMillis: Long,
    val groupNameSnapshot: String?,
    val singleDayAdjusted: Boolean = false,
    val executionConfigJson: String? = null,
    val awardedPoints: Int? = points.takeIf { status == "COMPLETED" },
)

@Serializable data class InstanceStepBackup(
    val taskId: String,
    val occurrenceKey: String,
    val position: Int,
    val name: String,
    val required: Boolean,
    val completed: Boolean,
    val updatedAtEpochMillis: Long,
    val stepId: String = "",
    val executionKind: String = "NORMAL",
    val executionAction: Int? = null,
    val executionTarget: Int? = null,
    val stepStatus: String = "PENDING",
    val counterValue: Int? = null,
    val elapsedMillis: Long? = null,
    val informationContent: String? = null,
    val moodRating: Int? = null,
    val moodText: String? = null,
    val executionConfigJson: String? = null,
    val conditionStepId: String? = null,
    val conditionOptionId: String? = null,
    val selectedOptionId: String? = null,
)

@Serializable data class ProgressBackup(
    val taskId: String,
    val occurrenceKey: String,
    val executionKind: String,
    val counterValue: Int?,
    val elapsedMillis: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val selectedOptionId: String? = null,
)

@Serializable data class MoodBackup(
    val taskId: String,
    val occurrenceKey: String,
    val rating: Int?,
    val text: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val submittedAtEpochMillis: Long?,
)

internal fun MoodSubmissionEntity.toBackup() = MoodBackup(taskId, occurrenceKey, rating, text, createdAtEpochMillis, updatedAtEpochMillis, submittedAtEpochMillis)
internal fun MoodBackup.toEntity() = MoodSubmissionEntity(taskId, occurrenceKey, rating, text, createdAtEpochMillis, updatedAtEpochMillis, submittedAtEpochMillis)

@Serializable data class InformationBackup(
    val taskId: String,
    val occurrenceKey: String,
    val content: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val submittedAtEpochMillis: Long?,
)

@Serializable data class NoteBackup(
    val taskId: String,
    val occurrenceKey: String,
    val content: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Serializable data class LedgerBackup(
    val ledgerId: String,
    val taskId: String,
    val occurrenceKey: String,
    val groupId: String?,
    val delta: Int,
    val reason: String,
    val createdAtEpochMillis: Long,
)

@Serializable data class ActionLogBackup(
    val eventId: String,
    val taskId: String?,
    val occurrenceKey: String?,
    val batchId: String?,
    val action: String,
    val detail: String?,
    val createdAtEpochMillis: Long,
)

@Serializable data class ResultRevisionBackup(
    val revisionId: String,
    val taskDate: String,
    val scope: String,
    val groupId: String?,
    val oldStatus: String?,
    val newStatus: String?,
    val oldPoints: Int?,
    val newPoints: Int?,
    val reason: String,
    val batchId: String?,
    val relatedTaskIdsJson: String,
    val createdAtEpochMillis: Long,
)

internal fun AppProfileEntity.toBackup() = ProfileBackup(id, domName, updatedAtEpochMillis)
internal fun ProfileBackup.toEntity() = AppProfileEntity(id, domName, updatedAtEpochMillis)
internal fun ImportBatchEntity.toBackup() = ImportBatchBackup(batchId, note, importedAtEpochMillis)
internal fun ImportBatchBackup.toEntity() = ImportBatchEntity(batchId, note, importedAtEpochMillis)
internal fun TaskGroupEntity.toBackup() = GroupBackup(groupId, name, completeMessage, incompleteMessage, archived, createdAtEpochMillis, updatedAtEpochMillis)
internal fun GroupBackup.toEntity() = TaskGroupEntity(groupId, name, completeMessage, incompleteMessage, archived, createdAtEpochMillis, updatedAtEpochMillis)
internal fun TaskStepDefinitionEntity.toBackup() = DefinitionStepBackup(taskId, position, name, required, stepId, executionKind, executionAction, executionTarget, executionConfigJson, conditionStepId, conditionOptionId)
internal fun DefinitionStepBackup.toEntity() = TaskStepDefinitionEntity(
    taskId, position, name, required,
    stepId ?: stableBackupStepId(taskId, position), executionKind, executionAction, executionTarget, executionConfigJson, conditionStepId, conditionOptionId,
)
internal fun RecurrenceExceptionEntity.toBackup() = RecurrenceExceptionBackup(taskId, occurrenceDate, cancelled, patchJson, createdAtEpochMillis, updatedAtEpochMillis)
internal fun RecurrenceExceptionBackup.toEntity() = RecurrenceExceptionEntity(taskId, occurrenceDate, cancelled, patchJson, createdAtEpochMillis, updatedAtEpochMillis)
internal fun InstanceStepEntity.toBackup() = InstanceStepBackup(taskId, occurrenceKey, position, name, required, completed, updatedAtEpochMillis, stepId, executionKind, executionAction, executionTarget, stepStatus, counterValue, elapsedMillis, informationContent, moodRating, moodText, executionConfigJson, conditionStepId, conditionOptionId, selectedOptionId)
internal fun InstanceStepBackup.toEntity() = InstanceStepEntity(
    taskId, occurrenceKey, position, name, required, completed, updatedAtEpochMillis,
    stepId.takeIf { it.isNotBlank() } ?: stableBackupStepId(taskId, position), executionKind,
    executionAction, executionTarget,
    if (stepId.isBlank()) (if (completed) "CONFIRMED" else "PENDING") else stepStatus,
    counterValue, elapsedMillis, informationContent, moodRating, moodText, executionConfigJson, conditionStepId, conditionOptionId, selectedOptionId,
)

private fun stableBackupStepId(taskId: String, position: Int): String =
    "s${taskId.take(12).padEnd(12, '0')}${position.toString(36).padStart(3, '0')}"
internal fun ExecutionProgressEntity.toBackup() = ProgressBackup(taskId, occurrenceKey, executionKind, counterValue, elapsedMillis, createdAtEpochMillis, updatedAtEpochMillis, selectedOptionId)
internal fun ProgressBackup.toEntity() = ExecutionProgressEntity(taskId, occurrenceKey, executionKind, counterValue, elapsedMillis, createdAtEpochMillis, updatedAtEpochMillis, selectedOptionId)
internal fun InformationSubmissionEntity.toBackup() = InformationBackup(taskId, occurrenceKey, content, createdAtEpochMillis, updatedAtEpochMillis, submittedAtEpochMillis)
internal fun InformationBackup.toEntity() = InformationSubmissionEntity(taskId, occurrenceKey, content, createdAtEpochMillis, updatedAtEpochMillis, submittedAtEpochMillis)
internal fun TaskNoteEntity.toBackup() = NoteBackup(taskId, occurrenceKey, content, createdAtEpochMillis, updatedAtEpochMillis)
internal fun NoteBackup.toEntity() = TaskNoteEntity(taskId, occurrenceKey, content, createdAtEpochMillis, updatedAtEpochMillis)
internal fun PointsLedgerEntity.toBackup() = LedgerBackup(ledgerId, taskId, occurrenceKey, groupId, delta, reason, createdAtEpochMillis)
internal fun LedgerBackup.toEntity() = PointsLedgerEntity(ledgerId, taskId, occurrenceKey, groupId, delta, reason, createdAtEpochMillis)
internal fun ActionLogEntity.toBackup() = ActionLogBackup(eventId, taskId, occurrenceKey, batchId, action, detail, createdAtEpochMillis)
internal fun ActionLogBackup.toEntity() = ActionLogEntity(eventId, taskId, occurrenceKey, batchId, action, detail, createdAtEpochMillis)
internal fun ResultRevisionEntity.toBackup() = ResultRevisionBackup(revisionId, taskDate, scope, groupId, oldStatus, newStatus, oldPoints, newPoints, reason, batchId, relatedTaskIdsJson, createdAtEpochMillis)
internal fun ResultRevisionBackup.toEntity() = ResultRevisionEntity(revisionId, taskDate, scope, groupId, oldStatus, newStatus, oldPoints, newPoints, reason, batchId, relatedTaskIdsJson, createdAtEpochMillis)

internal fun TaskDefinitionEntity.toBackup() = DefinitionBackup(
    taskId, name, description, groupId, required, taskDate, deadline, points, sortOrder,
    completionMessage, stepsFingerprint, cancelled, createdAtEpochMillis, updatedAtEpochMillis,
    recurrenceFrequency, recurrenceStartDate, recurrenceEndDate, recurrenceCount,
    recurrenceWeekdaysMask, recurrenceDeadlineTime, executionKind, executionAction,
    executionTarget, reminderMinutesJson, executionConfigJson,
)

internal fun DefinitionBackup.toEntity() = TaskDefinitionEntity(
    taskId, name, description, groupId, required, taskDate, deadline, points, sortOrder,
    completionMessage, stepsFingerprint, cancelled, createdAtEpochMillis, updatedAtEpochMillis,
    recurrenceFrequency, recurrenceStartDate, recurrenceEndDate, recurrenceCount,
    recurrenceWeekdaysMask, recurrenceDeadlineTime, executionKind, executionAction,
    executionTarget, reminderMinutesJson, executionConfigJson,
)

internal fun TaskInstanceEntity.toBackup() = InstanceBackup(
    taskId, occurrenceKey, name, description, taskDate, deadline, groupId, required, points,
    sortOrder, completionMessage, status, completedAtEpochMillis, createdAtEpochMillis,
    updatedAtEpochMillis, category, executionKind, executionAction, executionTarget,
    reminderMinutesJson, publishedAtEpochMillis, groupNameSnapshot, singleDayAdjusted, executionConfigJson, awardedPoints,
)

internal fun InstanceBackup.toEntity() = TaskInstanceEntity(
    taskId, occurrenceKey, name, description, taskDate, deadline, groupId, required, points,
    sortOrder, completionMessage, status, completedAtEpochMillis, createdAtEpochMillis,
    updatedAtEpochMillis, category, executionKind, executionAction, executionTarget,
    reminderMinutesJson, publishedAtEpochMillis, groupNameSnapshot, singleDayAdjusted, executionConfigJson, awardedPoints ?: points.takeIf { status == "COMPLETED" },
)
