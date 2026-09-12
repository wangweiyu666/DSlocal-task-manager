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
class ConditionalChoiceTest {
    private lateinit var db: AppDatabase
    private lateinit var importer: ImportService
    private lateinit var service: TaskExecutionService
    private val clock = Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC)
    private var sequence = 0
    private val ids = RecordIdGenerator { "ChoiceRecord${++sequence}" }
    private val key = TaskInstanceKey("Task000000000001")
    private val a = "Option0000000001"
    private val b = "Option0000000002"
    private val first = "Step000000000001"
    private val second = "Step000000000002"
    private val third = "Step000000000003"
    private val choice get() = """{"k":7,"o":[{"i":"$a","n":"分支 A","p":0},{"i":"$b","n":"分支 B","p":9}]}"""

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()
        importer = RoomImportService(db, Dst1Parser(), clock, ids)
        service = RoomTaskExecutionService(db, clock, ids)
    }
    @After fun close() = db.close()
    private suspend fun load(body: String, suffix: String = "1") {
        importer.import(importer.preview(encodeDst1ForTest("""{"v":1,"b":"Batch0000000000$suffix","t":[{"i":"${key.taskId}","n":"测试","r":1,"y":"2026-09-12","p":3,$body}]}""")))
    }
    private suspend fun branch(optional: Boolean = false) = load(""""u":{"k":5},"s":[{"i":"$first","n":"路线","r":${if(optional) 0 else 1},"u":$choice},{"i":"$second","n":"进一步选择","r":1,"u":$choice,"c":{"s":"$first","o":"$b"}},{"i":"$third","n":"通知","r":1,"u":{"k":6,"t":"请阅读"},"c":{"s":"$second","o":"$a"}}]""")
    private suspend fun steps() = db.instanceDao().getInstanceSteps(key.taskId)
    private fun rejects(block: suspend () -> Unit) = assertThrows(TaskOperationException::class.java) { runBlocking { block() } }

    @Test fun `notice requires explicit completion and choice restores zero or positive points once`() = runTest {
        load(""""u":{"k":6,"t":"通知正文 😀"}""")
        assertEquals(ExecutionState.Notice("通知正文 😀"), service.getExecutionState(key))
        assertEquals("PENDING", db.instanceDao().getInstance(key.taskId)?.status)
        service.complete(key)
        assertEquals(3, db.instanceDao().getInstance(key.taskId)?.awardedPoints)
        service.undoCompletion(key)
        load(""""u":$choice""", "2")
        assertNull((service.getExecutionState(key) as ExecutionState.Choice).selectedOptionId)
        rejects { service.complete(key) }
        rejects { service.saveChoice(key, "bad") }
        service.saveChoice(key, a)
        assertEquals(a, (RoomTaskExecutionService(db, clock, ids).getExecutionState(key) as ExecutionState.Choice).selectedOptionId)
        service.complete(key)
        assertEquals(3, db.instanceDao().getInstance(key.taskId)?.awardedPoints)
        rejects { service.complete(key) }
        service.undoCompletion(key)
        service.saveChoice(key, b)
        service.saveChoice(key, b)
        service.complete(key)
        assertEquals(12, db.instanceDao().getInstance(key.taskId)?.awardedPoints)
        assertEquals(12, db.auditDao().getLedger(key.taskId).sumOf { it.delta })
        val ledger = db.auditDao().getLedger(key.taskId)
        assertEquals(ledger.size, ledger.map { it.createdAtEpochMillis }.distinct().size)
    }

    @Test fun `cascading branches expand only after confirmation and required branch blocks completion`() = runTest {
        branch()
        assertEquals(listOf(first), steps().applicableSteps().map { it.stepId })
        service.saveStepChoice(key, first, b)
        assertEquals(listOf(first), steps().applicableSteps().map { it.stepId })
        service.confirmStep(key, first)
        rejects { service.saveStepChoice(key, first, a) }
        assertEquals(listOf(first, second), steps().applicableSteps().map { it.stepId })
        assertFalse(service.getCompletionReadiness(key).canComplete)
        rejects { service.complete(key) }
        service.saveStepChoice(key, second, a)
        service.confirmStep(key, second)
        assertEquals(3, steps().applicableSteps().size)
        rejects { service.complete(key) }
        service.confirmStep(key, third)
        service.complete(key)
        assertEquals(12, db.instanceDao().getInstance(key.taskId)?.awardedPoints)
    }

    @Test fun `undo rechoice preserves branch drafts but demands reconfirmation`() = runTest {
        branch()
        service.saveStepChoice(key, first, b); service.confirmStep(key, first)
        service.saveStepChoice(key, second, a); service.confirmStep(key, second); service.confirmStep(key, third)
        service.undoStep(key, first)
        assertTrue(steps().all { it.stepStatus == "PENDING" })
        assertEquals(a, steps()[1].selectedOptionId)
        service.saveStepChoice(key, first, a); service.confirmStep(key, first)
        service.complete(key)
        assertEquals(listOf("CONFIRMED", "NOT_APPLICABLE", "NOT_APPLICABLE"), steps().map { it.stepStatus })
        assertEquals(3, db.instanceDao().getInstance(key.taskId)?.awardedPoints)
        service.undoCompletion(key); service.undoStep(key, first)
        service.saveStepChoice(key, first, b); service.confirmStep(key, first)
        assertEquals(a, steps()[1].selectedOptionId)
        rejects { service.complete(key) }
        service.confirmStep(key, second); service.confirmStep(key, third); service.complete(key)
        assertEquals(12, db.auditDao().getLedger(key.taskId).sumOf { it.delta })
    }

    @Test fun `completion transaction skips optional choice and deactivates required descendants`() = runTest {
        branch(optional = true)
        service.saveStepChoice(key, first, b)
        assertTrue(service.getCompletionReadiness(key).canComplete)
        service.complete(key)
        assertEquals(listOf("SKIPPED", "NOT_APPLICABLE", "NOT_APPLICABLE"), steps().map { it.stepStatus })
        assertEquals(3, db.instanceDao().getInstance(key.taskId)?.awardedPoints)
    }

    @Test fun `configuration update clears removed selections and completed awards stay historical`() = runTest {
        load(""""u":$choice""")
        service.saveChoice(key, b)
        val changed = choice.replace(b, "Option0000000003").replace("\"p\":9", "\"p\":40")
        load(""""u":$changed""", "2")
        assertNull((service.getExecutionState(key) as ExecutionState.Choice).selectedOptionId)
        service.saveChoice(key, a); service.complete(key)
        val before = db.instanceDao().getInstance(key.taskId)
        load(""""u":${choice.replace("\"p\":0", "\"p\":80")}""", "3")
        assertEquals(before, db.instanceDao().getInstance(key.taskId))
        assertEquals(3, db.auditDao().getLedger(key.taskId).sumOf { it.delta })
    }

    @Test fun `backup v5 roundtrip and replace preserve inactive drafts and immutable award`() = runTest {
        branch()
        service.saveStepChoice(key, first, b); service.confirmStep(key, first)
        service.saveStepChoice(key, second, a)
        service.undoStep(key, first)
        service.saveStepChoice(key, first, a); service.confirmStep(key, first); service.complete(key)
        val backup = RoomBackupRepository(db, AppSettingsRepository(ApplicationProvider.getApplicationContext()))
        val payload = backup.snapshot()
        assertEquals(5, payload.schemaVersion)
        val metadata = BackupMetadata(clock.millis(), "test", "UTC", counts = BackupCounts(payload.groups.size,payload.definitions.size,payload.instances.size,payload.ledger.size,payload.actionLogs.size,payload.resultRevisions.size))
        val decoded = DstbCodec.decode(DstbCodec.encode(metadata, payload))
        BackupValidator.validate(decoded)
        backup.replace(decoded.payload)
        assertEquals(payload.instances, backup.snapshot().instances)
        assertEquals(payload.instanceSteps, backup.snapshot().instanceSteps)
        assertEquals(a, steps()[1].selectedOptionId)
        val invalid = payload.copy(instances = payload.instances.map { it.copy(awardedPoints = 999) })
        assertThrows(DstbException::class.java) { BackupValidator.validate(DecodedBackup(metadata, invalid)) }
        service.undoCompletion(key)
        val local = backup.snapshot()
        val merged = backup.previewMerge(payload)
        backup.applyMerge(merged)
        assertEquals(local.instances, backup.snapshot().instances)
        assertEquals(local.instanceSteps, backup.snapshot().instanceSteps)
        assertEquals(local.ledger, backup.snapshot().ledger)
        assertEquals(0, db.auditDao().getLedger(key.taskId).sumOf { it.delta })
    }

    @Test fun `parser rejects illegal references and exception overrides before database writes`() = runTest {
        val prefix = """{"v":1,"b":"Batch00000000001","t":[{"i":"${key.taskId}","n":"分支","r":1,"y":"2026-09-12","u":{"k":5},"s":["""
        val selection = """{"i":"$first","n":"选择","r":1,"u":$choice}"""
        for ((source, option) in listOf(second to a, "Other00000000001" to a, first to "Missing000000001")) {
            val body = prefix + selection + """,{"i":"$second","n":"通知","r":1,"u":{"k":6,"t":"正文"},"c":{"s":"$source","o":"$option"}}]}]}"""
            assertTrue(runCatching { importer.preview(encodeDst1ForTest(body)) }.isFailure)
        }
        assertNull(db.instanceDao().getInstance(key.taskId))
        branch()
        val invalidOverride = """{"v":1,"sv":1,"b":"Batch00000000002","e":[{"i":"${key.taskId}","y":"2026-09-12","s":[{"i":"$third","n":"坏引用","r":1,"c":{"s":"$first","o":"$b"}}]}]}"""
        assertTrue(runCatching { importer.import(importer.preview(encodeDst1ForTest(invalidOverride))) }.isFailure)
        assertEquals(3, steps().size)
    }

    @Test fun `recurrence and date exceptions keep daily selections isolated`() = runTest {
        val json = """{"v":1,"sv":1,"b":"Batch00000000001","t":[{"i":"${key.taskId}","n":"每日单选","r":1,"p":2,"l":null,"u":$choice,"x":{"f":1,"s":"2026-09-12","c":2}}],"e":[{"i":"${key.taskId}","y":"2026-09-13","p":5,"u":${choice.replace("\"p\":9", "\"p\":20")}}]}"""
        importer.import(importer.preview(encodeDst1ForTest(json)))
        val day1 = key.copy(occurrenceKey = "2026-09-12")
        service.saveChoice(day1, b); service.complete(day1)
        val nextClock = Clock.fixed(Instant.parse("2026-09-13T10:00:00Z"), ZoneOffset.UTC)
        com.ds.localtaskmanager.data.recurrence.RoomInstanceGenerationService(db, nextClock, ids).reconcileTask(key.taskId, LocalDate.of(2026,9,13), null)
        val nextService = RoomTaskExecutionService(db, nextClock, ids)
        val day2 = key.copy(occurrenceKey = "2026-09-13")
        assertNull((nextService.getExecutionState(day2) as ExecutionState.Choice).selectedOptionId)
        nextService.saveChoice(day2, b); nextService.complete(day2)
        assertEquals(11, db.instanceDao().getInstance(key.taskId, day1.occurrenceKey)?.awardedPoints)
        assertEquals(25, db.instanceDao().getInstance(key.taskId, day2.occurrenceKey)?.awardedPoints)
    }

    @Test fun `recurring config updates retain valid choices and clear removed choices`() = runTest {
        fun recurring(config: String, batch: Int, extra: String = "") = """{"v":1,${if (extra.isNotEmpty()) "\"sv\":1," else ""}"b":"Batch0000000000$batch","t":[{"i":"${key.taskId}","n":"每日","r":1,"p":2,"l":null,"u":$config,"x":{"f":1,"s":"2026-09-12","c":2}}]$extra}"""
        suspend fun apply(json: String) { importer.import(importer.preview(encodeDst1ForTest(json))) }
        val day = key.copy(occurrenceKey = "2026-09-12")
        apply(recurring(choice, 1)); service.saveChoice(day, b)
        val renamed = choice.replace("分支 B", "改名")
        apply(recurring(renamed, 2))
        assertEquals(b, (service.getExecutionState(day) as ExecutionState.Choice).selectedOptionId)
        val patch = """, "e":[{"i":"${key.taskId}","y":"2026-09-12","u":${renamed.replace("\"p\":9", "\"p\":40")}}]"""
        apply(recurring(renamed, 3, patch))
        assertEquals(b, (service.getExecutionState(day) as ExecutionState.Choice).selectedOptionId)
        val cleared = """, "e":[{"i":"${key.taskId}","y":"2026-09-12"}]"""
        apply(recurring(renamed.replace(b, "Option0000000003"), 4, cleared))
        assertNull((service.getExecutionState(day) as ExecutionState.Choice).selectedOptionId)
        rejects { service.complete(day) }
    }
}
