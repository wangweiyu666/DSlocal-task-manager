package com.ds.localtaskmanager.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ds.localtaskmanager.data.result.ResultRepository
import com.ds.localtaskmanager.data.result.RoomResultRepository
import com.ds.localtaskmanager.data.recurrence.RoomInstanceGenerationService
import com.ds.localtaskmanager.domain.RecordIdGenerator
import com.ds.localtaskmanager.domain.TaskDay
import com.ds.localtaskmanager.domain.result.DailyResultStatus
import com.ds.localtaskmanager.protocol.Dst1Parser
import com.ds.localtaskmanager.protocol.encodeDst1ForTest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class W12ResultServiceTest {
    private lateinit var database: AppDatabase
    private lateinit var importService: ImportService
    private lateinit var executionService: TaskExecutionService
    private lateinit var resultRepository: ResultRepository
    private val clock = MutableClock(Instant.parse("2026-07-18T10:00:00Z"))
    private val sequence = AtomicInteger()
    private val ids = RecordIdGenerator { "W12Rec${sequence.incrementAndGet().toString().padStart(10, '0')}" }

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
    fun `completion and undo recalculate current result and append revisions`() = runTest {
        importJson(taskJson("ResultBatch00001", GROUP_A))
        assertEquals(DailyResultStatus.IN_PROGRESS, resultRepository.getDailyResult(DATE)?.global?.status)

        executionService.complete(TASK_ID)
        assertEquals(DailyResultStatus.COMPLETED, resultRepository.getDailyResult(DATE)?.global?.status)
        assertEquals(5, resultRepository.getDailyResult(DATE)?.global?.totalPoints)

        executionService.undoCompletion(TASK_ID)
        assertEquals(DailyResultStatus.IN_PROGRESS, resultRepository.getDailyResult(DATE)?.global?.status)
        assertEquals(0, resultRepository.getDailyResult(DATE)?.global?.totalPoints)
        assertEquals(
            listOf("TASK_IMPORTED", "TASK_IMPORTED", "TASK_COMPLETED", "TASK_COMPLETED", "COMPLETION_UNDONE", "COMPLETION_UNDONE"),
            resultRepository.getRevisionTimeline(DATE).map { it.reason },
        )
    }

    @Test
    fun `moving a scored task transfers historical points and keeps global total`() = runTest {
        importJson(taskJson("ResultBatch00001", GROUP_A))
        executionService.complete(TASK_ID)

        importJson(taskJson("ResultBatch00002", GROUP_B))

        val ledger = database.auditDao().getLedger(TASK_ID)
        assertEquals(listOf("COMPLETED", "GROUP_TRANSFER_OUT", "GROUP_TRANSFER_IN"), ledger.map { it.reason })
        assertEquals(listOf(5, -5, 5), ledger.map { it.delta })
        assertEquals(listOf(GROUP_A, GROUP_A, GROUP_B), ledger.map { it.groupId })
        val result = resultRepository.getDailyResult(DATE)!!
        assertEquals(5, result.global?.totalPoints)
        assertNull(result.groups.singleOrNull { it.groupId == GROUP_A })
        assertEquals(5, result.groups.single { it.groupId == GROUP_B }.points)

        executionService.undoCompletion(TASK_ID)
        assertEquals(GROUP_B, database.auditDao().getLedger(TASK_ID).last().groupId)
        assertEquals(0, resultRepository.getDailyResult(DATE)?.global?.totalPoints)
    }

    @Test
    fun `required flag update recalculates historical result without changing earned points`() = runTest {
        importJson(taskJson("ResultBatch00001", GROUP_A))
        executionService.complete(TASK_ID)

        importJson(taskJson("ResultBatch00004", GROUP_A, required = false))

        val result = resultRepository.getDailyResult(DATE)!!.global!!
        assertEquals(DailyResultStatus.OPTIONAL_ONLY, result.status)
        assertEquals(5, result.totalPoints)
        val last = resultRepository.getRevisionTimeline(DATE).last { it.scope == "GLOBAL" }
        assertEquals(DailyResultStatus.COMPLETED.name, last.oldStatus)
        assertEquals(DailyResultStatus.OPTIONAL_ONLY.name, last.newStatus)
    }

    @Test
    fun `cancellation removes a completed task result while retaining ledger`() = runTest {
        importJson(taskJson("ResultBatch00001", GROUP_A))
        executionService.complete(TASK_ID)
        importJson("""{"v":1,"b":"ResultBatch00003","z":["$TASK_ID"]}""")

        assertNull(resultRepository.getDailyResult(DATE))
        assertEquals(listOf(5), database.auditDao().getLedger(TASK_ID).map { it.delta })
        val last = resultRepository.getRevisionTimeline(DATE).last { it.scope == "GLOBAL" }
        assertEquals(DailyResultStatus.COMPLETED.name, last.oldStatus)
        assertNull(last.newStatus)
    }

    @Test
    fun `foreground reconciliation marks overdue task missed and revises result`() = runTest {
        importJson(taskJson("ResultBatch00001", GROUP_A))
        clock.instantValue = Instant.parse("2026-07-20T10:00:00Z")

        RoomInstanceGenerationService(database, clock, ids).reconcileAll(
            TaskDay.from(LocalDateTime.ofInstant(clock.instant(), clock.zone)),
        )

        assertEquals(DailyResultStatus.INCOMPLETE, resultRepository.getDailyResult(DATE)?.global?.status)
        assertEquals(
            "DEADLINE_RECONCILED",
            resultRepository.getRevisionTimeline(DATE).last { it.scope == "GLOBAL" }.reason,
        )
    }

    private suspend fun importJson(json: String) {
        val preview = importService.preview(encodeDst1ForTest(json))
        importService.import(preview)
    }

    private fun taskJson(batchId: String, groupId: String, required: Boolean = true): String =
        """{"v":1,"b":"$batchId","g":[{"i":"$groupId","n":"Group","cm":"done","im":"missed","t":[{"i":"$TASK_ID","n":"Task","r":${if (required) 1 else 0},"y":"$DATE","p":5}]}]}"""

    private class MutableClock(
        var instantValue: Instant,
        private val zoneValue: ZoneId = ZoneOffset.UTC,
    ) : java.time.Clock() {
        override fun getZone(): ZoneId = zoneValue
        override fun withZone(zone: ZoneId): java.time.Clock = MutableClock(instantValue, zone)
        override fun instant(): Instant = instantValue
    }

    private companion object {
        const val TASK_ID = "ResultTask000001"
        const val GROUP_A = "ResultGroup00001"
        const val GROUP_B = "ResultGroup00002"
        const val DATE = "2026-07-18"
    }
}
