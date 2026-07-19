package com.ds.localtaskmanager.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.ds.localtaskmanager.data.ExecutionProgressEntity
import com.ds.localtaskmanager.data.InformationSubmissionEntity
import com.ds.localtaskmanager.data.TaskNoteEntity

@Dao
interface ExecutionDao {
    @Query("SELECT * FROM execution_progress WHERE taskId = :taskId AND occurrenceKey = :occurrenceKey")
    suspend fun getProgress(taskId: String, occurrenceKey: String): ExecutionProgressEntity?

    @Upsert
    suspend fun upsertProgress(progress: ExecutionProgressEntity)

    @Query("DELETE FROM execution_progress WHERE taskId = :taskId AND occurrenceKey = :occurrenceKey")
    suspend fun deleteProgress(taskId: String, occurrenceKey: String)

    @Query("SELECT * FROM information_submission WHERE taskId = :taskId AND occurrenceKey = :occurrenceKey")
    suspend fun getSubmission(taskId: String, occurrenceKey: String): InformationSubmissionEntity?

    @Upsert
    suspend fun upsertSubmission(submission: InformationSubmissionEntity)

    @Query("DELETE FROM information_submission WHERE taskId = :taskId AND occurrenceKey = :occurrenceKey")
    suspend fun deleteSubmission(taskId: String, occurrenceKey: String)

    @Query("SELECT * FROM task_note WHERE taskId = :taskId AND occurrenceKey = :occurrenceKey")
    suspend fun getNote(taskId: String, occurrenceKey: String): TaskNoteEntity?

    @Upsert
    suspend fun upsertNote(note: TaskNoteEntity)

    @Delete
    suspend fun deleteNote(note: TaskNoteEntity)
}
