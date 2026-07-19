package com.ds.localtaskmanager.data

import com.ds.localtaskmanager.data.dao.AuditDao
import com.ds.localtaskmanager.data.dao.InstanceDao
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import kotlinx.coroutines.flow.Flow

interface TaskRepository {
    fun observeTasks(taskDate: String): Flow<List<TaskInstanceEntity>>

    suspend fun queryHistory(
        groupId: String? = null,
        status: String? = null,
    ): List<TaskInstanceEntity>

    suspend fun logs(key: TaskInstanceKey): List<ActionLogEntity>
    suspend fun ledger(key: TaskInstanceKey): List<PointsLedgerEntity>

    suspend fun logs(taskId: String): List<ActionLogEntity> = logs(TaskInstanceKey(taskId))
    suspend fun ledger(taskId: String): List<PointsLedgerEntity> = ledger(TaskInstanceKey(taskId))
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

    override suspend fun logs(key: TaskInstanceKey): List<ActionLogEntity> =
        auditDao.getLogs(key.taskId, key.occurrenceKey)

    override suspend fun ledger(key: TaskInstanceKey): List<PointsLedgerEntity> =
        auditDao.getLedger(key.taskId, key.occurrenceKey)
}
