package com.ds.localtaskmanager.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.ds.localtaskmanager.data.dao.AuditDao
import com.ds.localtaskmanager.data.dao.DefinitionDao
import com.ds.localtaskmanager.data.dao.ExecutionDao
import com.ds.localtaskmanager.data.dao.InstanceDao
import com.ds.localtaskmanager.data.dao.ProfileDao
import com.ds.localtaskmanager.data.dao.ReminderDao
import com.ds.localtaskmanager.data.dao.ResultDao

@Database(
    entities = [
        AppProfileEntity::class,
        ImportBatchEntity::class,
        TaskGroupEntity::class,
        TaskDefinitionEntity::class,
        TaskStepDefinitionEntity::class,
        TaskInstanceEntity::class,
        InstanceStepEntity::class,
        ExecutionProgressEntity::class,
        InformationSubmissionEntity::class,
        TaskNoteEntity::class,
        PointsLedgerEntity::class,
        ActionLogEntity::class,
        ResultRevisionEntity::class,
        ReminderRecordEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun definitionDao(): DefinitionDao
    abstract fun instanceDao(): InstanceDao
    abstract fun executionDao(): ExecutionDao
    abstract fun auditDao(): AuditDao
    abstract fun resultDao(): ResultDao
    abstract fun reminderDao(): ReminderDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "dst-sub.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
