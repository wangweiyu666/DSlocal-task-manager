package com.ds.localtaskmanager.data

import kotlinx.coroutines.flow.Flow

class TaskRepository(private val dao: AppDao) {
    fun observeTasks(taskDate: String): Flow<List<TaskInstanceEntity>> =
        dao.observeForDate(taskDate)

    suspend fun queryHistory(
        groupId: String? = null,
        status: String? = null,
    ): List<TaskInstanceEntity> = dao.queryHistory(groupId, status)

    suspend fun logs(taskId: String): List<ActionLogEntity> = dao.getLogs(taskId)

    suspend fun ledger(taskId: String): List<PointsLedgerEntity> = dao.getLedger(taskId)
}
