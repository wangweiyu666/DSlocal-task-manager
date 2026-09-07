package com.ds.localtaskmanager.connected

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.ds.localtaskmanager.data.connectedOpenHelperFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ConnectedSyncDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val opened = mutableListOf<ConnectedSyncDatabase>()

    @After
    fun tearDown() {
        opened.forEach(ConnectedSyncDatabase::close)
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    // Robolectric includes the method and application ID in its Windows database path.
    // Keep this name short enough for the longer production application ID.
    fun `migrates v1 sync state`() = runBlocking {
        createLegacyDatabase { db ->
            db.execSQL(
                "INSERT INTO cloud_session " +
                    "(id, accessToken, refreshToken, csrfToken, accessExpiresAt, accountId, membershipId, spaceId, spaceName, role) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("active", "access-1", "refresh-1", "csrf-1", "2026-09-06T00:00:00Z", "account-1", "membership-1", "space-1", "测试空间", "ADMIN"),
            )
            db.execSQL(
                "INSERT INTO cloud_entity " +
                    "(key, spaceId, entityType, entityId, entityVersion, payloadVersion, payloadJson, updatedAt) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("space-1:task-1", "space-1", "TASK", "task-1", 7, 3, "{\"answer\":\"保留\"}", "2026-09-06T00:01:00Z"),
            )
            db.execSQL(
                "INSERT INTO cloud_outbox " +
                    "(commandId, spaceId, semanticKey, commandJson, queuedAt, attempts) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf("command-1", "space-1", "task-1:complete", "{\"answer\":\"保留\",\"n\":1}", "2026-09-06T00:02:00Z", 2),
            )
            db.execSQL(
                "INSERT INTO cloud_sent_semantic (semanticKey, spaceId, commandId, acceptedAt) VALUES (?, ?, ?, ?)",
                arrayOf("task-0:complete", "space-1", "command-0", "2026-09-05T23:59:00Z"),
            )
            db.execSQL(
                "INSERT INTO cloud_sync_meta (spaceId, changesCursor, lastSyncedAt, timeZone, timeZoneVersion) VALUES (?, ?, ?, ?, ?)",
                arrayOf("space-1", "cursor-17", "2026-09-06T00:03:00Z", "Asia/Hong_Kong", 4),
            )
            db.execSQL(
                "INSERT INTO cloud_occurrence_map " +
                    "(localKey, spaceId, taskId, localOccurrenceKey, cloudOccurrenceKey, scheduledAt, assignmentId, taskRevision, timeZoneVersion) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("task-1:2026-09-06", "space-1", "task-1", "2026-09-06", "occ-1", "2026-09-06T09:00:00+08:00", "assignment-1", 9, 4),
            )
        }

        val migrated = openCurrent()
        val dao = migrated.dao()
        val session = dao.session()
        assertEquals("access-1", session?.accessToken)
        assertEquals("refresh-1", session?.refreshToken)
        assertEquals("csrf-1", session?.csrfToken)
        assertEquals("2026-09-06T00:00:00Z", session?.accessExpiresAt)
        assertEquals("account-1", session?.accountId)
        assertEquals("membership-1", session?.membershipId)
        assertEquals("space-1", session?.spaceId)
        assertEquals("测试空间", session?.spaceName)
        assertEquals("ADMIN", session?.role)
        assertEquals("legacy", session?.sessionGeneration)
        assertEquals(0L, session?.retryUntilEpochMillis)
        assertEquals(false, session?.needsSync)
        assertNull(session?.blockedReason)
        val entity = dao.entities("space-1").single()
        assertEquals("task-1", entity.entityId)
        assertEquals(7, entity.entityVersion)
        assertEquals("{\"answer\":\"保留\"}", entity.payloadJson)
        val outbox = dao.outbox("space-1").single()
        assertEquals("command-1", outbox.commandId)
        assertEquals("task-1:complete", outbox.semanticKey)
        assertEquals("{\"answer\":\"保留\",\"n\":1}", outbox.commandJson)
        assertEquals("2026-09-06T00:02:00Z", outbox.queuedAt)
        assertEquals(2, outbox.attempts)
        val meta = dao.meta("space-1")
        assertEquals("cursor-17", meta?.changesCursor)
        assertEquals("2026-09-06T00:03:00Z", meta?.lastSyncedAt)
        assertEquals("Asia/Hong_Kong", meta?.timeZone)
        assertEquals(4, meta?.timeZoneVersion)
        val occurrence = dao.occurrenceMap("task-1:2026-09-06")
        assertEquals("occ-1", occurrence?.cloudOccurrenceKey)
        assertEquals("assignment-1", occurrence?.assignmentId)
        assertEquals(9, occurrence?.taskRevision)
        assertEquals("command-0", queryString(migrated, "SELECT commandId FROM cloud_sent_semantic WHERE semanticKey = 'task-0:complete'"))
        assertEquals("2026-09-05T23:59:00Z", queryString(migrated, "SELECT acceptedAt FROM cloud_sent_semantic WHERE semanticKey = 'task-0:complete'"))
        assertEquals("space-1:task-1", queryString(migrated, "SELECT key FROM cloud_entity WHERE key = 'space-1:task-1'"))
        assertNull(queryStringOrNull(migrated, "SELECT retryAtEpochMillis FROM cloud_outbox WHERE commandId = 'command-1'"))
        assertEquals("PENDING", queryString(migrated, "SELECT state FROM cloud_outbox WHERE commandId = 'command-1'"))
        assertNull(queryStringOrNull(migrated, "SELECT lastError FROM cloud_outbox WHERE commandId = 'command-1'"))

        migrated.openHelper.writableDatabase.execSQL(
            "UPDATE cloud_session SET sessionGeneration = ?, retryUntilEpochMillis = ?, needsSync = ?, blockedReason = ? WHERE id = ?",
            arrayOf("generation-2", 1_777_000_000_000L, 1, "RETRYABLE", "active"),
        )
        migrated.openHelper.writableDatabase.execSQL(
            "UPDATE cloud_outbox SET retryAtEpochMillis = ?, state = ?, lastError = ? WHERE commandId = ?",
            arrayOf(1_777_000_000_000L, "RETRYABLE", "temporary", "command-1"),
        )
        migrated.close()
        opened.remove(migrated)

        val reopened = openCurrent()
        assertEquals("generation-2", reopened.dao().session()?.sessionGeneration)
        assertEquals(1_777_000_000_000L, reopened.dao().session()?.retryUntilEpochMillis)
        assertEquals(true, reopened.dao().session()?.needsSync)
        assertEquals("RETRYABLE", reopened.dao().session()?.blockedReason)
        val retried = reopened.dao().outbox("space-1").single()
        assertEquals(1_777_000_000_000L, retried.retryAtEpochMillis)
        assertEquals("RETRYABLE", retried.state)
        assertEquals("temporary", retried.lastError)
        assertNoForeignKeyViolations(reopened)
    }

    private fun createLegacyDatabase(populate: (SupportSQLiteDatabase) -> Unit) {
        context.deleteDatabase(DATABASE_NAME)
        val schema = readSchema()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DATABASE_NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema.tables.forEach { (table, sql) -> db.execSQL(sql.replace("\${TABLE_NAME}", table)) }
                    db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                    db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)", arrayOf(schema.identityHash))
                    populate(db)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { it.writableDatabase }
    }

    private fun openCurrent(): ConnectedSyncDatabase = Room.databaseBuilder(
        context,
        ConnectedSyncDatabase::class.java,
        DATABASE_NAME,
    ).openHelperFactory(connectedOpenHelperFactory(context))
        .addMigrations(MIGRATION_1_2)
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        .allowMainThreadQueries()
        .build()
        .also { it.openHelper.writableDatabase; opened += it }

    private fun queryString(database: ConnectedSyncDatabase, sql: String): String =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun queryStringOrNull(database: ConnectedSyncDatabase, sql: String): String? =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getString(0)
        }

    private fun assertNoForeignKeyViolations(database: ConnectedSyncDatabase) {
        database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            assertTrue(!cursor.moveToFirst())
        }
    }

    private data class LegacySchema(val identityHash: String, val tables: List<Pair<String, String>>)

    private fun readSchema(): LegacySchema {
        val path = sequenceOf(
            File("app/schemas/com.ds.localtaskmanager.connected.ConnectedSyncDatabase/1.json"),
            File("../app/schemas/com.ds.localtaskmanager.connected.ConnectedSyncDatabase/1.json"),
        ).firstOrNull(File::isFile) ?: error("ConnectedSyncDatabase v1 schema not found")
        val root = Json.parseToJsonElement(path.readText()).jsonObject
        val database = root.getValue("database").jsonObject
        val identityHash = database.getValue("identityHash").jsonPrimitive.content
        val tables = database.getValue("entities").jsonArray.map { entity ->
            val value = entity.jsonObject
            value.getValue("tableName").jsonPrimitive.content to
                value.getValue("createSql").jsonPrimitive.content
        }
        check(tables.size == 6) { "Expected six v1 connected tables, found ${tables.size}" }
        return LegacySchema(identityHash, tables)
    }

    private companion object {
        const val DATABASE_NAME = "connected-sync-migration-test.db"
    }
}
