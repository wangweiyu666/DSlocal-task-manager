package com.ds.localtaskmanager.data

import com.ds.localtaskmanager.data.dao.AuditDao
import com.ds.localtaskmanager.data.dao.InstanceDao
import kotlinx.coroutines.flow.Flow

interface TaskRepository {
    fun observeTasks(taskDate: String): Flow<List<TaskInstanceEntity>>

    suspend fun queryHistory(
        groupId: String? = null,
        status: String? = null,
    ): List<TaskInstanceEntity>

    suspend fun logs(taskId: String): List<ActionLogEntity>
    suspend fun ledger(taskId: String): List<PointsLedgerEntity>
}

class RoomTaskRepository(
    private val instanceDao: InstanceDao,
    private val auditDao: AuditDao,
) : TaskRepository {
    override fun observeTasks(taskDate: String): Flow<List<TaskInstanceEntity>> =
        instanceDao.observeForDate(taskDate)

    override suspend fun queryHistory(
        groupId: String?,
        status: String?,
    ): List<TaskInstanceEntity> = instanceDao.queryHistory(groupId, status)

    override suspend fun logs(taskId: String): List<ActionLogEntity> = auditDao.getLogs(taskId)

    override suspend fun ledger(taskId: String): List<PointsLedgerEntity> =
        auditDao.getLedger(taskId)
}
