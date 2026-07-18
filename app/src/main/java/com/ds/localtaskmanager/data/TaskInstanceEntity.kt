package com.ds.localtaskmanager.data

import androidx.room.Entity

@Entity(
    tableName = "task_instance",
    primaryKeys = ["taskId", "occurrenceKey"],
)
data class TaskInstanceEntity(
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
