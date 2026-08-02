package com.ds.localtaskmanager.data.statistics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ds.localtaskmanager.data.AppDatabase
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class W25StatisticsPerformanceTest {
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
    fun `ten thousand instances and one hundred thousand ledger rows meet W25 load targets`() = runTest {
        seedLargeDataset()

        val dashboardStartedAt = System.nanoTime()
        val dashboard = repository.dashboard(StatisticsPeriod.THIRTY_DAYS)
        val dashboardMillis = (System.nanoTime() - dashboardStartedAt) / 1_000_000
        val ledgerStartedAt = System.nanoTime()
        val ledger = repository.ledger(LedgerQuery(), page = 0)
        val ledgerMillis = (System.nanoTime() - ledgerStartedAt) / 1_000_000

        println("W25 performance: dashboard=${dashboardMillis}ms, ledgerFirstPage=${ledgerMillis}ms")
        assertEquals(50, ledger.items.size)
        assertTrue("dashboard took ${dashboardMillis}ms", dashboardMillis < 2_000)
        assertTrue("ledger first page took ${ledgerMillis}ms", ledgerMillis < 500)
        assertTrue(dashboard.overview.cumulative != Int.MIN_VALUE)
    }

    private fun seedLargeDataset() {
        val db = database.openHelper.writableDatabase
        val taskDate = LocalDate.of(2026, 8, 3)
        db.beginTransaction()
        try {
            repeat(10_000) { index ->
                val taskId = "perf-task-$index"
                val date = taskDate.minusDays((index % 30).toLong()).toString()
                db.execSQL(
                    """
                    INSERT INTO task_definition (
                        taskId, name, description, groupId, required, taskDate, deadline, points,
                        sortOrder, completionMessage, stepsFingerprint, cancelled,
                        createdAtEpochMillis, updatedAtEpochMillis, executionKind
                    ) VALUES (?, ?, '', NULL, 1, ?, NULL, 1, NULL, '', '', 0, 1, 1, 'NORMAL')
                    """.trimIndent(),
                    arrayOf(taskId, "性能任务 $index", date),
                )
                db.execSQL(
                    """
                    INSERT INTO task_instance (
                        taskId, occurrenceKey, name, description, taskDate, deadline, groupId, required,
                        points, sortOrder, completionMessage, status, completedAtEpochMillis,
                        createdAtEpochMillis, updatedAtEpochMillis, category, executionKind, publishedAtEpochMillis
                    ) VALUES (?, 'once', ?, '', ?, NULL, NULL, 1, 1, NULL, '', 'COMPLETED', 1, 1, 1, 'DAILY', 'NORMAL', 1)
                    """.trimIndent(),
                    arrayOf(taskId, "性能任务 $index", date),
                )
            }
            repeat(100_000) { index ->
                val taskId = "perf-task-${index % 10_000}"
                db.execSQL(
                    """
                    INSERT INTO points_ledger
                        (ledgerId, taskId, occurrenceKey, groupId, delta, reason, createdAtEpochMillis)
                    VALUES (?, ?, 'once', NULL, 1, 'COMPLETED', ?)
                    """.trimIndent(),
                    arrayOf("perf-ledger-$index", taskId, 1_700_000_000_000L + index),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
