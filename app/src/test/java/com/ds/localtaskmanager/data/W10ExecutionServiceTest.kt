package com.ds.localtaskmanager.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ds.localtaskmanager.domain.RecordIdGenerator
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.execution.CounterAction
import com.ds.localtaskmanager.domain.execution.ExecutionState
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import com.ds.localtaskmanager.domain.execution.TaskOperationCode
import com.ds.localtaskmanager.protocol.Dst1Parser
import com.ds.localtaskmanager.protocol.encodeDst1ForTest
import com.ds.localtaskmanager.ui.execution.MonotonicClock
import com.ds.localtaskmanager.ui.execution.TimerSessionController
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
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
class W10ExecutionServiceTest {
    private lateinit var database: AppDatabase
    private lateinit var importService: ImportService
    private lateinit var executionService: TaskExecutionService
    private val clock = Clock.fixed(Instant.parse("2026-07-18T10:00:00Z"), ZoneOffset.UTC)
    private val sequence = AtomicInteger()
    private val ids = RecordIdGenerator { "W10Rec${sequence.incrementAndGet().toString().padStart(10, '0')}" }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        importService = RoomImportService(database, Dst1Parser(), clock, ids)
        executionService = RoomTaskExecutionService(database, clock, ids)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `import stores counter definition and instance snapshot`() = runTest {
        importJson(counterJson("CounterBatch0001", target = 3))

        val definition = database.definitionDao().getDefinition(COUNTER_TASK)!!
        val instance = database.instanceDao().getInstance(COUNTER_TASK)!!
        assertEquals("COUNTER", definition.executionKind)
        assertEquals(2, definition.executionAction)
        assertEquals(3, definition.executionTarget)
        assertEquals("COUNTER", instance.executionKind)
        assertEquals(ExecutionState.Counter(0, 3, CounterAction.CLICK), executionService.getExecutionState(COUNTER_KEY))
    }

    @Test
    fun `counter target gates explicit completion and undo preserves progress`() = runTest {
        importJson(counterJson("CounterBatch0001", target = 3))

        val early = assertOperation(TaskOperationCode.EXECUTION_TARGET_NOT_REACHED) {
            executionService.complete(COUNTER_KEY)
        }
        assertEquals(TaskOperationCode.EXECUTION_TARGET_NOT_REACHED, early.code)
        assertEquals(false, executionService.getCompletionReadiness(COUNTER_KEY).canComplete)
        executionService.setCounter(COUNTER_KEY, 3)
        assertEquals(true, executionService.getCompletionReadiness(COUNTER_KEY).canComplete)
        assertEquals(TaskStatus.PENDING.name, database.instanceDao().getInstance(COUNTER_TASK)?.status)
        executionService.complete(COUNTER_KEY)
        executionService.undoCompletion(COUNTER_KEY)

        assertEquals(3, (executionService.getExecutionState(COUNTER_KEY) as ExecutionState.Counter).value)
        assertEquals(listOf(7, -7), database.auditDao().getLedger(COUNTER_TASK).map { it.delta })
    }

    @Test
    fun `counter rejects values outside the imported target`() = runTest {
        importJson(counterJson("CounterBatch0001", target = 3))

        assertOperation(TaskOperationCode.COUNTER_OUT_OF_RANGE) {
            executionService.setCounter(COUNTER_KEY, -1)
        }
        assertOperation(TaskOperationCode.COUNTER_OUT_OF_RANGE) {
            executionService.setCounter(COUNTER_KEY, 4)
        }
        assertNull(database.executionDao().getProgress(COUNTER_TASK, "once"))
    }

    @Test
    fun `counter configuration update resets mutable progress and archives summary`() = runTest {
        importJson(counterJson("CounterBatch0001", target = 3))
        executionService.setCounter(COUNTER_KEY, 2)

        val preview = importService.preview(encodeDst1ForTest(counterJson("CounterBatch0002", target = 4)))
        assertEquals(true, ImportChangeType.EXECUTION_RESET in preview.taskChanges.single().types)
        importService.import(preview)

        assertEquals(0, (executionService.getExecutionState(COUNTER_KEY) as ExecutionState.Counter).value)
        val reset = database.auditDao().getLogs(COUNTER_TASK).single { it.action == "EXECUTION_RESET" }
        assertEquals(true, reset.detail!!.contains("\"counterValue\":2"))
    }

    @Test
    fun `finished instance keeps its original execution snapshot on definition update`() = runTest {
        val first = """{"v":1,"b":"MissedBatch00001","t":[{"i":"$COUNTER_TASK","n":"Count","r":1,"y":"2026-07-17","u":{"k":1,"a":2,"v":3}}]}"""
        val second = """{"v":1,"b":"MissedBatch00002","t":[{"i":"$COUNTER_TASK","n":"Count","r":1,"y":"2026-07-17","u":{"k":1,"a":2,"v":4}}]}"""
        importJson(first)
        assertEquals(TaskStatus.MISSED.name, database.instanceDao().getInstance(COUNTER_TASK)?.status)

        importJson(second)

        assertEquals(4, database.definitionDao().getDefinition(COUNTER_TASK)?.executionTarget)
        assertEquals(3, database.instanceDao().getInstance(COUNTER_TASK)?.executionTarget)
        assertEquals(false, database.auditDao().getLogs(COUNTER_TASK).any { it.action == "EXECUTION_RESET" })
    }

    @Test
    fun `timer controller accumulates monotonic foreground segments and clamps target`() = runTest {
        importJson(timerJson())
        val monotonic = FakeMonotonicClock()
        val controller = TimerSessionController(executionService, monotonic)

        controller.start(TIMER_KEY)
        monotonic.now = 1_500
        assertEquals(1_500, controller.pause()!!.elapsedMillis)
        monotonic.now = 10_000
        controller.start(TIMER_KEY)
        monotonic.now = 12_000
        assertEquals(3_000, controller.onForegroundLost()!!.elapsedMillis)
        assertEquals(false, controller.isRunning)
        val reconstructed = RoomTaskExecutionService(database, clock, ids)
        assertEquals(3_000, (reconstructed.getExecutionState(TIMER_KEY) as ExecutionState.Timer).elapsedMillis)

        executionService.complete(TIMER_KEY)
        assertEquals(TaskStatus.COMPLETED.name, database.instanceDao().getInstance(TIMER_TASK)?.status)
    }

    @Test
    fun `information draft uses code points locks on completion and survives undo`() = runTest {
        importJson(informationJson("InfoBatch0000001", "Tell me"))
        val content = "  line one\n😀  "

        val saved = executionService.saveInformationDraft(INFO_KEY, content)
        assertEquals("line one\n😀", saved.content)
        executionService.complete(INFO_KEY)
        assertOperation(TaskOperationCode.INSTANCE_NOT_PENDING) {
            executionService.saveInformationDraft(INFO_KEY, "changed")
        }
        executionService.undoCompletion(INFO_KEY)

        val restored = executionService.getExecutionState(INFO_KEY) as ExecutionState.Information
        assertEquals("line one\n😀", restored.content)
        assertEquals(true, restored.submittedAtEpochMillis != null)
        assertEquals(false, database.auditDao().getLogs(INFO_TASK).any { it.detail?.contains("line one") == true })
    }

    @Test
    fun `information requirement update preserves draft and appears in preview`() = runTest {
        importJson(informationJson("InfoBatch0000001", "Tell me"))
        executionService.saveInformationDraft(INFO_KEY, "preserve me")

        val preview = importService.preview(
            encodeDst1ForTest(informationJson("InfoBatch0000002", "Tell me more")),
        )
        assertEquals(true, ImportChangeType.INFORMATION_REVIEW_REQUIRED in preview.taskChanges.single().types)
        importService.import(preview)

        assertEquals(
            "preserve me",
            (executionService.getExecutionState(INFO_KEY) as ExecutionState.Information).content,
        )
    }

    @Test
    fun `information enforces empty and 2000 code point limits`() = runTest {
        importJson(informationJson("InfoBatch0000001", "Tell me"))

        assertOperation(TaskOperationCode.INFORMATION_EMPTY) {
            executionService.saveInformationDraft(INFO_KEY, " \n ")
        }
        executionService.saveInformationDraft(INFO_KEY, "😀".repeat(2_000))
        assertOperation(TaskOperationCode.INFORMATION_TOO_LONG) {
            executionService.saveInformationDraft(INFO_KEY, "😀".repeat(2_001))
        }
    }

    private suspend fun importJson(json: String) {
        val preview = importService.preview(encodeDst1ForTest(json))
        importService.import(preview)
    }

    private fun counterJson(batchId: String, target: Int): String =
        """{"v":1,"b":"$batchId","t":[{"i":"$COUNTER_TASK","n":"Count","r":1,"y":"2026-07-18","p":7,"u":{"k":1,"a":2,"v":$target}}]}"""

    private fun timerJson(): String =
        """{"v":1,"b":"TimerBatch000001","t":[{"i":"$TIMER_TASK","n":"Time","r":1,"y":"2026-07-18","u":{"k":2,"v":3}}]}"""

    private fun informationJson(batchId: String, requirement: String): String =
        """{"v":1,"b":"$batchId","t":[{"i":"$INFO_TASK","n":"Inform","r":1,"d":"$requirement","y":"2026-07-18","u":{"k":3}}]}"""

    private fun assertOperation(
        code: TaskOperationCode,
        block: suspend () -> Unit,
    ): TaskOperationException {
        val error = assertThrows(TaskOperationException::class.java) {
            runBlocking { block() }
        }
        assertEquals(code, error.code)
        return error
    }

    private class FakeMonotonicClock(var now: Long = 0) : MonotonicClock {
        override fun elapsedRealtimeMillis(): Long = now
    }

    private companion object {
        const val COUNTER_TASK = "CounterTask00001"
        const val TIMER_TASK = "TimerTask0000001"
        const val INFO_TASK = "InformTask000001"
        val COUNTER_KEY = TaskInstanceKey(COUNTER_TASK)
        val TIMER_KEY = TaskInstanceKey(TIMER_TASK)
        val INFO_KEY = TaskInstanceKey(INFO_TASK)
    }
}
