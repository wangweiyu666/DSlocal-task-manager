package com.ds.localtaskmanager.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ds.localtaskmanager.data.recurrence.RoomInstanceGenerationService
import com.ds.localtaskmanager.domain.RecordIdGenerator
import com.ds.localtaskmanager.domain.TaskDay
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.execution.ExecutionState
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import com.ds.localtaskmanager.protocol.Dst1Parser
import com.ds.localtaskmanager.protocol.encodeDst1ForTest
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class W11RecurrenceServiceTest {
    private lateinit var database: AppDatabase
    private lateinit var importService: ImportService
    private lateinit var generationService: RoomInstanceGenerationService
    private lateinit var executionService: TaskExecutionService
    private val clock = MutableClock(Instant.parse("2026-07-18T10:00:00Z"))
    private val sequence = AtomicInteger()
    private val ids = RecordIdGenerator { "W11Rec${sequence.incrementAndGet().toString().padStart(10, '0')}" }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        importService = RoomImportService(database, Dst1Parser(), clock, ids)
        generationService = RoomInstanceGenerationService(database, clock, ids)
        executionService = RoomTaskExecutionService(database, clock, ids)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `first daily import starts today and never creates once instance`() = runTest {
        val preview = importService.preview(
            encodeDst1ForTest(dailyJson("RepeatBatch00001", name = "First", count = 5)),
        )
        assertEquals(listOf("2026-07-18"), preview.taskChanges.single().generatedOccurrences)
        importService.import(preview)

        assertNull(database.instanceDao().getInstance(TASK_ID, "once"))
        val instances = instances()
        assertEquals(listOf("2026-07-18"), instances.map { it.occurrenceKey })
        assertEquals("DAILY", instances.single().category)
        assertEquals("First", instances.single().name)
        assertEquals("2026-07-19T04:00", instances.single().deadline)
    }

    @Test
    fun `offline reconciliation backfills missed dates and remains idempotent`() = runTest {
        importJson(dailyJson("RepeatBatch00001", count = 10))
        clock.instantValue = Instant.parse("2026-07-20T10:00:00Z")
        val through = TaskDay.from(LocalDateTime.ofInstant(clock.instant(), clock.zone))

        val first = generationService.reconcileAll(through)
        val second = generationService.reconcileAll(through)

        assertEquals(listOf("2026-07-19", "2026-07-20"), first.created.map { it.occurrenceKey })
        assertEquals(emptyList<TaskInstanceKey>(), second.created)
        assertEquals(
            listOf(TaskStatus.MISSED.name, TaskStatus.MISSED.name, TaskStatus.PENDING.name),
            instances().map { it.status },
        )
        assertEquals(3, database.instanceDao().generationSummaries(listOf(TASK_ID)).single().generatedCount)
    }

    @Test
    fun `template update preserves old snapshot and new date uses new snapshot`() = runTest {
        importJson(dailyJson("RepeatBatch00001", name = "Old", points = 1, count = 5))
        clock.instantValue = Instant.parse("2026-07-19T10:00:00Z")

        importJson(dailyJson("UpdateBatch00001", name = "New", points = 2, count = 5))

        val instances = instances()
        assertEquals(listOf("Old", "New"), instances.map { it.name })
        assertEquals(listOf(1, 2), instances.map { it.points })
    }

    @Test
    fun `generated count limits actual occurrences`() = runTest {
        importJson(dailyJson("RepeatBatch00001", count = 2))
        clock.instantValue = Instant.parse("2026-07-25T10:00:00Z")

        generationService.reconcileAll(TaskDay.from(LocalDateTime.ofInstant(clock.instant(), clock.zone)))

        assertEquals(listOf("2026-07-18", "2026-07-19"), instances().map { it.occurrenceKey })
    }

    @Test
    fun `cancellation stops generation and restore skips cancelled interval`() = runTest {
        importJson(dailyJson("RepeatBatch00001", count = 5))
        importJson("""{"v":1,"b":"CancelBatch00001","z":["$TASK_ID"]}""")
        clock.instantValue = Instant.parse("2026-07-20T10:00:00Z")
        generationService.reconcileAll(TaskDay.from(LocalDateTime.ofInstant(clock.instant(), clock.zone)))
        assertEquals(listOf("2026-07-18"), instances().map { it.occurrenceKey })
        assertEquals(TaskStatus.CANCELLED.name, instances().single().status)

        importJson(dailyJson("RestoreBatch0001", count = 5))

        assertEquals(listOf("2026-07-18", "2026-07-20"), instances().map { it.occurrenceKey })
        assertEquals(TaskStatus.CANCELLED.name, instances().first().status)
    }

    @Test
    fun `W10 execution service operates on dated occurrence key`() = runTest {
        importJson(dailyJson("RepeatBatch00001", count = 1, counterTarget = 2))
        val key = TaskInstanceKey(TASK_ID, "2026-07-18")

        executionService.setCounter(key, 2)
        executionService.complete(key)

        assertEquals(2, (executionService.getExecutionState(key) as ExecutionState.Counter).value)
        assertEquals(TaskStatus.COMPLETED.name, database.instanceDao().getInstance(TASK_ID, key.occurrenceKey)?.status)
        assertEquals(listOf(1), database.auditDao().getLedger(TASK_ID, key.occurrenceKey).map { it.delta })
    }

    @Test
    fun `single and recurring template conversion cancels competing active instances`() = runTest {
        importJson(singleJson("SingleBatch00001"))
        importJson(dailyJson("RepeatBatch00001", count = 5))
        assertEquals(TaskStatus.CANCELLED.name, database.instanceDao().getInstance(TASK_ID, "once")?.status)
        assertEquals(TaskStatus.PENDING.name, database.instanceDao().getInstance(TASK_ID, "2026-07-18")?.status)

        importJson(singleJson("SingleBatch00002"))

        assertEquals(TaskStatus.PENDING.name, database.instanceDao().getInstance(TASK_ID, "once")?.status)
        assertEquals(TaskStatus.CANCELLED.name, database.instanceDao().getInstance(TASK_ID, "2026-07-18")?.status)
    }

    @Test
    fun `generation transaction rolls back all dates when second audit insert fails`() = runTest {
        importJson(dailyJson("RepeatBatch00001", count = 5))
        clock.instantValue = Instant.parse("2026-07-20T10:00:00Z")
        val duplicateIds = RecordIdGenerator { "DuplicateGen0001" }
        val failing = RoomInstanceGenerationService(database, clock, duplicateIds)

        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking {
                failing.reconcileAll(TaskDay.from(LocalDateTime.ofInstant(clock.instant(), clock.zone)))
            }
        }

        assertEquals(listOf("2026-07-18"), instances().map { it.occurrenceKey })
    }

    private suspend fun importJson(json: String) {
        val preview = importService.preview(encodeDst1ForTest(json))
        importService.import(preview)
    }

    private suspend fun instances(): List<TaskInstanceEntity> =
        database.instanceDao().getInstancesForTasks(listOf(TASK_ID)).sortedBy { it.taskDate }

    private fun dailyJson(
        batchId: String,
        name: String = "Daily",
        points: Int = 1,
        count: Int,
        counterTarget: Int? = null,
    ): String {
        val execution = counterTarget?.let { ",\"u\":{\"k\":1,\"a\":2,\"v\":$it}" }.orEmpty()
        return """{"v":1,"b":"$batchId","t":[{"i":"$TASK_ID","n":"$name","r":1,"p":$points,"x":{"f":1,"s":"2026-07-01","c":$count}$execution}]}"""
    }

    private fun singleJson(batchId: String): String =
        """{"v":1,"b":"$batchId","t":[{"i":"$TASK_ID","n":"Single","r":1,"y":"2026-07-18"}]}"""

    private class MutableClock(
        var instantValue: Instant,
        private val zoneValue: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zoneValue
        override fun withZone(zone: ZoneId): Clock = MutableClock(instantValue, zone)
        override fun instant(): Instant = instantValue
    }

    private companion object {
        const val TASK_ID = "RepeatTask000001"
    }
}
