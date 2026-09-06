package com.ds.localtaskmanager.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ds.localtaskmanager.domain.RecordIdGenerator
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import com.ds.localtaskmanager.data.recurrence.RoomInstanceGenerationService
import com.ds.localtaskmanager.protocol.Dst1Parser
import com.ds.localtaskmanager.protocol.encodeDst1ForTest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StepsExecutionRegressionTest {
    private lateinit var database: AppDatabase
    private lateinit var importService: ImportService
    private lateinit var execution: TaskExecutionService
    private val clock = Clock.fixed(Instant.parse("2026-07-18T10:00:00Z"), ZoneOffset.UTC)
    private val ids = RecordIdGenerator { "StepRecord${sequence.incrementAndGet().toString().padStart(8, '0')}" }
    private val sequence = AtomicInteger()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        importService = RoomImportService(database, Dst1Parser(), clock, ids)
        execution = RoomTaskExecutionService(database, clock, ids)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `mixed steps enforce order target and final optional skip with one ledger`() = runTest {
        importMixed()
        val key = TaskInstanceKey(TASK_ID)
        assertThrows(TaskOperationException::class.java) { runBlocking { execution.confirmStep(key, TIMER_ID) } }
        assertThrows(TaskOperationException::class.java) { runBlocking { execution.confirmStep(key, COUNTER_ID) } }
        execution.setStepCounter(key, COUNTER_ID, 2)
        assertThrows(TaskOperationException::class.java) { runBlocking { execution.confirmStep(key, COUNTER_ID) } }
        execution.setStepCounter(key, COUNTER_ID, 3)
        execution.confirmStep(key, COUNTER_ID)
        execution.setStepTimer(key, TIMER_ID, 2_000)
        execution.confirmStep(key, TIMER_ID)
        execution.saveStepInformation(key, INFO_ID, "已完成")
        execution.confirmStep(key, INFO_ID)
        execution.saveStepMood(key, MOOD_ID, 4, "不错")
        execution.confirmStep(key, MOOD_ID)
        execution.confirmStep(key, NORMAL_ID)
        execution.saveStepInformation(key, "O000000000000001", "草稿答案")
        execution.complete(key)

        val steps = database.instanceDao().getInstanceSteps(TASK_ID)
        assertEquals(listOf("CONFIRMED", "CONFIRMED", "CONFIRMED", "CONFIRMED", "CONFIRMED", "SKIPPED"), steps.map { it.stepStatus })
        assertEquals(1, database.auditDao().getLedger(TASK_ID).count { it.delta == 7 })
        assertEquals(TaskStatus.COMPLETED.name, database.instanceDao().getInstance(TASK_ID)?.status)
        assertEquals("草稿答案", steps.last().informationContent)
    }

    @Test
    fun `undo earlier step resets later confirmations but preserves answers and overall undo preserves result`() = runTest {
        importMixed()
        val key = TaskInstanceKey(TASK_ID)
        execution.setStepCounter(key, COUNTER_ID, 3)
        execution.confirmStep(key, COUNTER_ID)
        execution.setStepTimer(key, TIMER_ID, 2_000)
        execution.confirmStep(key, TIMER_ID)
        execution.undoStep(key, COUNTER_ID)
        var steps = database.instanceDao().getInstanceSteps(TASK_ID)
        assertEquals(listOf("PENDING", "PENDING"), steps.take(2).map { it.stepStatus })
        assertEquals(3, steps.first().counterValue)
        assertEquals(2_000L, steps[1].elapsedMillis)
        execution.confirmStep(key, COUNTER_ID)
        execution.confirmStep(key, TIMER_ID)
        execution.saveStepInformation(key, INFO_ID, "信息")
        execution.confirmStep(key, INFO_ID)
        execution.saveStepMood(key, MOOD_ID, 5, "很好")
        execution.confirmStep(key, MOOD_ID)
        execution.confirmStep(key, NORMAL_ID)
        execution.complete(key)
        execution.undoCompletion(key)
        steps = database.instanceDao().getInstanceSteps(TASK_ID)
        assertEquals(TaskStatus.PENDING.name, database.instanceDao().getInstance(TASK_ID)?.status)
        assertTrue(steps.take(5).all { it.stepStatus == "CONFIRMED" })
        assertEquals(3, steps.first().counterValue)
        assertEquals(1, database.auditDao().getLedger(TASK_ID).count { it.delta == 7 })
    }

    @Test
    fun `malformed confirmed answer cannot bypass target and occurrences stay isolated`() = runTest {
        importMixed()
        val key = TaskInstanceKey(TASK_ID)
        execution.setStepCounter(key, COUNTER_ID, 3)
        execution.confirmStep(key, COUNTER_ID)
        execution.setStepTimer(key, TIMER_ID, 2_000)
        execution.confirmStep(key, TIMER_ID)
        execution.saveStepInformation(key, INFO_ID, "信息")
        execution.confirmStep(key, INFO_ID)
        execution.saveStepMood(key, MOOD_ID, 4, "不错")
        execution.confirmStep(key, MOOD_ID)
        execution.confirmStep(key, NORMAL_ID)
        val original = database.instanceDao().getInstanceSteps(TASK_ID).first()
        database.instanceDao().insertInstanceSteps(listOf(original.copy(completed = true, stepStatus = "CONFIRMED", counterValue = 1)))
        assertThrows(TaskOperationException::class.java) { runBlocking { execution.complete(key) } }
        assertEquals(TaskStatus.PENDING.name, database.instanceDao().getInstance(TASK_ID)?.status)
        assertEquals(0, database.auditDao().getLedger(TASK_ID).size)

        val recurringTask = "R000000000000001"
        val batch = recurringJson(recurringTask)
        importService.import(importService.preview(encodeDst1ForTest(batch)))
        val firstBeforeGeneration = TaskInstanceKey(recurringTask, "2026-07-18")
        execution.setStepCounter(firstBeforeGeneration, COUNTER_ID, 2)
        val nextClock = Clock.fixed(Instant.parse("2026-07-19T10:00:00Z"), ZoneOffset.UTC)
        RoomInstanceGenerationService(database, nextClock, ids).reconcileTask(
            recurringTask,
            LocalDate.of(2026, 7, 19),
            null,
        )
        val instances = database.instanceDao().getInstancesForTasks(listOf(recurringTask)).filter { it.occurrenceKey != "once" }.sortedBy { it.occurrenceKey }
        assertEquals(listOf("2026-07-18", "2026-07-19"), instances.map { it.occurrenceKey })
        assertTrue(instances.all { it.executionKind == "STEPS" })
        assertTrue(instances[0].status == TaskStatus.PENDING.name || instances[0].status == TaskStatus.MISSED.name)
        assertEquals(TaskStatus.PENDING.name, instances[1].status)
        val first = TaskInstanceKey(recurringTask, instances[0].occurrenceKey)
        val second = TaskInstanceKey(recurringTask, instances[1].occurrenceKey)
        RoomTaskExecutionService(database, nextClock, ids).setStepCounter(second, COUNTER_ID, 3)
        assertEquals(2, database.instanceDao().getInstanceSteps(recurringTask, first.occurrenceKey).first().counterValue)
        assertEquals(3, database.instanceDao().getInstanceSteps(recurringTask, second.occurrenceKey).first().counterValue)
        assertFalse(database.instanceDao().getInstanceSteps(recurringTask, first.occurrenceKey).first().completed)
        assertFalse(database.instanceDao().getInstanceSteps(recurringTask, second.occurrenceKey).first().completed)
    }

    private suspend fun importMixed() {
        importService.import(importService.preview(encodeDst1ForTest(mixedJson(TASK_ID))))
    }

    private fun mixedJson(taskId: String) = """{"v":1,"b":"B000000000000001","t":[{"i":"$taskId","n":"混合步骤","r":1,"y":"2026-07-18","p":7,"u":{"k":5},"s":[{"i":"$COUNTER_ID","n":"计数","r":1,"u":{"k":1,"a":2,"v":3}},{"i":"$TIMER_ID","n":"计时","r":1,"u":{"k":2,"v":2}},{"i":"$INFO_ID","n":"告知","r":1,"u":{"k":3}},{"i":"$MOOD_ID","n":"心情","r":1,"u":{"k":4}},{"i":"$NORMAL_ID","n":"直接","r":1},{"i":"O000000000000001","n":"选做","r":0,"u":{"k":3}}]}]}"""

    private fun recurringJson(taskId: String) = """{"v":1,"b":"D000000000000001","t":[{"i":"$taskId","n":"重复步骤","r":1,"p":1,"l":null,"u":{"k":5},"x":{"f":1,"s":"2026-07-18","c":2},"s":[{"i":"$COUNTER_ID","n":"计数","r":1,"u":{"k":1,"a":2,"v":3}}]}]}"""

    private companion object {
        const val TASK_ID = "K000000000000001"
        const val COUNTER_ID = "C000000000000001"
        const val TIMER_ID = "T000000000000001"
        const val INFO_ID = "I000000000000001"
        const val MOOD_ID = "M000000000000001"
        const val NORMAL_ID = "N000000000000001"
        init { require(listOf(TASK_ID, COUNTER_ID, TIMER_ID, INFO_ID, MOOD_ID, NORMAL_ID, "O000000000000001", "R000000000000001", "B000000000000001", "D000000000000001").all { it.length == 16 }) }
    }
}
