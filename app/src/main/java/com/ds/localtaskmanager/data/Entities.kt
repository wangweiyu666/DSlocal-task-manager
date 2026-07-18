package com.ds.localtaskmanager.data

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "app_profile")
data class AppProfileEntity(
    @androidx.room.PrimaryKey val id: Int = 1,
    val domName: String = "",
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "import_batch")
data class ImportBatchEntity(
    @androidx.room.PrimaryKey val batchId: String,
    val note: String?,
    val importedAtEpochMillis: Long,
)

@Entity(tableName = "task_group")
data class TaskGroupEntity(
    @androidx.room.PrimaryKey val groupId: String,
    val name: String,
    val completeMessage: String,
    val incompleteMessage: String,
    val archived: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "task_definition",
    indices = [Index("groupId"), Index("taskDate")],
)
data class TaskDefinitionEntity(
    @androidx.room.PrimaryKey val taskId: String,
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
)

@Entity(
    tableName = "task_step_definition",
    primaryKeys = ["taskId", "position"],
    indices = [Index("taskId")],
)
data class TaskStepDefinitionEntity(
    val taskId: String,
    val position: Int,
    val name: String,
    val required: Boolean,
)

@Entity(
    tableName = "task_instance",
    primaryKeys = ["taskId", "occurrenceKey"],
    indices = [Index("taskDate"), Index("status"), Index("groupId")],
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
)

@Entity(
    tableName = "instance_step",
    primaryKeys = ["taskId", "occurrenceKey", "position"],
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
    tableName = "points_ledger",
    indices = [Index(value = ["taskId", "occurrenceKey"]), Index("groupId")],
)
data class PointsLedgerEntity(
    @androidx.room.PrimaryKey val ledgerId: String,
    val taskId: String,
    val occurrenceKey: String,
    val groupId: String?,
    val delta: Int,
    val reason: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "action_log",
    indices = [Index(value = ["taskId", "occurrenceKey"]), Index("batchId")],
)
data class ActionLogEntity(
    @androidx.room.PrimaryKey val eventId: String,
    val taskId: String?,
    val occurrenceKey: String?,
    val batchId: String?,
    val action: String,
    val detail: String?,
    val createdAtEpochMillis: Long,
)
