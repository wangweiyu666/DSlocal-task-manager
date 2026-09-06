package com.ds.localtaskmanager.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ds.localtaskmanager.data.AppDatabase
import com.ds.localtaskmanager.settings.AppSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MoodBackupRoomRegressionTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomBackupRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomBackupRepository(database, AppSettingsRepository(context))
    }

    @After fun close() = database.close()

    @Test
    fun `replace and snapshot preserve a valid completed mood parent and answer`() = runTest {
        val payload = moodPayload(completed = true, parentTime = 100, moodTime = 100)
        repository.replace(payload)

        val snapshot = repository.snapshot()
        assertEquals(payload.definitions, snapshot.definitions)
        assertEquals(payload.instances, snapshot.instances)
        assertEquals(payload.moods, snapshot.moods)
    }

    @Test
    fun `merge keeps completed mood snapshot over newer draft and excludes mood for normal parent`() = runTest {
        val local = moodPayload(completed = false, parentTime = 50, moodTime = 200)
        repository.replace(local)
        val completedBackup = moodPayload(completed = true, parentTime = 100, moodTime = 100)

        val preview = repository.previewMerge(completedBackup)
        val merged = preview.merged
        assertEquals("COMPLETED", merged.instances.single().status)
        assertEquals(4, merged.moods.single().rating)
        assertEquals(100L, merged.moods.single().submittedAtEpochMillis)

        val newerPending = moodPayload(completed = false, parentTime = 200, moodTime = 300)
        repository.replace(completedBackup)
        val pendingMerged = repository.previewMerge(newerPending).merged
        assertEquals("PENDING", pendingMerged.instances.single().status)
        assertEquals(4, pendingMerged.moods.single().rating)
        assertEquals(null, pendingMerged.moods.single().submittedAtEpochMillis)

        val normal = normalPayload()
        val typeChanged = repository.previewMerge(normal).merged
        assertEquals("NORMAL", typeChanged.instances.single().executionKind)
        assertTrue(typeChanged.moods.isEmpty())
    }

    private fun moodPayload(completed: Boolean, parentTime: Long, moodTime: Long): BackupPayload {
        val status = if (completed) "COMPLETED" else "PENDING"
        val completedAt = if (completed) parentTime else null
        return BackupPayload(
            definitions = listOf(definition("MOOD")),
            instances = listOf(instance("MOOD", status, parentTime, completedAt)),
            moods = listOf(MoodBackup("mood-task", "2026-09-06", 4, "稳定", moodTime, moodTime, completedAt)),
        )
    }

    private fun normalPayload() = BackupPayload(
        definitions = listOf(definition("NORMAL")),
        instances = listOf(instance("NORMAL", "PENDING", 300, null)),
    )

    private fun definition(kind: String) = DefinitionBackup(
        taskId = "mood-task", name = "心情", description = "", groupId = null, required = true,
        taskDate = "2026-09-06", deadline = null, points = 1, sortOrder = null, completionMessage = "完成",
        stepsFingerprint = "", cancelled = false, createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
        executionKind = kind,
    )

    private fun instance(kind: String, status: String, time: Long, completedAt: Long?) = InstanceBackup(
        taskId = "mood-task", occurrenceKey = "2026-09-06", name = "心情", description = "",
        taskDate = "2026-09-06", deadline = null, groupId = null, required = true, points = 1,
        sortOrder = null, completionMessage = "完成", status = status, completedAtEpochMillis = completedAt,
        createdAtEpochMillis = 1, updatedAtEpochMillis = time, category = "TEMPORARY", executionKind = kind,
        executionAction = null, executionTarget = null, reminderMinutesJson = null, publishedAtEpochMillis = 1,
        groupNameSnapshot = null,
    )
}
