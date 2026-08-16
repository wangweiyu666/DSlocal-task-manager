package com.ds.localtaskmanager.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.ds.localtaskmanager.data.RecurrenceExceptionEntity

@Dao
interface RecurrenceExceptionDao {
    @Query("SELECT * FROM recurrence_exception WHERE taskId = :taskId AND occurrenceDate = :date")
    suspend fun get(taskId: String, date: String): RecurrenceExceptionEntity?

    @Query("SELECT * FROM recurrence_exception WHERE taskId IN (:taskIds) ORDER BY taskId, occurrenceDate")
    suspend fun forTasks(taskIds: List<String>): List<RecurrenceExceptionEntity>

    @Query("SELECT * FROM recurrence_exception WHERE taskId = :taskId AND occurrenceDate BETWEEN :fromDate AND :throughDate")
    suspend fun inRange(taskId: String, fromDate: String, throughDate: String): List<RecurrenceExceptionEntity>

    @Upsert
    suspend fun upsert(value: RecurrenceExceptionEntity)

    @Query("DELETE FROM recurrence_exception WHERE taskId = :taskId AND occurrenceDate = :date")
    suspend fun delete(taskId: String, date: String)

    @Query("DELETE FROM recurrence_exception WHERE taskId = :taskId AND occurrenceDate >= :fromDate")
    suspend fun deleteFuture(taskId: String, fromDate: String)

    @Query("SELECT * FROM recurrence_exception WHERE taskId = :taskId ORDER BY occurrenceDate")
    suspend fun forTask(taskId: String): List<RecurrenceExceptionEntity>

    @Query("DELETE FROM recurrence_exception")
    suspend fun clear()
}
