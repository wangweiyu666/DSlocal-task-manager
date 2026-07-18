package com.ds.localtaskmanager.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.ds.localtaskmanager.data.ResultRevisionEntity

@Dao
interface ResultDao {
    @Insert
    suspend fun insertRevision(revision: ResultRevisionEntity)

    @Query("SELECT * FROM result_revision WHERE taskDate = :taskDate ORDER BY createdAtEpochMillis, revisionId")
    suspend fun revisionsForDate(taskDate: String): List<ResultRevisionEntity>
}
