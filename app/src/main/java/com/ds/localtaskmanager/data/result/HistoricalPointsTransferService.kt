package com.ds.localtaskmanager.data.result

import com.ds.localtaskmanager.data.AppDatabase
import com.ds.localtaskmanager.data.PointsLedgerEntity
import com.ds.localtaskmanager.domain.RecordIdGenerator
import java.time.Clock

internal class HistoricalPointsTransferService(
    private val database: AppDatabase,
    private val clock: Clock,
    private val idGenerator: RecordIdGenerator,
) {
    suspend fun moveTaskToGroup(taskId: String, newGroupId: String?) {
        val balances = database.auditDao().getGroupBalances(taskId)
        balances.filter { it.groupId != newGroupId && it.balance != 0 }.forEach { balance ->
            val now = clock.millis()
            database.auditDao().insertLedger(
                PointsLedgerEntity(
                    ledgerId = idGenerator.next(),
                    taskId = taskId,
                    occurrenceKey = balance.occurrenceKey,
                    groupId = balance.groupId,
                    delta = -balance.balance,
                    reason = "GROUP_TRANSFER_OUT",
                    createdAtEpochMillis = now,
                ),
            )
            database.auditDao().insertLedger(
                PointsLedgerEntity(
                    ledgerId = idGenerator.next(),
                    taskId = taskId,
                    occurrenceKey = balance.occurrenceKey,
                    groupId = newGroupId,
                    delta = balance.balance,
                    reason = "GROUP_TRANSFER_IN",
                    createdAtEpochMillis = now,
                ),
            )
        }
    }
}
