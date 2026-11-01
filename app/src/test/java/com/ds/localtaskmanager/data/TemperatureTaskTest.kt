package com.ds.localtaskmanager.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ds.localtaskmanager.backup.*
import com.ds.localtaskmanager.domain.RecordIdGenerator
import com.ds.localtaskmanager.domain.execution.*
import com.ds.localtaskmanager.protocol.*
import com.ds.localtaskmanager.settings.AppSettingsRepository
import java.time.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TemperatureTaskTest {
    private lateinit var db: AppDatabase
    private lateinit var importer: ImportService
    private lateinit var service: TaskExecutionService
    private val clock = Clock.fixed(Instant.parse("2026-10-31T10:00:00Z"), ZoneOffset.UTC)
    private var sequence = 0
    private val ids = RecordIdGenerator { "TemperatureRecord${++sequence}" }
    private val key = TaskInstanceKey("Task000000000001")
    private val first = "Step000000000001"
    private val second = "Step000000000002"

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()
        importer = RoomImportService(db, Dst1Parser(), clock, ids)
        service = RoomTaskExecutionService(db, clock, ids)
    }
    @After fun close() = db.close()
    private suspend fun load(body: String = "\"u\":{\"k\":8}", suffix: String = "1") {
        importer.import(importer.preview(encodeDst1ForTest("""{"v":1,"b":"Batch0000000000$suffix","t":[{"i":"${key.taskId}","n":"体温询问","r":1,"y":"2026-10-31","l":null,"p":3,$body}]}""")))
    }
    private fun rejects(block: suspend () -> Unit) = assertThrows(TaskOperationException::class.java) { runBlocking { block() } }

    @Test fun `temperature requires selection validates bounds restores drafts and locks completion`() = runTest {
        load()
        assertEquals(ExecutionState.Temperature(null, null), service.getExecutionState(key))
        assertFalse(service.getCompletionReadiness(key).canComplete)
        rejects { service.complete(key) }
        rejects { service.saveTemperatureDraft(key, 349) }
        rejects { service.saveTemperatureDraft(key, 421) }
        for (value in listOf(350, 365, 420)) {
            service.saveTemperatureDraft(key, value)
            assertEquals(value, (RoomTaskExecutionService(db, clock, ids).getExecutionState(key) as ExecutionState.Temperature).temperatureTenthsC)
        }
        service.complete(key)
        val completed = db.instanceDao().getInstance(key.taskId)!!
        assertEquals(completed.completedAtEpochMillis, db.executionDao().getTemperature(key.taskId, "once")?.submittedAtEpochMillis)
        assertEquals(3, completed.awardedPoints)
        rejects { service.saveTemperatureDraft(key, 365) }
        rejects { service.complete(key) }
        service.undoCompletion(key)
        assertEquals(ExecutionState.Temperature(420, null), service.getExecutionState(key))
        service.saveTemperatureDraft(key, 365)
        service.complete(key)
        assertTrue(db.instanceDao().getInstance(key.taskId)!!.completedAtEpochMillis!! > completed.completedAtEpochMillis!!)
        assertEquals(3, db.auditDao().getLedger(key.taskId).sumOf { it.delta })
    }

    @Test fun `temperature type change clears pending draft but preserves completed snapshot`() = runTest {
        load(); service.saveTemperatureDraft(key, 365)
        load("\"u\":{\"k\":4}", "2")
        assertNull(db.executionDao().getTemperature(key.taskId, "once"))
        rejects { service.saveTemperatureDraft(key, 365) }
        load(suffix = "3"); service.saveTemperatureDraft(key, 370); service.complete(key)
        load("\"u\":{\"k\":4}", "4")
        assertEquals("TEMPERATURE", db.instanceDao().getInstance(key.taskId)?.executionKind)
        assertEquals(370, db.executionDao().getTemperature(key.taskId, "once")?.temperatureTenthsC)
    }

    @Test fun `temperature steps enforce confirmation order and preserve values on undo`() = runTest {
        load(""""u":{"k":5},"s":[{"i":"$first","n":"测温","r":1,"u":{"k":8}},{"i":"$second","n":"选做","r":0,"u":{"k":8}}]""")
        rejects { service.confirmStep(key, first) }
        rejects { service.saveStepTemperature(key, second, 365) }
        service.saveStepTemperature(key, first, 365); service.confirmStep(key, first)
        service.saveStepTemperature(key, second, 370); service.skipStep(key, 1)
        service.complete(key)
        assertEquals(listOf("CONFIRMED", "SKIPPED"), db.instanceDao().getInstanceSteps(key.taskId).map { it.stepStatus })
        service.undoCompletion(key); service.undoStep(key, first)
        assertEquals(365, db.instanceDao().getInstanceSteps(key.taskId)[0].temperatureTenthsC)
        assertFalse(service.getCompletionReadiness(key).canComplete)
    }

    @Test fun `temperature conditional steps become not applicable on another branch`() = runTest {
        load(""""u":{"k":5},"s":[{"i":"$first","n":"选择","r":1,"u":{"k":7,"o":[{"i":"Option0000000001","n":"测量","p":0},{"i":"Option0000000002","n":"无需","p":0}]}},{"i":"$second","n":"体温","r":1,"u":{"k":8},"c":{"s":"$first","o":"Option0000000001"}}]""")
        service.saveStepChoice(key, first, "Option0000000002"); service.confirmStep(key, first); service.complete(key)
        assertEquals("NOT_APPLICABLE", db.instanceDao().getInstanceSteps(key.taskId)[1].stepStatus)
    }

    @Test fun `temperature daily instances and exception types stay isolated`() = runTest {
        val json = """{"v":1,"sv":1,"b":"Batch00000000001","t":[{"i":"${key.taskId}","n":"每日体温","r":1,"l":null,"u":{"k":8},"x":{"f":1,"s":"2026-10-31","c":2}}],"e":[{"i":"${key.taskId}","y":"2026-11-01","u":{"k":4}}]}"""
        importer.import(importer.preview(encodeDst1ForTest(json)))
        val day1 = key.copy(occurrenceKey = "2026-10-31")
        service.saveTemperatureDraft(day1, 365); service.complete(day1)
        val nextClock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC)
        com.ds.localtaskmanager.data.recurrence.RoomInstanceGenerationService(db, nextClock, ids).reconcileTask(key.taskId, LocalDate.of(2026, 11, 1), null)
        val day2 = key.copy(occurrenceKey = "2026-11-01")
        assertTrue(RoomTaskExecutionService(db, nextClock, ids).getExecutionState(day2) is ExecutionState.Mood)
        assertNull(db.executionDao().getTemperature(day2.taskId, day2.occurrenceKey))
        assertEquals(365, db.executionDao().getTemperature(day1.taskId, day1.occurrenceKey)?.temperatureTenthsC)
    }

    @Test fun `temperature root converts to stable step and its answer survives backup`() = runTest {
        load(); service.saveTemperatureDraft(key, 365)
        val root = "r" + key.taskId.take(12) + "000"
        load(""""u":{"k":5},"s":[{"i":"$root","n":"原体温","r":1,"u":{"k":8}},{"i":"$second","n":"通知","r":1,"u":{"k":6,"t":"确认"}}]""", "2")
        assertNull(db.executionDao().getTemperature(key.taskId, "once"))
        assertEquals(365, db.instanceDao().getInstanceSteps(key.taskId)[0].temperatureTenthsC)
        service.confirmStep(key, root); service.confirmStep(key, second); service.complete(key)
        val backup = RoomBackupRepository(db, AppSettingsRepository(ApplicationProvider.getApplicationContext()))
        val payload = backup.snapshot()
        val metadata = BackupMetadata(clock.millis() + 100, "test", "UTC", counts = BackupCounts(payload.groups.size, payload.definitions.size, payload.instances.size, payload.ledger.size, payload.actionLogs.size, payload.resultRevisions.size))
        val decoded = DstbCodec.decode(DstbCodec.encode(metadata, payload))
        BackupValidator.validate(decoded); backup.replace(decoded.payload)
        assertEquals(payload.instanceSteps, backup.snapshot().instanceSteps)
        assertEquals(365, backup.snapshot().instanceSteps[0].temperatureTenthsC)
    }

    @Test fun `temperature backup round trip preserves submitted snapshot and validates answers`() = runTest {
        load(); service.saveTemperatureDraft(key, 365); service.complete(key)
        val backup = RoomBackupRepository(db, AppSettingsRepository(ApplicationProvider.getApplicationContext()))
        val payload = backup.snapshot()
        val metadata = BackupMetadata(clock.millis() + 100, "test", "UTC", counts = BackupCounts(payload.groups.size, payload.definitions.size, payload.instances.size, payload.ledger.size, payload.actionLogs.size, payload.resultRevisions.size))
        val decoded = DstbCodec.decode(DstbCodec.encode(metadata, payload))
        BackupValidator.validate(decoded); backup.replace(decoded.payload)
        assertEquals(payload.temperatures, backup.snapshot().temperatures)
        val invalid = payload.copy(temperatures = payload.temperatures.map { it.copy(temperatureTenthsC = 349) })
        assertThrows(DstbException::class.java) { BackupValidator.validate(DecodedBackup(metadata, invalid)) }
        service.undoCompletion(key); service.saveTemperatureDraft(key, 370)
        val merged = backup.previewMerge(payload)
        backup.applyMerge(merged)
        assertEquals(null, db.executionDao().getTemperature(key.taskId, "once")?.submittedAtEpochMillis)
        assertEquals(370, db.executionDao().getTemperature(key.taskId, "once")?.temperatureTenthsC)
    }
}
