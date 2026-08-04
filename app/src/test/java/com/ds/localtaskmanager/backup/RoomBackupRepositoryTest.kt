package com.ds.localtaskmanager.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ds.localtaskmanager.data.AppDatabase
import com.ds.localtaskmanager.data.TaskDefinitionEntity
import com.ds.localtaskmanager.data.TaskGroupEntity
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
class RoomBackupRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomBackupRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomBackupRepository(database, AppSettingsRepository(context))
    }

    @After fun close() = database.close()

    @Test
    fun snapshotUsesStableBusinessTables() = runTest {
        database.definitionDao().upsertGroups(listOf(group("g")))

        val snapshot = repository.snapshot()

        assertEquals(listOf("g"), snapshot.groups.map { it.groupId })
        assertTrue(snapshot.instances.isEmpty())
    }

    @Test
    fun failedReplacementRollsBackExistingData() = runTest {
        database.definitionDao().upsertGroups(listOf(group("local")))
        val invalid = BackupPayload(
            definitions = listOf(
                TaskDefinitionEntity(
                    taskId = "task",
                    name = "无效引用",
                    description = "",
                    groupId = "missing",
                    required = true,
                    taskDate = "2026-08-05",
                    deadline = null,
                    points = 1,
                    sortOrder = null,
                    completionMessage = "完成",
                    stepsFingerprint = "",
                    cancelled = false,
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 1,
                ).toBackup(),
            ),
        )

        assertTrue(runCatching { repository.replace(invalid) }.isFailure)
        assertEquals("本机", database.definitionDao().getGroup("local")?.name)
    }

    private fun group(id: String) = TaskGroupEntity(id, "本机", "完成", "未完成", false, 1, 1)
}
