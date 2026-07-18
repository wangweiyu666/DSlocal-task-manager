package com.ds.localtaskmanager.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.ds.localtaskmanager.data.ActionLogEntity
import com.ds.localtaskmanager.data.PointsLedgerEntity

@Dao
interface AuditDao {
    @Insert
    suspend fun insertLedger(entry: PointsLedgerEntity)

    @Query(
        """
        SELECT * FROM points_ledger
        WHERE taskId = :taskId AND occurrenceKey = :occurrenceKey
        ORDER BY createdAtEpochMillis, ledgerId
        """,
    )
    suspend fun getLedger(
        taskId: String,
        occurrenceKey: String = "once",
    ): List<PointsLedgerEntity>

    @Insert
    suspend fun insertLogs(logs: List<ActionLogEntity>)

    @Query(
        """
        SELECT * FROM action_log
        WHERE taskId = :taskId AND occurrenceKey = :occurrenceKey
        ORDER BY createdAtEpochMillis, eventId
        """,
    )
    suspend fun getLogs(
        taskId: String,
        occurrenceKey: String = "once",
    ): List<ActionLogEntity>
}
