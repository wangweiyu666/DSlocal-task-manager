package com.ds.localtaskmanager.data.statistics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ds.localtaskmanager.data.AppDatabase
import com.ds.localtaskmanager.data.PointsLedgerEntity
import com.ds.localtaskmanager.data.TaskDefinitionEntity
import com.ds.localtaskmanager.data.TaskGroupEntity
import com.ds.localtaskmanager.data.TaskInstanceEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
class W25StatisticsRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomStatisticsRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomStatisticsRepository(
            database,
            Clock.fixed(Instant.parse("2026-08-03T10:00:00Z"), ZoneOffset.UTC),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `dashboard uses task day, excludes futures, and counts final instance statuses`() = runTest {
        addGroup("group-a", "A", 20)
        addGroup("group-b", "B", 10)
        addGroup("group-c", "C", 30, archived = true)

        addTask("today", "2026-08-03", "group-a", required = true, status = "COMPLETED", category = "DAILY")
        addLedger("today-earned", "today", "group-a", 10, "COMPLETED", epoch("2026-08-03T08:00:00Z"))
        addLedger("today-undone", "today", "group-a", -20, "COMPLETION_UNDONE", epoch("2026-08-03T09:00:00Z"))
        addTask("week", "2026-07-28", "group-a", required = true, status = "PENDING", category = "WEEKLY")
        addTask("month", "2026-07-05", "group-b", required = false, status = "MISSED", category = "TEMPORARY")
        addLedger("month-deducted", "month", "group-b", -4, "COMPLETION_UNDONE", epoch("2026-07-06T08:00:00Z"))
        addTask("older", "2026-07-01", "group-b", required = true, status = "MISSED", category = "TEMPORARY")
        addLedger("older-earned", "older", "group-b", 2, "COMPLETED", epoch("2026-07-01T08:00:00Z"))
        addTask("cancelled", "2026-08-02", "group-c", required = true, status = "CANCELLED", category = "DAILY")
        addTask("not-started", "2026-08-02", "group-c", required = true, status = "NOT_STARTED", category = "DAILY")
        addTask("future", "2026-08-04", "group-a", required = true, status = "COMPLETED", category = "DAILY")
        addLedger("future-earned", "future", "group-a", 99, "COMPLETED", epoch("2026-08-04T08:00:00Z"))

        val dashboard = repository.dashboard(StatisticsPeriod.THIRTY_DAYS)

        assertEquals(PointsOverview(cumulative = -12, today = -10, sevenDays = -10, thirtyDays = -14), dashboard.overview)
        assertEquals(1, dashboard.completion.completed)
        assertEquals(2, dashboard.completion.total)
        assertEquals(
            listOf("group-b", "group-a"),
            dashboard.groups.filterNot(GroupStatistics::archived).map(GroupStatistics::groupId),
        )
        assertEquals(1, dashboard.requirement.single { it.key == "REQUIRED" }.counts.completed)
        assertEquals(1, dashboard.requirement.single { it.key == "REQUIRED" }.counts.pending)
        assertEquals(0, dashboard.requirement.single { it.key == "REQUIRED" }.counts.missed)
        assertEquals(1, dashboard.requirement.single { it.key == "REQUIRED" }.counts.cancelled)
        assertEquals(1, dashboard.categories.single { it.key == "DAILY" }.counts.completed)
        assertEquals(1, dashboard.categories.single { it.key == "WEEKLY" }.counts.pending)
        assertEquals(1, dashboard.categories.single { it.key == "TEMPORARY" }.counts.missed)
    }

    @Test
    fun `ledger merges transfer and preserves both sides when drilling into either group`() = runTest {
        addGroup("source", "来源", 1)
        addGroup("target", "目标", 2)
        addTask("moved", "2026-07-01", "target", required = true, status = "COMPLETED", category = "TEMPORARY")
        val movedAt = epoch("2026-08-03T09:00:00Z")
        addLedger("earned", "moved", "source", 5, "COMPLETED", epoch("2026-07-01T09:00:00Z"))
        addLedger("transfer-out", "moved", "source", -5, "GROUP_TRANSFER_OUT", movedAt)
        addLedger("transfer-in", "moved", "target", 5, "GROUP_TRANSFER_IN", movedAt)

        listOf("source", "target").forEach { groupId ->
            val item = repository.ledger(
                LedgerQuery(groupId = groupId, types = setOf(LedgerType.TRANSFER)),
                page = 0,
            ).items.single() as LedgerItem.Transfer
            assertEquals(listOf("来源"), item.sourceNames)
            assertEquals("目标", item.targetName)
            assertEquals(5, item.points)
        }
        assertEquals(5, repository.dashboard(StatisticsPeriod.ALL).overview.cumulative)
    }

    @Test
    fun `task day rolls over at four in the morning`() = runTest {
        val preFourRepository = RoomStatisticsRepository(
            database,
            Clock.fixed(Instant.parse("2026-08-03T03:59:00Z"), ZoneOffset.UTC),
        )
        addTask("previous-day", "2026-08-02", null, true, "COMPLETED", "DAILY")
        addLedger("previous-earned", "previous-day", null, 8, "COMPLETED", epoch("2026-08-03T03:30:00Z"))
        addTask("calendar-today", "2026-08-03", null, true, "COMPLETED", "DAILY")
        addLedger("calendar-earned", "calendar-today", null, 9, "COMPLETED", epoch("2026-08-03T03:30:00Z"))

        assertEquals(8, preFourRepository.dashboard(StatisticsPeriod.SEVEN_DAYS).overview.today)
    }

    @Test
    fun `ledger pagination does not split a transfer or duplicate it on the next page`() = runTest {
        addGroup("source", "来源", 1)
        addGroup("target", "目标", 2)
        repeat(49) { index ->
            val taskId = "normal-$index"
            addTask(taskId, "2026-08-03", "source", true, "COMPLETED", "DAILY")
            addLedger("ledger-$index", taskId, "source", 1, "COMPLETED", 10_000L - index)
        }
        addTask("moved", "2026-08-03", "target", true, "COMPLETED", "DAILY")
        addLedger("transfer-out", "moved", "source", -5, "GROUP_TRANSFER_OUT", 9_000)
        addLedger("transfer-in", "moved", "target", 5, "GROUP_TRANSFER_IN", 9_000)

        val first = repository.ledger(LedgerQuery(), page = 0)
        val second = repository.ledger(LedgerQuery(), page = 1)
        val all = first.items + second.items

        assertEquals(1, all.filterIsInstance<LedgerItem.Transfer>().size)
        assertEquals(50, all.map(LedgerItem::stableId).toSet().size)
        assertTrue(first.items.any { it is LedgerItem.Transfer })
        assertFalse(first.endReached)
        assertTrue(second.endReached)
        assertTrue(second.items.isEmpty())
    }

    private suspend fun addGroup(id: String, name: String, createdAt: Long, archived: Boolean = false) {
        database.definitionDao().upsertGroups(
            listOf(TaskGroupEntity(id, name, "", "", archived, createdAt, createdAt)),
        )
    }

    private suspend fun addTask(
        id: String,
        date: String,
        groupId: String?,
        required: Boolean,
        status: String,
        category: String,
    ) {
        database.definitionDao().upsertDefinitions(
            listOf(
                TaskDefinitionEntity(id, id, "", groupId, required, date, null, 5, null, "", "", false, 1, 1),
            ),
        )
        database.instanceDao().upsertInstances(
            listOf(
                TaskInstanceEntity(
                    id, "once", id, "", date, null, groupId, required, 5, null, "", status,
                    null, 1, 1, category = category, groupNameSnapshot = groupId?.let { database.definitionDao().getGroup(it)?.name },
                ),
            ),
        )
    }

    private suspend fun addLedger(id: String, taskId: String, groupId: String?, delta: Int, reason: String, at: Long) {
        database.auditDao().insertLedger(PointsLedgerEntity(id, taskId, "once", groupId, delta, reason, at))
    }

    private fun epoch(text: String): Long = Instant.parse(text).toEpochMilli()
}
