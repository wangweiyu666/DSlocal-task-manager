@file:Suppress("unused")

package com.ds.localtaskmanager.data.schema

import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.RoomDatabase

/** Compiler-only declarations that preserve the exact committed v1/v2 Room schemas. */
@Database(entities = [V1TaskInstance::class], version = 1, exportSchema = true)
abstract class AppDatabaseV1Schema : RoomDatabase()

@Entity(tableName = "task_instance", primaryKeys = ["taskId", "occurrenceKey"])
data class V1TaskInstance(
    val taskId: String,
    val occurrenceKey: String,
    val name: String,
    val taskDate: String,
    val groupId: String?,
    val required: Boolean,
    val points: Int,
    val status: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Database(
    entities = [
        V2AppProfile::class,
        V2ImportBatch::class,
        V2TaskGroup::class,
        V2TaskDefinition::class,
        V2TaskStepDefinition::class,
        V2TaskInstance::class,
        V2InstanceStep::class,
        V2PointsLedger::class,
        V2ActionLog::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabaseV2Schema : RoomDatabase()

@Entity(tableName = "app_profile")
data class V2AppProfile(
    @androidx.room.PrimaryKey val id: Int,
    val domName: String,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "import_batch")
data class V2ImportBatch(
    @androidx.room.PrimaryKey val batchId: String,
    val note: String?,
    val importedAtEpochMillis: Long,
)

@Entity(tableName = "task_group")
data class V2TaskGroup(
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
data class V2TaskDefinition(
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
data class V2TaskStepDefinition(
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
data class V2TaskInstance(
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
data class V2InstanceStep(
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
data class V2PointsLedger(
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
data class V2ActionLog(
    @androidx.room.PrimaryKey val eventId: String,
    val taskId: String?,
    val occurrenceKey: String?,
    val batchId: String?,
    val action: String,
    val detail: String?,
    val createdAtEpochMillis: Long,
)
