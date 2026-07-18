package com.ds.localtaskmanager.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.ds.localtaskmanager.data.TaskDefinitionEntity
import com.ds.localtaskmanager.data.TaskGroupEntity
import com.ds.localtaskmanager.data.TaskStepDefinitionEntity

@Dao
interface DefinitionDao {
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
}
