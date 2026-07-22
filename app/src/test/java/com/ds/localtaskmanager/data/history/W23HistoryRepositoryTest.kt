package com.ds.localtaskmanager.data.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ds.localtaskmanager.data.AppDatabase
import com.ds.localtaskmanager.data.TaskDefinitionEntity
import com.ds.localtaskmanager.data.TaskGroupEntity
import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.data.TaskNoteEntity
import com.ds.localtaskmanager.domain.TaskStatus
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class W23HistoryRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: HistoryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomHistoryRepository(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `history pages by task date and searches snapshot group and note`() = runTest {
        insertDefinition("HistoryGroup001", "现在的组名")
        insertInstance("2026-07-21", "one", TaskStatus.COMPLETED, "旧组名")
        insertInstance("2026-07-20", "two", TaskStatus.MISSED, "旧组名")
        insertInstance("2026-07-19", "three", TaskStatus.CANCELLED, "旧组名")
        database.executionDao().upsertNote(
            TaskNoteEntity(TASK_ID, "two", "需要再次检查", 1, 1),
        )

        val first = repository.loadPage(HistoryQuery(), "2026-07-21", page = 0, pageSize = 2)
        val second = repository.loadPage(HistoryQuery(), "2026-07-21", page = 1, pageSize = 2)

        assertEquals(listOf("2026-07-21", "2026-07-20"), first.days.map { it.taskDate })
        assertFalse(first.endReached)
        assertEquals(listOf("2026-07-19"), second.days.map { it.taskDate })
        assertTrue(second.endReached)

        val groupSearch = repository.loadPage(HistoryQuery(text = "旧组名"), "2026-07-21", 0)
        assertEquals(3, groupSearch.days.size)
        val noteSearch = repository.loadPage(HistoryQuery(text = "再次检查"), "2026-07-21", 0)
        assertEquals(listOf("2026-07-20"), noteSearch.days.map { it.taskDate })
        assertEquals("旧组名", noteSearch.days.single().tasks.single().instance.groupNameSnapshot)
    }

    @Test
    fun `status source requirement and selected date filters compose`() = runTest {
        insertDefinition("HistoryGroup002", "分组")
        insertInstance("2026-07-21", "daily-required", TaskStatus.COMPLETED, "分组", category = "DAILY", required = true)
        insertInstance("2026-07-21", "weekly-optional", TaskStatus.COMPLETED, "分组", category = "WEEKLY", required = false)
        insertInstance("2026-07-20", "daily-missed", TaskStatus.MISSED, "分组", category = "DAILY", required = true)

        val query = HistoryQuery(
            statuses = setOf(TaskStatus.COMPLETED.name),
            categories = setOf("DAILY"),
            requirement = HistoryRequirement.REQUIRED,
            selectedDate = "2026-07-21",
        )
        val page = repository.loadPage(query, "2026-07-21", 0)

        assertEquals(listOf("daily-required"), page.days.single().tasks.map { it.instance.occurrenceKey })
    }

    @Test
    fun `ten thousand instances still return only one thirty-day page`() = runTest {
        insertDefinition("HistoryGroup003", "压力测试")
        val start = LocalDate.parse("2026-07-21")
        val instances = (0 until 10_000).map { index ->
            val date = start.minusDays((index / 100).toLong()).toString()
            instance(date, "load-$index", TaskStatus.COMPLETED, "压力测试")
        }
        database.instanceDao().upsertInstances(instances)

        val page = repository.loadPage(HistoryQuery(), "2026-07-21", 0, pageSize = 30)

        assertEquals(30, page.days.size)
        assertEquals(3_000, page.days.sumOf { it.tasks.size })
        assertFalse(page.endReached)
    }

    private suspend fun insertDefinition(groupId: String, groupName: String) {
        database.definitionDao().upsertGroups(
            listOf(TaskGroupEntity(groupId, groupName, "完成", "未完成", false, 1, 1)),
        )
        database.definitionDao().upsertDefinitions(
            listOf(
                TaskDefinitionEntity(
                    taskId = TASK_ID,
                    name = "历史任务",
                    description = "",
                    groupId = groupId,
                    required = true,
                    taskDate = "2026-07-21",
                    deadline = null,
                    points = 5,
                    sortOrder = null,
                    completionMessage = "完成",
                    stepsFingerprint = "",
                    cancelled = false,
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 1,
                ),
            ),
        )
    }

    private suspend fun insertInstance(
        date: String,
        occurrenceKey: String,
        status: TaskStatus,
        groupName: String,
        category: String = "TEMPORARY",
        required: Boolean = true,
    ) {
        database.instanceDao().upsertInstances(listOf(instance(date, occurrenceKey, status, groupName, category, required)))
    }

    private fun instance(
        date: String,
        occurrenceKey: String,
        status: TaskStatus,
        groupName: String,
        category: String = "TEMPORARY",
        required: Boolean = true,
    ) = TaskInstanceEntity(
        taskId = TASK_ID,
        occurrenceKey = occurrenceKey,
        name = "任务 $occurrenceKey",
        description = "",
        taskDate = date,
        deadline = "${date}T22:00",
        groupId = null,
        required = required,
        points = 5,
        sortOrder = null,
        completionMessage = "完成",
        status = status.name,
        completedAtEpochMillis = if (status == TaskStatus.COMPLETED) 1 else null,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
        category = category,
        groupNameSnapshot = groupName,
    )

    private companion object {
        const val TASK_ID = "HistoryTask00001"
    }
}
