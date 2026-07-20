package com.ds.localtaskmanager.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.ds.localtaskmanager.data.ResultRevisionEntity

data class ResultTaskRow(
    val taskId: String,
    val occurrenceKey: String,
    val taskDate: String,
    val groupId: String?,
    val required: Boolean,
    val status: String,
    val actualPoints: Int,
    val groupCompleteMessage: String?,
    val groupIncompleteMessage: String?,
)

@Dao
interface ResultDao {
    @Insert
    suspend fun insertRevision(revision: ResultRevisionEntity)

    @Query("SELECT * FROM result_revision WHERE taskDate = :taskDate ORDER BY createdAtEpochMillis, revisionId")
    suspend fun revisionsForDate(taskDate: String): List<ResultRevisionEntity>

    @Query(
        """
        SELECT i.taskId, i.occurrenceKey, i.taskDate,
          d.groupId AS groupId, d.required AS required, i.status,
          CAST(COALESCE(SUM(l.delta), 0) AS INTEGER) AS actualPoints,
          g.completeMessage AS groupCompleteMessage,
          g.incompleteMessage AS groupIncompleteMessage
        FROM task_instance i
        INNER JOIN task_definition d ON d.taskId = i.taskId
        LEFT JOIN points_ledger l
          ON l.taskId = i.taskId AND l.occurrenceKey = i.occurrenceKey
        LEFT JOIN task_group g ON g.groupId = d.groupId
        WHERE i.taskDate = :taskDate AND d.cancelled = 0
        GROUP BY i.taskId, i.occurrenceKey
        ORDER BY i.taskId, i.occurrenceKey
        """,
    )
    suspend fun resultRowsForDate(taskDate: String): List<ResultTaskRow>

    @Query(
        """
        SELECT i.taskId, i.occurrenceKey, i.taskDate,
          d.groupId AS groupId, d.required AS required, i.status,
          CAST(COALESCE(SUM(l.delta), 0) AS INTEGER) AS actualPoints,
          g.completeMessage AS groupCompleteMessage,
          g.incompleteMessage AS groupIncompleteMessage
        FROM task_instance i
        INNER JOIN task_definition d ON d.taskId = i.taskId
        LEFT JOIN points_ledger l
          ON l.taskId = i.taskId AND l.occurrenceKey = i.occurrenceKey
        LEFT JOIN task_group g ON g.groupId = d.groupId
        WHERE i.taskDate BETWEEN :fromDate AND :throughDate AND d.cancelled = 0
        GROUP BY i.taskId, i.occurrenceKey
        ORDER BY i.taskDate, i.taskId, i.occurrenceKey
        """,
    )
    suspend fun resultRowsInRange(fromDate: String, throughDate: String): List<ResultTaskRow>
}
