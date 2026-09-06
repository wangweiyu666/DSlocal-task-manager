package com.ds.localtaskmanager.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "mood_submission",
    primaryKeys = ["taskId", "occurrenceKey"],
    foreignKeys = [ForeignKey(
        entity = TaskInstanceEntity::class,
        parentColumns = ["taskId", "occurrenceKey"],
        childColumns = ["taskId", "occurrenceKey"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["taskId", "occurrenceKey"])],
)
data class MoodSubmissionEntity(
    val taskId: String,
    val occurrenceKey: String,
    val rating: Int?,
    val text: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val submittedAtEpochMillis: Long?,
)
