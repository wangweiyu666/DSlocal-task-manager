package com.ds.localtaskmanager.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ds.localtaskmanager.data.recurrence.RoomInstanceGenerationService
import com.ds.localtaskmanager.domain.RecordIdGenerator
import com.ds.localtaskmanager.domain.TaskDay
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import com.ds.localtaskmanager.protocol.Dst1Parser
import com.ds.localtaskmanager.protocol.encodeDst1ForTest
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MoodInstanceIsolationTest {
    private lateinit var database: AppDatabase
    private lateinit var importService: ImportService
    private lateinit var generationService: RoomInstanceGenerationService
    private lateinit var executionService: TaskExecutionService
    private val clock = MutableClock(Instant.parse("2026-09-06T10:00:00Z"))
    private val sequence = AtomicInteger()
    private val ids = RecordIdGenerator { "MoodIso${sequence.incrementAndGet().toString().padStart(10, '0')}" }

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        importService = RoomImportService(database, Dst1Parser(), clock, ids)
        generationService = RoomInstanceGenerationService(database, clock, ids)
        executionService = RoomTaskExecutionService(database, clock, ids)
    }

    @After fun tearDown() = database.close()

    @Test
    fun `daily mood answers stay isolated and type changes clear only pending occurrence`() = runTest {
        importJson(dailyMood("MoodIsoBatch0001", count = 3, points = 7))
        val dayA = TaskInstanceKey(TASK_ID, "2026-09-06")
        executionService.saveMoodDraft(dayA, 1, "低落")
        executionService.complete(dayA)

        clock.instantValue = Instant.parse("2026-09-07T10:00:00Z")
        generationService.reconcileAll(TaskDay.from(LocalDateTime.ofInstant(clock.instant(), clock.zone)))
        val dayB = TaskInstanceKey(TASK_ID, "2026-09-07")
        assertEquals(TaskStatus.PENDING.name, database.instanceDao().getInstance(TASK_ID, dayB.occurrenceKey)?.status)
        assertNull(database.executionDao().getMood(TASK_ID, dayB.occurrenceKey)?.rating)
        executionService.saveMoodDraft(dayB, 2, "B draft")
        assertEquals(2, database.executionDao().getMood(TASK_ID, dayB.occurrenceKey)?.rating)

        importJson("""{"v":1,"sv":1,"b":"MoodIsoExcept001","e":[{"i":"$TASK_ID","y":"2026-09-07","u":null}]}""")
        assertEquals("NORMAL", database.instanceDao().getInstance(TASK_ID, dayB.occurrenceKey)?.executionKind)
        assertNull(database.executionDao().getMood(TASK_ID, dayB.occurrenceKey))
        assertEquals(TaskStatus.COMPLETED.name, database.instanceDao().getInstance(TASK_ID, dayA.occurrenceKey)?.status)
        assertEquals(1, database.executionDao().getMood(TASK_ID, dayA.occurrenceKey)?.rating)
        assertEquals("低落", database.executionDao().getMood(TASK_ID, dayA.occurrenceKey)?.text)

        clock.instantValue = Instant.parse("2026-09-08T10:00:00Z")
        generationService.reconcileAll(TaskDay.from(LocalDateTime.ofInstant(clock.instant(), clock.zone)))
        val dayC = TaskInstanceKey(TASK_ID, "2026-09-08")
        executionService.saveMoodDraft(dayC, 5, "很好")
        executionService.complete(dayC)

        assertEquals(listOf(7), database.auditDao().getLedger(TASK_ID, dayA.occurrenceKey).map { it.delta })
        assertEquals(listOf(7), database.auditDao().getLedger(TASK_ID, dayC.occurrenceKey).map { it.delta })
        assertNotNull(database.executionDao().getMood(TASK_ID, dayC.occurrenceKey)?.submittedAtEpochMillis)
        assertEquals(7, database.instanceDao().getInstance(TASK_ID, dayA.occurrenceKey)?.points)
        assertEquals(7, database.instanceDao().getInstance(TASK_ID, dayC.occurrenceKey)?.points)
    }

    private suspend fun importJson(json: String) {
        val preview = importService.preview(encodeDst1ForTest(json))
        importService.import(preview)
    }

    private fun dailyMood(batchId: String, count: Int, points: Int) =
        """{"v":1,"b":"$batchId","t":[{"i":"$TASK_ID","n":"每日心情","r":1,"p":$points,"x":{"f":1,"s":"2026-09-06","c":$count},"u":{"k":4}}]}"""

    private class MutableClock(var instantValue: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
        override fun instant() = instantValue
    }

    private companion object { const val TASK_ID = "MoodIsoTask00001" }
}
