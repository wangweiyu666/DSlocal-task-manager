package com.ds.localtaskmanager.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.ds.localtaskmanager.data.ReminderRecordEntity

@Dao
interface ReminderDao {
    @Upsert
    suspend fun upsertRecords(records: List<ReminderRecordEntity>)

    @Query("SELECT * FROM reminder_record WHERE taskId = :taskId AND occurrenceKey = :occurrenceKey ORDER BY minutesBeforeDeadline DESC")
    suspend fun recordsForInstance(taskId: String, occurrenceKey: String): List<ReminderRecordEntity>

    @Query("SELECT * FROM reminder_record WHERE state = 'SCHEDULED' ORDER BY scheduledForEpochMillis")
    suspend fun scheduledRecords(): List<ReminderRecordEntity>
}
