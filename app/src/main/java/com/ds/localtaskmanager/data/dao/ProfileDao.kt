package com.ds.localtaskmanager.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.ds.localtaskmanager.data.AppProfileEntity
import com.ds.localtaskmanager.data.ImportBatchEntity

@Dao
interface ProfileDao {
    @Query("SELECT * FROM app_profile WHERE id = 1")
    suspend fun getProfile(): AppProfileEntity?

    @Upsert
    suspend fun upsertProfile(profile: AppProfileEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM import_batch WHERE batchId = :batchId)")
    suspend fun hasBatch(batchId: String): Boolean

    @Insert
    suspend fun insertBatch(batch: ImportBatchEntity)
}
