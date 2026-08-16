package com.ds.localtaskmanager.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "app_profile")
data class AppProfileEntity(
    @PrimaryKey val id: Int = 1,
    val domName: String = "",
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "import_batch")
data class ImportBatchEntity(
    @PrimaryKey val batchId: String,
    val note: String?,
    val importedAtEpochMillis: Long,
)

@Entity(tableName = "task_group")
data class TaskGroupEntity(
    @PrimaryKey val groupId: String,
    val name: String,
    val completeMessage: String,
    val incompleteMessage: String,
    val archived: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "task_definition",
    foreignKeys = [
        ForeignKey(
            entity = TaskGroupEntity::class,
            parentColumns = ["groupId"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("groupId"), Index("taskDate")],
)
data class TaskDefinitionEntity(
    @PrimaryKey val taskId: String,
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
)

@Entity(
    tableName = "task_step_definition",
    primaryKeys = ["taskId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = TaskDefinitionEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId")],
)
data class TaskStepDefinitionEntity(
    val taskId: String,
    val position: Int,
    val name: String,
    val required: Boolean,
)

@Entity(
    tableName = "recurrence_exception",
    primaryKeys = ["taskId", "occurrenceDate"],
    foreignKeys = [
        ForeignKey(
            entity = TaskDefinitionEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId"), Index("occurrenceDate")],
)
data class RecurrenceExceptionEntity(
    val taskId: String,
    val occurrenceDate: String,
    val cancelled: Boolean,
    val patchJson: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "task_instance",
    primaryKeys = ["taskId", "occurrenceKey"],
    foreignKeys = [
        ForeignKey(
            entity = TaskDefinitionEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
        ),
        ForeignKey(
            entity = TaskGroupEntity::class,
            parentColumns = ["groupId"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("taskId"),
        Index("taskDate"),
        Index("status"),
        Index("groupId"),
        Index(value = ["taskDate", "required", "status", "category"]),
        Index(value = ["taskDate", "groupId", "status"]),
    ],
)
data class TaskInstanceEntity(
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
    val category: String = "TEMPORARY",
    val executionKind: String = "NORMAL",
    val executionAction: Int? = null,
    val executionTarget: Int? = null,
    val reminderMinutesJson: String? = null,
    val publishedAtEpochMillis: Long = createdAtEpochMillis,
    val groupNameSnapshot: String? = null,
    @ColumnInfo(defaultValue = "0") val singleDayAdjusted: Boolean = false,
)

@Entity(
    tableName = "instance_step",
    primaryKeys = ["taskId", "occurrenceKey", "position"],
    foreignKeys = [
        ForeignKey(
            entity = TaskInstanceEntity::class,
            parentColumns = ["taskId", "occurrenceKey"],
            childColumns = ["taskId", "occurrenceKey"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["taskId", "occurrenceKey"])],
)
data class InstanceStepEntity(
    val taskId: String,
    val occurrenceKey: String,
    val position: Int,
    val name: String,
    val required: Boolean,
    val completed: Boolean,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "execution_progress",
    primaryKeys = ["taskId", "occurrenceKey"],
    foreignKeys = [
        ForeignKey(
            entity = TaskInstanceEntity::class,
            parentColumns = ["taskId", "occurrenceKey"],
            childColumns = ["taskId", "occurrenceKey"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["taskId", "occurrenceKey"])],
)
data class ExecutionProgressEntity(
    val taskId: String,
    val occurrenceKey: String,
    val executionKind: String,
    val counterValue: Int?,
    val elapsedMillis: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "information_submission",
    primaryKeys = ["taskId", "occurrenceKey"],
    foreignKeys = [
        ForeignKey(
            entity = TaskInstanceEntity::class,
            parentColumns = ["taskId", "occurrenceKey"],
            childColumns = ["taskId", "occurrenceKey"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["taskId", "occurrenceKey"])],
)
data class InformationSubmissionEntity(
    val taskId: String,
    val occurrenceKey: String,
    val content: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val submittedAtEpochMillis: Long?,
)

@Entity(
    tableName = "task_note",
    primaryKeys = ["taskId", "occurrenceKey"],
    foreignKeys = [
        ForeignKey(
            entity = TaskInstanceEntity::class,
            parentColumns = ["taskId", "occurrenceKey"],
            childColumns = ["taskId", "occurrenceKey"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["taskId", "occurrenceKey"])],
)
data class TaskNoteEntity(
    val taskId: String,
    val occurrenceKey: String,
    val content: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "points_ledger",
    foreignKeys = [
        ForeignKey(
            entity = TaskInstanceEntity::class,
            parentColumns = ["taskId", "occurrenceKey"],
            childColumns = ["taskId", "occurrenceKey"],
        ),
        ForeignKey(
            entity = TaskGroupEntity::class,
            parentColumns = ["groupId"],
            childColumns = ["groupId"],
        ),
    ],
    indices = [
        Index(value = ["taskId", "occurrenceKey"]),
        Index("groupId"),
        Index("createdAtEpochMillis"),
        Index(value = ["reason", "createdAtEpochMillis"]),
    ],
)
data class PointsLedgerEntity(
    @PrimaryKey val ledgerId: String,
    val taskId: String,
    val occurrenceKey: String,
    val groupId: String?,
    val delta: Int,
    val reason: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "action_log",
    foreignKeys = [
        ForeignKey(
            entity = TaskInstanceEntity::class,
            parentColumns = ["taskId", "occurrenceKey"],
            childColumns = ["taskId", "occurrenceKey"],
        ),
        ForeignKey(
            entity = ImportBatchEntity::class,
            parentColumns = ["batchId"],
            childColumns = ["batchId"],
        ),
    ],
    indices = [Index(value = ["taskId", "occurrenceKey"]), Index("batchId")],
)
data class ActionLogEntity(
    @PrimaryKey val eventId: String,
    val taskId: String?,
    val occurrenceKey: String?,
    val batchId: String?,
    val action: String,
    val detail: String?,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "result_revision",
    foreignKeys = [
        ForeignKey(
            entity = TaskGroupEntity::class,
            parentColumns = ["groupId"],
            childColumns = ["groupId"],
        ),
        ForeignKey(
            entity = ImportBatchEntity::class,
            parentColumns = ["batchId"],
            childColumns = ["batchId"],
        ),
    ],
    indices = [Index(value = ["taskDate", "scope"]), Index("groupId"), Index("batchId")],
)
data class ResultRevisionEntity(
    @PrimaryKey val revisionId: String,
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

@Entity(
    tableName = "reminder_record",
    primaryKeys = ["taskId", "occurrenceKey", "minutesBeforeDeadline"],
    foreignKeys = [
        ForeignKey(
            entity = TaskInstanceEntity::class,
            parentColumns = ["taskId", "occurrenceKey"],
            childColumns = ["taskId", "occurrenceKey"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["taskId", "occurrenceKey"]),
        Index("scheduledForEpochMillis"),
        Index("state"),
    ],
)
data class ReminderRecordEntity(
    val taskId: String,
    val occurrenceKey: String,
    val minutesBeforeDeadline: Int,
    val scheduledForEpochMillis: Long,
    val state: String,
    val deliveredAtEpochMillis: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
