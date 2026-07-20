package com.ds.localtaskmanager.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.ds.localtaskmanager.data.ActionLogEntity
import com.ds.localtaskmanager.data.PointsLedgerEntity

data class LedgerGroupBalance(
    val occurrenceKey: String,
    val groupId: String?,
    val balance: Int,
)

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

    @Query(
        """
        SELECT occurrenceKey, groupId, CAST(SUM(delta) AS INTEGER) AS balance
        FROM points_ledger
        WHERE taskId = :taskId
        GROUP BY occurrenceKey, groupId
        HAVING SUM(delta) != 0
        """,
    )
    suspend fun getGroupBalances(taskId: String): List<LedgerGroupBalance>

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
