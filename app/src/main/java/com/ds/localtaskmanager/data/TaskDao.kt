package com.ds.localtaskmanager.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query(
        """
        SELECT * FROM task_instance
        WHERE taskDate = :taskDate AND status != 'CANCELLED'
        ORDER BY required DESC, createdAtEpochMillis ASC
        """,
    )
    fun observeForDate(taskDate: String): Flow<List<TaskInstanceEntity>>

    @Upsert
    suspend fun upsertAll(tasks: List<TaskInstanceEntity>)
}
