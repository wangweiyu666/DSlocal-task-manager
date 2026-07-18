package com.ds.localtaskmanager.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.ds.localtaskmanager.data.InstanceStepEntity
import com.ds.localtaskmanager.data.TaskInstanceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InstanceDao {
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
    suspend fun getInstanceSteps(
        taskId: String,
        occurrenceKey: String = "once",
    ): List<InstanceStepEntity>

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
}
