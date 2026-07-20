package com.ds.localtaskmanager.data

import com.ds.localtaskmanager.data.dao.AuditDao
import com.ds.localtaskmanager.data.dao.DefinitionDao
import com.ds.localtaskmanager.data.dao.InstanceDao
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest

data class TodayTask(
    val instance: TaskInstanceEntity,
    val groupName: String?,
    val groupCreatedAtEpochMillis: Long?,
)

interface TaskRepository {
    fun observeTasks(taskDate: String): Flow<List<TaskInstanceEntity>>
    fun observeTodayTasks(taskDate: String): Flow<List<TodayTask>>

    suspend fun getTask(key: TaskInstanceKey): TaskInstanceEntity?
    suspend fun getSteps(key: TaskInstanceKey): List<InstanceStepEntity>

    suspend fun queryHistory(
        groupId: String? = null,
        status: String? = null,
    ): List<TaskInstanceEntity>

    suspend fun logs(key: TaskInstanceKey): List<ActionLogEntity>
    suspend fun ledger(key: TaskInstanceKey): List<PointsLedgerEntity>

    suspend fun logs(taskId: String): List<ActionLogEntity> = logs(TaskInstanceKey(taskId))
    suspend fun ledger(taskId: String): List<PointsLedgerEntity> = ledger(TaskInstanceKey(taskId))
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoomTaskRepository(
    private val instanceDao: InstanceDao,
    private val definitionDao: DefinitionDao,
    private val auditDao: AuditDao,
) : TaskRepository {
    override fun observeTasks(taskDate: String): Flow<List<TaskInstanceEntity>> =
        instanceDao.observeForDate(taskDate)

    override fun observeTodayTasks(taskDate: String): Flow<List<TodayTask>> =
        instanceDao.observeForDate(taskDate).mapLatest { instances ->
            val groups = instances.mapNotNull { it.groupId }.distinct().let { ids ->
                if (ids.isEmpty()) emptyMap() else definitionDao.getGroups(ids).associateBy { it.groupId }
            }
            instances.map { instance ->
                val group = instance.groupId?.let(groups::get)
                TodayTask(instance, group?.name, group?.createdAtEpochMillis)
            }
        }

    override suspend fun getTask(key: TaskInstanceKey): TaskInstanceEntity? =
        instanceDao.getInstance(key.taskId, key.occurrenceKey)

    override suspend fun getSteps(key: TaskInstanceKey): List<InstanceStepEntity> =
        instanceDao.getInstanceSteps(key.taskId, key.occurrenceKey)

    override suspend fun queryHistory(
        groupId: String?,
        status: String?,
    ): List<TaskInstanceEntity> = instanceDao.queryHistory(groupId, status)

    override suspend fun logs(key: TaskInstanceKey): List<ActionLogEntity> =
        auditDao.getLogs(key.taskId, key.occurrenceKey)

    override suspend fun ledger(key: TaskInstanceKey): List<PointsLedgerEntity> =
        auditDao.getLedger(key.taskId, key.occurrenceKey)
}
