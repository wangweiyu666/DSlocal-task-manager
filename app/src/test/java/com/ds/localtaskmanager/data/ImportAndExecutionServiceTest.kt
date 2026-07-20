package com.ds.localtaskmanager.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ds.localtaskmanager.domain.RecordIdGenerator
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.protocol.Dst1Parser
import com.ds.localtaskmanager.protocol.encodeDst1ForTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImportAndExecutionServiceTest {
    private lateinit var database: AppDatabase
    private lateinit var importService: ImportService
    private lateinit var executionService: TaskExecutionService
    private val clock = Clock.fixed(Instant.parse("2026-07-18T10:00:00Z"), ZoneOffset.UTC)
    private val sequence = AtomicInteger()
    private val ids = RecordIdGenerator { "Record${sequence.incrementAndGet().toString().padStart(10, '0')}" }

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
    fun `preview and atomic import create definition instance group steps and batch`() = runTest {
        val encoded = resource("valid/minimal-step.dst1").trim()

        val preview = importService.preview(encoded)
        importService.import(preview)

        val definitionDao = database.definitionDao()
        val instanceDao = database.instanceDao()
        val profileDao = database.profileDao()
        assertEquals(setOf(ImportChangeType.NEW), preview.taskChanges.single().types)
        assertEquals("Daily", definitionDao.getGroups(listOf("GroupId123456789")).single().name)
        assertEquals("Drink water", definitionDao.getDefinition("TaskId1234567890")?.name)
        assertEquals(TaskStatus.PENDING.name, instanceDao.getInstance("TaskId1234567890")?.status)
        assertEquals("Pour water", instanceDao.getInstanceSteps("TaskId1234567890").single().name)
        assertEquals(true, profileDao.hasBatch("BatchId123456789"))
    }

    @Test
    fun `same batch cannot be imported twice`() = runTest {
        val preview = importService.preview(resource("valid/minimal-step.dst1").trim())
        importService.import(preview)

        assertThrows(DuplicateBatchException::class.java) {
            kotlinx.coroutines.runBlocking { importService.preview(resource("valid/minimal-step.dst1").trim()) }
        }
    }

    @Test
    fun `required steps gate completion and undo writes compensating ledger`() = runTest {
        importService.import(importService.preview(resource("valid/minimal-step.dst1").trim()))

        assertThrows(TaskOperationException::class.java) {
            kotlinx.coroutines.runBlocking { executionService.complete("TaskId1234567890") }
        }
        executionService.setStep("TaskId1234567890", 0, true)
        executionService.complete("TaskId1234567890")
        executionService.undoCompletion("TaskId1234567890")

        assertEquals(TaskStatus.PENDING.name, database.instanceDao().getInstance("TaskId1234567890")?.status)
        assertEquals(listOf(5, -5), database.auditDao().getLedger("TaskId1234567890").map { it.delta })
        assertEquals(
            listOf("COMPLETED", "COMPLETION_UNDONE"),
            database.auditDao().getLogs("TaskId1234567890").map { it.action }.filter { it in setOf("COMPLETED", "COMPLETION_UNDONE") },
        )
    }

    @Test
    fun `moving task migrates old points with compensating ledger`() = runTest {
        importService.import(importService.preview(resource("valid/minimal-step.dst1").trim()))
        executionService.setStep("TaskId1234567890", 0, true)
        executionService.complete("TaskId1234567890")

        val moved = """{"v":1,"b":"SecondBatch12345","g":[{"i":"SecondGroup12345","n":"Health","t":[{"i":"TaskId1234567890","n":"Drink water","r":1,"y":"2026-07-18","p":5}]}]}"""
        val preview = importService.preview(encodeDst1ForTest(moved))
        importService.import(preview)

        val ledger = database.auditDao().getLedger("TaskId1234567890")
        assertEquals(
            listOf("GroupId123456789", "GroupId123456789", "SecondGroup12345"),
            ledger.map { it.groupId },
        )
        assertEquals(listOf(5, -5, 5), ledger.map { it.delta })
        assertEquals(true, ImportChangeType.MOVED in preview.taskChanges.single().types)
    }

    @Test
    fun `dom cancellation keeps a completed instance completed and scored`() = runTest {
        importService.import(importService.preview(resource("valid/minimal-step.dst1").trim()))
        executionService.setStep("TaskId1234567890", 0, true)
        executionService.complete("TaskId1234567890")
        val cancellation = """{"v":1,"b":"CancelBatch12345","z":["TaskId1234567890"]}"""

        importService.import(importService.preview(encodeDst1ForTest(cancellation)))

        assertEquals(TaskStatus.COMPLETED.name, database.instanceDao().getInstance("TaskId1234567890")?.status)
        assertEquals(listOf(5), database.auditDao().getLedger("TaskId1234567890").map { it.delta })
        assertEquals(true, database.definitionDao().getDefinition("TaskId1234567890")?.cancelled)
    }

    @Test
    fun `transaction rolls back every write when a later log insert fails`() = runTest {
        val duplicateId = RecordIdGenerator { "SameRecordId0001" }
        val failingService = RoomImportService(database, Dst1Parser(), clock, duplicateId)
        val json = """{"v":1,"b":"AtomicBatch12345","t":[{"i":"FirstTask1234567","n":"First","r":1},{"i":"SecondTask123456","n":"Second","r":1}]}"""
        val preview = failingService.preview(encodeDst1ForTest(json))

        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking { failingService.import(preview) }
        }

        assertFalse(database.profileDao().hasBatch("AtomicBatch12345"))
        assertEquals(emptyList<TaskDefinitionEntity>(), database.definitionDao().getDefinitions(listOf("FirstTask1234567", "SecondTask123456")))
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader?.getResource(path)) { "Missing resource $path" }.readText()
}
