package com.ds.localtaskmanager.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val opened = mutableListOf<AppDatabase>()

    @After
    fun tearDown() {
        opened.forEach(AppDatabase::close)
        context.deleteDatabase(V1_DATABASE)
        context.deleteDatabase(V2_DATABASE)
    }

    @Test
    fun `version 1 migrates through version 2 and preserves task data`() {
        createLegacyDatabase(V1_DATABASE, 1) { db ->
            insertV1Task(db, "LegacyTaskV10001", "LegacyGroupV1001")
        }

        val database = openCurrent(V1_DATABASE)
        val definition = kotlinx.coroutines.runBlocking {
            database.definitionDao().getDefinition("LegacyTaskV10001")
        }
        val instance = kotlinx.coroutines.runBlocking {
            database.instanceDao().getInstance("LegacyTaskV10001")
        }

        assertNotNull(definition)
        assertEquals("Legacy task", definition?.name)
        assertEquals("NORMAL", definition?.executionKind)
        assertEquals("TEMPORARY", instance?.category)
        assertEquals(100L, instance?.publishedAtEpochMillis)
        assertEquals("未命名积分组", instance?.groupNameSnapshot)
        assertEquals("未命名积分组", kotlinx.coroutines.runBlocking {
            database.definitionDao().getGroups(listOf("LegacyGroupV1001")).single().name
        })
        assertNoForeignKeyViolations(database)
    }

    @Test
    fun `version 2 migrates audit rows and creates the frozen tables`() {
        createLegacyDatabase(V2_DATABASE, 2) { db ->
            insertV1Task(db, "LegacyTaskV20001", "LegacyGroupV2001")
            MIGRATION_1_2.migrate(db)
            db.execSQL("INSERT INTO import_batch VALUES ('LegacyBatchV2001', 'legacy', 300)")
            db.execSQL(
                "INSERT INTO points_ledger VALUES ('LegacyLedger0001', 'LegacyTaskV20001', 'once', 'LegacyGroupV2001', 7, 'COMPLETED', 301)",
            )
            db.execSQL(
                "INSERT INTO action_log VALUES ('LegacyEvent00001', 'LegacyTaskV20001', 'once', 'LegacyBatchV2001', 'IMPORTED', NULL, 302)",
            )
        }

        val database = openCurrent(V2_DATABASE)
        val ledger = kotlinx.coroutines.runBlocking {
            database.auditDao().getLedger("LegacyTaskV20001")
        }
        val logs = kotlinx.coroutines.runBlocking {
            database.auditDao().getLogs("LegacyTaskV20001")
        }

        assertEquals(listOf(7), ledger.map(PointsLedgerEntity::delta))
        assertEquals(listOf("IMPORTED"), logs.map(ActionLogEntity::action))
        val tables = database.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table'",
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertEquals(
            setOf(
                "execution_progress",
                "information_submission",
                "task_note",
                "result_revision",
                "reminder_record",
            ),
            tables.intersect(
                setOf(
                    "execution_progress",
                    "information_submission",
                    "task_note",
                    "result_revision",
                    "reminder_record",
                ),
            ),
        )
        assertNoForeignKeyViolations(database)
    }

    private fun createLegacyDatabase(
        name: String,
        version: Int,
        populate: (SupportSQLiteDatabase) -> Unit,
    ) {
        context.deleteDatabase(name)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    createV1Schema(db)
                    populate(db)
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            helper.writableDatabase
        }
    }

    private fun openCurrent(name: String): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
            .also {
                it.openHelper.writableDatabase
                opened += it
            }

    private fun createV1Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE task_instance (
              taskId TEXT NOT NULL,
              occurrenceKey TEXT NOT NULL,
              name TEXT NOT NULL,
              taskDate TEXT NOT NULL,
              groupId TEXT,
              required INTEGER NOT NULL,
              points INTEGER NOT NULL,
              status TEXT NOT NULL,
              createdAtEpochMillis INTEGER NOT NULL,
              updatedAtEpochMillis INTEGER NOT NULL,
              PRIMARY KEY(taskId, occurrenceKey)
            )
            """.trimIndent(),
        )
    }

    private fun insertV1Task(db: SupportSQLiteDatabase, taskId: String, groupId: String) {
        db.execSQL(
            """
            INSERT INTO task_instance
              (taskId, occurrenceKey, name, taskDate, groupId, required, points, status,
               createdAtEpochMillis, updatedAtEpochMillis)
            VALUES ('$taskId', 'once', 'Legacy task', '2026-07-18', '$groupId', 1, 7,
                    'PENDING', 100, 200)
            """.trimIndent(),
        )
    }

    private fun assertNoForeignKeyViolations(database: AppDatabase) {
        database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            assertFalse("Foreign key violation in migrated database", cursor.moveToFirst())
        }
    }

    private companion object {
        const val V1_DATABASE = "migration-v1.db"
        const val V2_DATABASE = "migration-v2.db"
    }
}
