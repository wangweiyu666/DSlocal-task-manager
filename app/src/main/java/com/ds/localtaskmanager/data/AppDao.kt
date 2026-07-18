package com.ds.localtaskmanager.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM app_profile WHERE id = 1")
    suspend fun getProfile(): AppProfileEntity?

    @Upsert
    suspend fun upsertProfile(profile: AppProfileEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM import_batch WHERE batchId = :batchId)")
    suspend fun hasBatch(batchId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBatch(batch: ImportBatchEntity)

    @Query("SELECT * FROM task_group WHERE groupId IN (:ids)")
    suspend fun getGroups(ids: List<String>): List<TaskGroupEntity>

    @Upsert
    suspend fun upsertGroups(groups: List<TaskGroupEntity>)

    @Query("SELECT * FROM task_definition WHERE taskId IN (:ids)")
    suspend fun getDefinitions(ids: List<String>): List<TaskDefinitionEntity>

    @Query("SELECT * FROM task_definition WHERE taskId = :taskId")
    suspend fun getDefinition(taskId: String): TaskDefinitionEntity?

    @Upsert
    suspend fun upsertDefinitions(definitions: List<TaskDefinitionEntity>)

    @Query("DELETE FROM task_step_definition WHERE taskId IN (:taskIds)")
    suspend fun deleteStepDefinitions(taskIds: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStepDefinitions(steps: List<TaskStepDefinitionEntity>)

    @Query("SELECT * FROM task_instance WHERE taskId IN (:ids) AND occurrenceKey = 'once'")
    suspend fun getOnceInstances(ids: List<String>): List<TaskInstanceEntity>

    @Query("SELECT * FROM task_instance WHERE taskId = :taskId AND occurrenceKey = :occurrenceKey")
    suspend fun getInstance(taskId: String, occurrenceKey: String = "once"): TaskInstanceEntity?

    @Upsert
    suspend fun upsertInstances(instances: List<TaskInstanceEntity>)

    @Query("DELETE FROM instance_step WHERE taskId = :taskId AND occurrenceKey = :occurrenceKey")
    suspend fun deleteInstanceSteps(taskId: String, occurrenceKey: String = "once")

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInstanceSteps(steps: List<InstanceStepEntity>)

    @Query(
        """
        SELECT * FROM instance_step
        WHERE taskId = :taskId AND occurrenceKey = :occurrenceKey
        ORDER BY position
        """,
    )
    suspend fun getInstanceSteps(taskId: String, occurrenceKey: String = "once"): List<InstanceStepEntity>

    @Query(
        """
        SELECT * FROM task_instance
        WHERE taskDate = :taskDate AND status != 'CANCELLED'
        ORDER BY required DESC,
          CASE WHEN deadline IS NULL THEN 1 ELSE 0 END,
          deadline ASC,
          CASE WHEN sortOrder IS NULL THEN 1 ELSE 0 END,
          sortOrder ASC,
          createdAtEpochMillis ASC
        """,
    )
    fun observeForDate(taskDate: String): Flow<List<TaskInstanceEntity>>

    @Query(
        """
        SELECT * FROM task_instance
        WHERE (:groupId IS NULL OR groupId = :groupId)
          AND (:status IS NULL OR status = :status)
        ORDER BY taskDate DESC, updatedAtEpochMillis DESC
        """,
    )
    suspend fun queryHistory(groupId: String?, status: String?): List<TaskInstanceEntity>

    @Query(
        """
        SELECT COUNT(*) FROM instance_step
        WHERE taskId = :taskId AND occurrenceKey = :occurrenceKey
          AND required = 1 AND completed = 0
        """,
    )
    suspend fun countIncompleteRequiredSteps(taskId: String, occurrenceKey: String): Int

    @Query(
        """
        UPDATE instance_step SET completed = :completed, updatedAtEpochMillis = :updatedAt
        WHERE taskId = :taskId AND occurrenceKey = :occurrenceKey AND position = :position
        """,
    )
    suspend fun updateStep(
        taskId: String,
        occurrenceKey: String,
        position: Int,
        completed: Boolean,
        updatedAt: Long,
    ): Int

    @Insert
    suspend fun insertLedger(entry: PointsLedgerEntity)

    @Query(
        """
        SELECT * FROM points_ledger
        WHERE taskId = :taskId AND occurrenceKey = :occurrenceKey
        ORDER BY createdAtEpochMillis, ledgerId
        """,
    )
    suspend fun getLedger(taskId: String, occurrenceKey: String = "once"): List<PointsLedgerEntity>

    @Insert
    suspend fun insertLogs(logs: List<ActionLogEntity>)

    @Query(
        """
        SELECT * FROM action_log
        WHERE taskId = :taskId AND occurrenceKey = :occurrenceKey
        ORDER BY createdAtEpochMillis, eventId
        """,
    )
    suspend fun getLogs(taskId: String, occurrenceKey: String = "once"): List<ActionLogEntity>
}
