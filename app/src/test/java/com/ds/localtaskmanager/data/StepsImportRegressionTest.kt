package com.ds.localtaskmanager.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ds.localtaskmanager.domain.RecordIdGenerator
import com.ds.localtaskmanager.protocol.Dst1Parser
import com.ds.localtaskmanager.protocol.Dst1ErrorCode
import com.ds.localtaskmanager.protocol.Dst1ValidationException
import com.ds.localtaskmanager.protocol.encodeDst1ForTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StepsImportRegressionTest {
    private lateinit var database: AppDatabase
    private lateinit var service: ImportService
    private val clock = Clock.fixed(Instant.parse("2026-07-18T10:00:00Z"), ZoneOffset.UTC)
    private val sequence = AtomicInteger()
    private val ids = RecordIdGenerator { "ImportStep${sequence.incrementAndGet().toString().padStart(8, '0')}" }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        service = RoomImportService(database, Dst1Parser(), clock, ids)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `import keeps stable ids and resets confirmation from first reordered step`() = runTest {
        import(batch("BatchSteps000001", """[{"i":"step000000000001","n":"第一步","r":1},{"i":"step000000000002","n":"第二步","r":1}]"""))
        val execution = RoomTaskExecutionService(database, clock, ids)
        execution.confirmStep(com.ds.localtaskmanager.domain.execution.TaskInstanceKey("TaskSteps0000001"), "step000000000001")

        import(batch("BatchSteps000002", """[{"i":"step000000000002","n":"第二步","r":1},{"i":"step000000000001","n":"第一步","r":1}]"""))
        val steps = database.instanceDao().getInstanceSteps("TaskSteps0000001")
        assertEquals(listOf("step000000000002", "step000000000001"), steps.map { it.stepId })
        assertTrue(steps.all { it.stepStatus == "PENDING" })
    }

    @Test
    fun `renaming a step preserves confirmed answer while changing its leaf target resets it`() = runTest {
        import(batch("BatchSteps000003", """[{"i":"step000000000001","n":"第一步","r":1,"u":{"k":1,"a":2,"v":1}}]"""))
        val execution = RoomTaskExecutionService(database, clock, ids)
        val key = com.ds.localtaskmanager.domain.execution.TaskInstanceKey("TaskSteps0000001")
        execution.setStepCounter(key, "step000000000001", 1)
        execution.confirmStep(key, "step000000000001")

        import(batch("BatchSteps000004", """[{"i":"step000000000001","n":"改名但目标不变","r":1,"u":{"k":1,"a":2,"v":1}}]"""))
        assertEquals("CONFIRMED", database.instanceDao().getInstanceSteps(key.taskId).single().stepStatus)
        assertEquals("改名但目标不变", database.instanceDao().getInstanceSteps(key.taskId).single().name)

        import(batch("BatchSteps000005", """[{"i":"step000000000001","n":"目标变化","r":1,"u":{"k":1,"a":2,"v":2}}]"""))
        val changed = database.instanceDao().getInstanceSteps(key.taskId).single()
        assertEquals("PENDING", changed.stepStatus)
        assertEquals(null, changed.counterValue)
    }

    @Test
    fun `legacy counter progress moves into supplied deterministic root step`() = runTest {
        import(legacyBatch("BatchSteps000006", """{"k":1,"a":2,"v":2}"""))
        val execution = RoomTaskExecutionService(database, clock, ids)
        val key = com.ds.localtaskmanager.domain.execution.TaskInstanceKey("TaskSteps0000001")
        execution.setStep(key, 0, true)
        execution.setCounter(key, 2)

        import(batch("BatchSteps000007", """[{"i":"step000000000001","n":"旧普通","r":1},{"i":"rTaskSteps000000","n":"旧计数","r":1,"u":{"k":1,"a":2,"v":2}},{"i":"step000000000002","n":"新步骤","r":1}]"""))
        val steps = database.instanceDao().getInstanceSteps(key.taskId)
        assertEquals(2, steps.first { it.stepId == "rTaskSteps000000" }.counterValue)
        assertEquals("PENDING", steps.first { it.stepId == "step000000000001" }.stepStatus)
        assertTrue(steps.all { it.stepStatus == "PENDING" })
    }

    @Test
    fun `exception only import uses stored recurring base and updates that day`() = runTest {
        val taskId = "TaskSteps0000001"
        val base = """{"v":1,"sv":1,"b":"BatchSteps000008","t":[{"i":"$taskId","n":"重复","r":1,"p":1,"u":{"k":5},"x":{"f":1,"s":"2026-07-18","c":1},"s":[{"i":"step000000000001","n":"基础","r":1}]}],"e":[{"i":"$taskId","y":"2026-07-18","u":{"k":5},"s":[{"i":"step000000000001","n":"基础","r":1}]}]}"""
        import(base)
        val exception = """{"v":1,"sv":1,"b":"BatchSteps000009","e":[{"i":"$taskId","y":"2026-07-18","u":{"k":5},"s":[{"i":"step000000000001","n":"当天覆盖","r":1}]}]}"""
        import(exception)
        val instance = database.instanceDao().getInstance(taskId, "2026-07-18")
        assertEquals("STEPS", instance?.executionKind)
        assertEquals("当天覆盖", database.instanceDao().getInstanceSteps(taskId, "2026-07-18").single().name)
    }

    @Test
    fun `exception only import rejects empty steps and requires explicit clear when leaving steps`() = runTest {
        val taskId = "TaskSteps0000001"
        val base = """{"v":1,"b":"BatchSteps000010","t":[{"i":"$taskId","n":"重复","r":1,"u":{"k":5},"x":{"f":1,"s":"2026-07-18","c":2},"s":[{"i":"step000000000001","n":"基础","r":1}]}]}"""
        import(base)

        val missingSteps = """{"v":1,"sv":1,"b":"BatchSteps000011","e":[{"i":"$taskId","y":"2026-07-18","u":{"k":5}}]}"""
        import(missingSteps)
        assertEquals("STEPS", database.instanceDao().getInstance(taskId, "2026-07-18")?.executionKind)

        val emptySteps = """{"v":1,"sv":1,"b":"BatchSteps000014","e":[{"i":"$taskId","y":"2026-07-18","u":{"k":5},"s":[]}]}"""
        val empty = assertThrows(Dst1ValidationException::class.java) { runBlocking { import(emptySteps) } }
        assertEquals(Dst1ErrorCode.INVALID_VALUE, empty.code)

        val cutOutWithoutClear = """{"v":1,"sv":1,"b":"BatchSteps000012","e":[{"i":"$taskId","y":"2026-07-18","u":{"k":1,"a":2,"v":1}}]}"""
        val missingClear = assertThrows(Dst1ValidationException::class.java) { runBlocking { import(cutOutWithoutClear) } }
        assertEquals(Dst1ErrorCode.CONFLICTING_FIELDS, missingClear.code)

        val cutOut = """{"v":1,"sv":1,"b":"BatchSteps000013","e":[{"i":"$taskId","y":"2026-07-18","u":{"k":1,"a":2,"v":1},"s":[]}]}"""
        import(cutOut)
        assertEquals("COUNTER", database.instanceDao().getInstance(taskId, "2026-07-18")?.executionKind)
    }

    private suspend fun import(json: String) {
        val preview = service.preview(encodeDst1ForTest(json))
        service.import(preview)
    }

    private fun batch(batchId: String, steps: String): String = """
        {"v":1,"b":"$batchId","g":[{"i":"GroupSteps000001","n":"步骤","t":[{"i":"TaskSteps0000001","n":"分步骤任务","r":1,"y":"2026-07-18","p":3,"u":{"k":5},"s":$steps}]}]}
    """.trimIndent()

    private fun legacyBatch(batchId: String, execution: String): String = """
        {"v":1,"b":"$batchId","g":[{"i":"GroupSteps000001","n":"步骤","t":[{"i":"TaskSteps0000001","n":"旧任务","r":1,"y":"2026-07-18","p":3,"u":$execution,"s":[{"i":"step000000000001","n":"旧普通","r":1}]}]}]}
    """.trimIndent()
}
