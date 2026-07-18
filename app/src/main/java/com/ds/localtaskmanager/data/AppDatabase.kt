package com.ds.localtaskmanager.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AppProfileEntity::class,
        ImportBatchEntity::class,
        TaskGroupEntity::class,
        TaskDefinitionEntity::class,
        TaskStepDefinitionEntity::class,
        TaskInstanceEntity::class,
        InstanceStepEntity::class,
        PointsLedgerEntity::class,
        ActionLogEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "dst-sub.db",
            )
                .fallbackToDestructiveMigration()
                .build()
    }
}
