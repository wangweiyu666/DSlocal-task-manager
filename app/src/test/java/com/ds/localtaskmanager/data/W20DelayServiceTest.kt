package com.ds.localtaskmanager.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ds.localtaskmanager.data.recurrence.RoomInstanceGenerationService
import com.ds.localtaskmanager.data.result.RoomResultRepository
import com.ds.localtaskmanager.domain.RecordIdGenerator
import com.ds.localtaskmanager.domain.TaskDay
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.result.DailyResultStatus
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class W20DelayServiceTest {
    private lateinit var database: AppDatabase
    private lateinit var importService: ImportService
    private lateinit var executionService: TaskExecutionService
    private lateinit var resultRepository: RoomResultRepository
    private val clock = MutableClock(Instant.parse("2026-07-18T10:00:00Z"))
    private val sequence = AtomicInteger()
    private val ids = RecordIdGenerator { "W20Rec${sequence.incrementAndGet().toString().padStart(10, '0')}" }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        importService = RoomImportService(database, Dst1Parser(), clock, ids)
        executionService = RoomTaskExecutionService(database, clock, ids)
        resultRepository = RoomResultRepository(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `omitted y preserves date and future deadline reopens missed task with progress`() = runTest {
        importJson(taskJson("DelayBatch000001", y = DATE, deadline = "2026-07-18T12:00", withStep = true))
        executionService.setStep(TASK_ID, 0, true)
        clock.instantValue = Instant.parse("2026-07-18T13:00:00Z")
        reconcileForeground()
        assertEquals(TaskStatus.MISSED.name, instance().status)

        val preview = preview(taskJson("DelayBatch000002", y = null, deadline = "2026-07-20T20:00"))
        val change = preview.taskChanges.single()
        assertTrue(ImportChangeType.DEADLINE_EXTENDED in change.types)
        assertTrue(ImportChangeType.REOPENED in change.types)
        assertFalse(ImportChangeType.DATE_MOVED in change.types)
        assertEquals(DATE, change.newDate)
        importService.import(preview)

        assertEquals(DATE, instance().taskDate)
        assertEquals("2026-07-20T20:00", instance().deadline)
        assertEquals(TaskStatus.PENDING.name, instance().status)
        assertTrue(database.instanceDao().getInstanceSteps(TASK_ID).single().completed)
        assertEquals("TASK_REOPENED", database.auditDao().getLogs(TASK_ID).last().action)
        assertEquals("TASK_REOPENED", resultRepository.getRevisionTimeline(DATE).last().reason)

        val revisionCount = resultRepository.getRevisionTimeline(DATE).size
        val replay = preview(taskJson("DelayBatch000005", y = null, deadline = "2026-07-20T20:00"))
        assertEquals(setOf(ImportChangeType.UNCHANGED), replay.taskChanges.single().types)
        importService.import(replay)
        assertEquals(1, database.auditDao().getLogs(TASK_ID).count { it.action == "TASK_REOPENED" })
        assertEquals(revisionCount, resultRepository.getRevisionTimeline(DATE).size)
    }

    @Test
    fun `explicit y moves completed task date and its earned points`() = runTest {
        importJson(taskJson("DelayBatch000001", y = DATE, deadline = "2026-07-18T20:00"))
        executionService.complete(TASK_ID)

        val preview = preview(taskJson("DelayBatch000003", y = "2026-07-20", deadline = "2026-07-20T20:00"))
        val change = preview.taskChanges.single()
        assertTrue(ImportChangeType.DATE_MOVED in change.types)
        assertTrue(ImportChangeType.HISTORICAL_POINTS_MOVED in change.types)
        assertEquals(5, change.historicalPointsMoved)
        importService.import(preview)

        assertEquals("2026-07-20", instance().taskDate)
        assertEquals(TaskStatus.COMPLETED.name, instance().status)
        assertNull(resultRepository.getDailyResult(DATE))
        val moved = resultRepository.getDailyResult("2026-07-20")!!
        assertEquals(DailyResultStatus.COMPLETED, moved.global?.status)
        assertEquals(5, moved.global?.totalPoints)
        assertEquals(listOf(5), database.auditDao().getLedger(TASK_ID).map { it.delta })
        assertEquals("TASK_DATE_MOVED", resultRepository.getRevisionTimeline("2026-07-20").last().reason)
    }

    @Test
    fun `deadline-derived date never migrates an existing task without explicit y`() = runTest {
        importJson(taskJson("DelayBatch000001", y = DATE, deadline = "2026-07-18T20:00"))

        val preview = preview(taskJson("DelayBatch000004", y = null, deadline = "2026-07-20T20:00"))
        importService.import(preview)

        assertEquals(DATE, instance().taskDate)
        assertFalse(ImportChangeType.DATE_MOVED in preview.taskChanges.single().types)
    }

    @Test
    fun `delayed recurring occurrence is a new independent temporary task`() = runTest {
        importJson(
            """{"v":1,"b":"RepeatBatch00001","t":[{"i":"RepeatDelay00001","n":"Repeat","r":1,"x":{"f":1,"s":"2026-07-18","c":1}}]}""",
        )
        clock.instantValue = Instant.parse("2026-07-19T10:00:00Z")
        reconcileForeground()
        assertEquals(
            TaskStatus.MISSED.name,
            database.instanceDao().getInstance("RepeatDelay00001", DATE)?.status,
        )

        importJson(
            """{"v":1,"b":"TempBatch0000001","t":[{"i":"TempDelay0000001","n":"Repeat","r":1,"y":"2026-07-19","l":"2026-07-20T20:00"}]}""",
        )

        val original = database.instanceDao().getInstance("RepeatDelay00001", DATE)!!
        val temporary = database.instanceDao().getInstance("TempDelay0000001")!!
        assertEquals(TaskStatus.MISSED.name, original.status)
        assertEquals(TaskStatus.PENDING.name, temporary.status)
        assertEquals("TEMPORARY", temporary.category)
        assertEquals("once", temporary.occurrenceKey)
    }

    private suspend fun reconcileForeground() {
        RoomInstanceGenerationService(database, clock, ids).reconcileAll(
            TaskDay.from(LocalDateTime.ofInstant(clock.instant(), clock.zone)),
        )
    }

    private suspend fun preview(json: String): ImportPreview =
        importService.preview(encodeDst1ForTest(json))

    private suspend fun importJson(json: String) = importService.import(preview(json))

    private suspend fun instance(): TaskInstanceEntity = checkNotNull(database.instanceDao().getInstance(TASK_ID))

    private fun taskJson(batchId: String, y: String?, deadline: String, withStep: Boolean = false): String {
        val date = y?.let { ",\"y\":\"$it\"" }.orEmpty()
        val steps = if (withStep) ",\"s\":[{\"n\":\"Keep\",\"r\":1}]" else ""
        return """{"v":1,"b":"$batchId","t":[{"i":"$TASK_ID","n":"Task","r":1$date,"l":"$deadline","p":5$steps}]}"""
    }

    private class MutableClock(
        var instantValue: Instant,
        private val zoneValue: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zoneValue
        override fun withZone(zone: ZoneId): Clock = MutableClock(instantValue, zone)
        override fun instant(): Instant = instantValue
    }

    private companion object {
        const val TASK_ID = "DelayTask0000001"
        const val DATE = "2026-07-18"
    }
}
