package com.ds.localtaskmanager.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ds.localtaskmanager.data.AppDatabase
import com.ds.localtaskmanager.data.InstanceStepEntity
import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.settings.AppSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StepsBackupRegressionTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomBackupRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomBackupRepository(database, AppSettingsRepository(context))
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `payload codec and Room replace preserve mixed step snapshots across instances`() = runTest {
        val payload = mixedPayload()
        BackupValidator.validate(decoded(payload))
        repository.replace(payload)
        val exported = repository.snapshot()
        val restored = DstbCodec.decode(DstbCodec.encode(metadata(exported), exported)).payload
        repository.replace(restored)
        val again = repository.snapshot()
        assertEquals(exported.definitions, again.definitions)
        assertEquals(exported.definitionSteps, again.definitionSteps)
        assertEquals(exported.instances, again.instances)
        assertEquals(exported.instanceSteps, again.instanceSteps)
        val completed = again.instanceSteps.filter { it.occurrenceKey == "2026-09-06" }.sortedBy { it.position }
        val draft = again.instanceSteps.filter { it.occurrenceKey == "2026-09-07" }.sortedBy { it.position }
        assertEquals(5, completed.size)
        assertEquals(listOf("CONFIRMED", "CONFIRMED", "CONFIRMED", "CONFIRMED", "CONFIRMED"), completed.map { it.stepStatus })
        assertEquals(5, draft.size)
        assertEquals(listOf("PENDING", "PENDING", "PENDING", "PENDING", "PENDING"), draft.map { it.stepStatus })
        assertEquals(3, completed[0].counterValue)
        assertEquals(2_000L, completed[1].elapsedMillis)
        assertEquals("已完成", completed[2].informationContent)
        assertEquals(4, completed[3].moodRating)
        assertEquals("草稿答案", draft[2].informationContent)
        assertEquals("很好", draft[3].moodText)
    }

    @Test
    fun `v3 missing step ids restore deterministically for every occurrence and preserve completion`() = runTest {
        val payload = BackupPayload(
            schemaVersion = 3,
            definitions = listOf(definition("legacy", "NORMAL", 1)),
            definitionSteps = listOf(
                DefinitionStepBackup("legacy", 0, "旧一", true, null),
                DefinitionStepBackup("legacy", 1, "旧二", false, null),
            ),
            instances = listOf(instance("legacy", "once", "NORMAL", "PENDING", 100), instance("legacy", "2026-09-07", "NORMAL", "COMPLETED", 101)),
            instanceSteps = listOf(
                InstanceStepBackup("legacy", "once", 0, "旧一", true, false, 100, ""),
                InstanceStepBackup("legacy", "once", 1, "旧二", false, false, 100, ""),
                InstanceStepBackup("legacy", "2026-09-07", 0, "旧一", true, true, 101, ""),
                InstanceStepBackup("legacy", "2026-09-07", 1, "旧二", false, true, 101, ""),
            ),
        )
        val decoded = DstbCodec.decode(DstbCodec.encode(metadata(payload), payload)).payload
        repository.replace(decoded)
        val restored = repository.snapshot().instanceSteps
        assertEquals(2, restored.map { it.stepId }.distinct().size)
        assertEquals(1, restored.filter { it.position == 0 }.map { it.stepId }.distinct().size)
        assertTrue(restored.filter { it.occurrenceKey == "2026-09-07" }.all { it.stepStatus == "CONFIRMED" && it.completed })
        assertTrue(restored.filter { it.occurrenceKey == "once" }.all { it.stepStatus == "PENDING" && !it.completed })
    }

    @Test
    fun `merge chooses newer instance step group and exposes definition step source conflict`() {
        val local = mixedPayload(updated = 100, instanceStatus = "COMPLETED", stepNames = listOf("旧一", "旧二"))
        val backup = mixedPayload(updated = 200, instanceStatus = "PENDING", stepNames = listOf("新一"))
        val merged = BackupMerger.merge(local, backup, emptySet()).merged
        assertEquals("PENDING", merged.instances.single { it.occurrenceKey == "2026-09-06" }.status)
        val mergedFirstSteps = merged.instanceSteps.filter { it.occurrenceKey == "2026-09-06" }.sortedBy { it.position }
        assertEquals(5, mergedFirstSteps.size)
        assertEquals("新一", mergedFirstSteps.first().name)
        assertEquals("新一", merged.definitions.single().name)
        assertEquals("新一", merged.definitionSteps.single { it.position == 0 }.name)

        val sharedDefinition = local.definitions.single().copy(name = "同一父定义", updatedAtEpochMillis = 100)
        val sharedInstances = local.instances.map { it.copy(name = "同一父实例", updatedAtEpochMillis = 100) }
        val sameParentLocal = local.copy(definitions = listOf(sharedDefinition), instances = sharedInstances)
        val sameParentBackup = backup.copy(definitions = listOf(sharedDefinition), instances = sharedInstances)
        val selected = BackupMerger.merge(
            sameParentLocal,
            sameParentBackup,
            setOf("definition-steps:steps", "instance-steps:steps|2026-09-06"),
        ).merged
        assertEquals("新一", selected.definitionSteps.single { it.position == 0 }.name)
        assertEquals("新一", selected.instanceSteps.single { it.occurrenceKey == "2026-09-06" && it.position == 0 }.name)
        assertEquals("旧一", selected.instanceSteps.single { it.occurrenceKey == "2026-09-07" && it.position == 0 }.name)
    }

    @Test
    fun `validator rejects invalid step matrices but accepts legacy normal steps`() {
        val baseline = mixedPayload()
        fun alterFirstInstance(change: (List<InstanceStepBackup>) -> List<InstanceStepBackup>) = baseline.copy(
            instanceSteps = baseline.instanceSteps.filterNot { it.occurrenceKey == "2026-09-06" } +
                change(baseline.instanceSteps.filter { it.occurrenceKey == "2026-09-06" }),
        )
        assertTrue(runCatching { BackupValidator.validate(decoded(baseline)) }.isSuccess)
        val cases = listOf(
            "nested" to alterFirstInstance { it.toMutableList().also { steps -> steps[0] = steps[0].copy(executionKind = "STEPS") } },
            "empty" to alterFirstInstance { emptyList() },
            "fifty-one" to alterFirstInstance { List(51) { index -> InstanceStepBackup("steps", "2026-09-06", index, "步骤", true, true, 100, "X" + index.toString().padStart(15, '0'), "NORMAL", stepStatus = "CONFIRMED") } },
            "sparse" to alterFirstInstance { it.toMutableList().also { steps -> steps[1] = steps[1].copy(position = 2) } },
            "duplicate" to alterFirstInstance { it.toMutableList().also { steps -> steps[1] = steps[1].copy(stepId = steps[0].stepId) } },
            "required-skip" to alterFirstInstance { it.toMutableList().also { steps -> steps[0] = steps[0].copy(completed = false, stepStatus = "SKIPPED") } },
            "unmet-confirmed" to alterFirstInstance { it.toMutableList().also { steps -> steps[0] = steps[0].copy(counterValue = 1) } },
        )
        cases.forEach { (_, payload) -> assertFalse(runCatching { BackupValidator.validate(decoded(payload)) }.isSuccess) }
        val legacy = v4Payload("matrix", definitionKind = "NORMAL", steps = listOf(step(0, "", kind = "NORMAL")))
        assertTrue(runCatching { BackupValidator.validate(decoded(legacy)) }.isSuccess)
    }

    private fun decoded(payload: BackupPayload) = DecodedBackup(metadata(payload), payload)

    private fun metadata(payload: BackupPayload) = BackupMetadata(
        createdAtEpochMillis = 2_000,
        appVersion = "test",
        sourceTimeZone = "Asia/Hong_Kong",
        counts = BackupCounts(payload.groups.size, payload.definitions.size, payload.instances.size, payload.ledger.size, payload.actionLogs.size, payload.resultRevisions.size),
        payloadSchemaVersion = payload.schemaVersion,
    )

    private fun mixedPayload(updated: Long = 100, instanceStatus: String = "COMPLETED", stepNames: List<String> = listOf("计数", "选做")) = BackupPayload(
        definitions = listOf(definition("steps", "STEPS", updated, stepNames.first())),
        definitionSteps = listOf(
            DefinitionStepBackup("steps", 0, stepNames[0], true, "C000000000000001", "COUNTER", 2, 3),
            DefinitionStepBackup("steps", 1, "计时", true, "T000000000000001", "TIMER", null, 2),
            DefinitionStepBackup("steps", 2, "告知", true, "I000000000000001", "INFORMATION"),
            DefinitionStepBackup("steps", 3, "心情", true, "M000000000000001", "MOOD"),
            DefinitionStepBackup("steps", 4, "直接", true, "N000000000000001", "NORMAL"),
        ),
        instances = listOf(
            instance("steps", "2026-09-06", "STEPS", instanceStatus, updated),
            instance("steps", "2026-09-07", "STEPS", "PENDING", updated),
        ),
        instanceSteps = listOf(
            InstanceStepBackup("steps", "2026-09-06", 0, stepNames[0], true, true, updated, "C000000000000001", "COUNTER", 2, 3, "CONFIRMED", 3),
            InstanceStepBackup("steps", "2026-09-06", 1, "计时", true, true, updated, "T000000000000001", "TIMER", null, 2, "CONFIRMED", null, 2_000),
            InstanceStepBackup("steps", "2026-09-06", 2, "告知", true, true, updated, "I000000000000001", "INFORMATION", informationContent = "已完成", stepStatus = "CONFIRMED"),
            InstanceStepBackup("steps", "2026-09-06", 3, "心情", true, true, updated, "M000000000000001", "MOOD", stepStatus = "CONFIRMED", moodRating = 4, moodText = "不错"),
            InstanceStepBackup("steps", "2026-09-06", 4, "直接", true, true, updated, "N000000000000001", "NORMAL", stepStatus = "CONFIRMED"),
            InstanceStepBackup("steps", "2026-09-07", 0, stepNames[0], true, false, updated, "C000000000000001", "COUNTER", 2, 3, "PENDING", 2),
            InstanceStepBackup("steps", "2026-09-07", 1, "计时", true, false, updated, "T000000000000001", "TIMER", null, 2, "PENDING", null, 1_000),
            InstanceStepBackup("steps", "2026-09-07", 2, "告知", true, false, updated, "I000000000000001", "INFORMATION", informationContent = "草稿答案", stepStatus = "PENDING"),
            InstanceStepBackup("steps", "2026-09-07", 3, "心情", true, false, updated, "M000000000000001", "MOOD", stepStatus = "PENDING", moodRating = 4, moodText = "很好"),
            InstanceStepBackup("steps", "2026-09-07", 4, "直接", true, false, updated, "N000000000000001", "NORMAL", stepStatus = "PENDING"),
        ),
    )

    private fun v4Payload(taskId: String, definitionKind: String, steps: List<InstanceStepBackup>) = BackupPayload(
        definitions = listOf(definition(taskId, definitionKind, 100)),
        definitionSteps = steps.map { DefinitionStepBackup(taskId, it.position, it.name, it.required, it.stepId, it.executionKind, it.executionAction, it.executionTarget) },
        instances = listOf(instance(taskId, "once", definitionKind, "COMPLETED", 100)),
        instanceSteps = steps.map { it.copy(taskId = taskId, occurrenceKey = "once") },
    )

    private fun definition(id: String, kind: String, updated: Long, name: String = "任务") = DefinitionBackup(id, name, "", null, true, "2026-09-06", null, 1, null, "完成", "fp", false, 1, updated, executionKind = kind)
    private fun instance(id: String, occurrence: String, kind: String, status: String, updated: Long) = InstanceBackup(id, occurrence, "任务", "", "2026-09-06", null, null, true, 1, null, "完成", status, if (status == "COMPLETED") updated else null, 1, updated, "TEMPORARY", kind, null, null, null, updated, null)
    private fun step(position: Int, id: String, name: String = "步骤", status: String = "CONFIRMED", required: Boolean = true, kind: String = "NORMAL", target: Int? = null, counter: Int? = null) = InstanceStepBackup("matrix", "once", position, name, required, status == "CONFIRMED", 100, id, kind, if (kind == "COUNTER") 2 else null, target, status, counter)
}
