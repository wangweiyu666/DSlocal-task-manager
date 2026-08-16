package com.ds.localtaskmanager.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.ds.localtaskmanager.data.dao.AuditDao
import com.ds.localtaskmanager.data.dao.BackupDao
import com.ds.localtaskmanager.data.dao.DefinitionDao
import com.ds.localtaskmanager.data.dao.ExecutionDao
import com.ds.localtaskmanager.data.dao.InstanceDao
import com.ds.localtaskmanager.data.dao.ProfileDao
import com.ds.localtaskmanager.data.dao.RecurrenceExceptionDao
import com.ds.localtaskmanager.data.dao.ReminderDao
import com.ds.localtaskmanager.data.dao.ResultDao
import com.ds.localtaskmanager.data.dao.StatisticsDao

@Database(
    entities = [
        AppProfileEntity::class,
        ImportBatchEntity::class,
        TaskGroupEntity::class,
        TaskDefinitionEntity::class,
        TaskStepDefinitionEntity::class,
        RecurrenceExceptionEntity::class,
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
    version = 6,
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
    abstract fun statisticsDao(): StatisticsDao
    abstract fun backupDao(): BackupDao
    abstract fun recurrenceExceptionDao(): RecurrenceExceptionDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "dst-sub.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
    }
}
