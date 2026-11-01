package com.ds.localtaskmanager.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ds.localtaskmanager.domain.RecordIdGenerator
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.protocol.Dst1Parser
import com.ds.localtaskmanager.protocol.encodeDst1ForTest
import com.ds.localtaskmanager.reminder.ReminderCoordinator
import com.ds.localtaskmanager.reminder.ReminderKey
import com.ds.localtaskmanager.reminder.ReminderNotifier
import com.ds.localtaskmanager.reminder.ReminderScheduler
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomReminderTest {
    private lateinit var database: AppDatabase
    private val clock = MutableClock(Instant.parse("2026-07-20T20:00:00Z"))
    private val scheduler = FakeScheduler()
    private val notifier = FakeNotifier()
    private val sequence = AtomicInteger()
    private val ids = RecordIdGenerator { "W21Rem${sequence.incrementAndGet().toString().padStart(10, '0')}" }
    private lateinit var coordinator: ReminderCoordinator

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        coordinator = ReminderCoordinator(database, scheduler, notifier, clock, ids) { ZoneOffset.UTC }
    }

    @After
    fun tearDown() = database.close()


    @Test fun customAndDeadlineRemindersCoexistAndDeduplicateConcurrentReceipts() = runTest {
        insertInstance()
        val old = database.instanceDao().getInstance(TASK_ID)!!
        setInstance(old.copy(customReminderAt = "2026-07-20T21:00"))
        coordinator.reconcileAll()
        assertEquals(3, database.reminderDao().scheduledRecords().size)
        clock.value = Instant.parse("2026-07-20T21:00:00Z")
        kotlinx.coroutines.coroutineScope {
            listOf(ReminderKey(TASK_ID, "once", 60), ReminderKey(TASK_ID, "once", 0, "CUSTOM")).map {
                launch { coordinator.deliver(it) }
            }.forEach { it.join() }
        }
        assertEquals(1, notifier.posts.size)
        assertEquals(2, database.reminderDao().allRecords().count { it.state == "DELIVERED" })
        coordinator.reconcileAll()
        coordinator.deliver(ReminderKey(TASK_ID, "once", 0, "CUSTOM"))
        assertEquals(1, notifier.posts.size)
    }

    @Test fun noDeadlinePermissionAndPastReminders() = runTest {
        insertInstance()
        val old = database.instanceDao().getInstance(TASK_ID)!!
        setInstance(old.copy(deadline = null, reminderMinutesJson = null, customReminderAt = "2026-07-20T21:00"))
        coordinator.reconcileAll()
        assertEquals(1, database.reminderDao().scheduledRecords().size)
        notifier.enabled = false
        clock.value = Instant.parse("2026-07-20T21:00:00Z")
        coordinator.deliver(ReminderKey(TASK_ID, "once", 0, "CUSTOM"))
        assertEquals("SKIPPED_PERMISSION", database.reminderDao().getRecord(TASK_ID, "once", 0, "CUSTOM")?.state)
        assertEquals(0, notifier.posts.size)
        notifier.enabled = true
        coordinator.reconcileAll()
        assertEquals(0, database.reminderDao().scheduledRecords().size)
    }

    @Test fun delayCompletionUndoDeletionAndRebootKeepIndependentTimeAndSentState() = runTest {
        insertInstance()
        val old = database.instanceDao().getInstance(TASK_ID)!!
        setInstance(old.copy(customReminderAt = "2026-07-20T21:00"))
        coordinator.reconcileAll()
        setInstance(old.copy(deadline = "2026-07-20T23:00", customReminderAt = "2026-07-20T21:00"))
        coordinator.reconcileAll()
        assertEquals(Instant.parse("2026-07-20T21:00:00Z").toEpochMilli(), database.reminderDao().getRecord(TASK_ID, "once", 0, "CUSTOM")!!.scheduledForEpochMillis)
        setInstance(old.copy(status = "COMPLETED", customReminderAt = "2026-07-20T21:00"))
        coordinator.reconcileAll()
        assertEquals(0, database.reminderDao().scheduledRecords().size)
        setInstance(old.copy(customReminderAt = "2026-07-20T21:00"))
        coordinator.reconcileAll("undo")
        assertEquals(3, database.reminderDao().scheduledRecords().size)
        clock.value = Instant.parse("2026-07-20T21:00:00Z")
        coordinator.deliver(ReminderKey(TASK_ID, "once", 0, "CUSTOM"))
        setInstance(old)
        coordinator.reconcileAll()
        setInstance(old.copy(customReminderAt = "2026-07-20T21:00"))
        coordinator.reconcileAll("boot")
        assertEquals("DELIVERED", database.reminderDao().getRecord(TASK_ID, "once", 0, "CUSTOM")!!.state)
        assertEquals(1, notifier.posts.size)
    }

    @Test fun recurringTaskDaysAndExceptionsResolveAndClearWithoutMixingInstances() = runTest {
        val service = RoomImportService(database, Dst1Parser(), clock, ids)
        suspend fun apply(json: String) { service.import(service.preview(encodeDst1ForTest(json))) }
        apply("""{"v":1,"b":"CustomBatch00001","t":[{"i":"ReminderTask0001","n":"提醒","r":1,"x":{"f":1,"s":"2026-07-20"},"a":"02:00"}]}""")
        assertEquals("2026-07-21T02:00", database.instanceDao().getInstance(TASK_ID, "2026-07-20")!!.customReminderAt)
        apply("""{"v":1,"sv":1,"b":"CustomBatch00002","e":[{"i":"ReminderTask0001","y":"2026-07-20","a":null}]}""")
        assertEquals(null, database.instanceDao().getInstance(TASK_ID, "2026-07-20")!!.customReminderAt)
        apply("""{"v":1,"sv":1,"b":"CustomBatch00003","e":[{"i":"ReminderTask0001","y":"2026-07-20","a":"2026-07-20T23:00"}]}""")
        assertEquals("2026-07-20T23:00", database.instanceDao().getInstance(TASK_ID, "2026-07-20")!!.customReminderAt)
        com.ds.localtaskmanager.data.recurrence.RoomInstanceGenerationService(database, clock, ids).reconcileAll(java.time.LocalDate.parse("2026-07-21"))
        assertEquals("2026-07-22T02:00", database.instanceDao().getInstance(TASK_ID, "2026-07-21")!!.customReminderAt)
        apply("""{"v":1,"sv":1,"b":"CustomBatch00004","e":[{"i":"ReminderTask0001","y":"2026-07-20"}]}""")
        assertEquals("2026-07-21T02:00", database.instanceDao().getInstance(TASK_ID, "2026-07-20")!!.customReminderAt)
        val bad = """{"v":1,"sv":1,"b":"CustomBatch00005","e":[{"i":"ReminderTask0001","y":"2026-07-20","l":"2026-07-20T22:00"}]}"""
        var failed = false
        try { apply(bad) } catch (_: com.ds.localtaskmanager.protocol.Dst1ValidationException) { failed = true }
        org.junit.Assert.assertTrue(failed)
        assertEquals("2026-07-21T02:00", database.instanceDao().getInstance(TASK_ID, "2026-07-20")!!.customReminderAt)
    }

    @Test fun backupRoundtripMergeAndLegacyReadPreserveCustomAndDeliveredRecords() = runTest {
        insertInstance()
        val old = database.instanceDao().getInstance(TASK_ID)!!
        setInstance(old.copy(customReminderAt = "2026-07-20T21:00"))
        coordinator.reconcileAll()
        clock.value = Instant.parse("2026-07-20T21:00:00Z")
        coordinator.deliver(ReminderKey(TASK_ID, "once", 0, "CUSTOM"))
        val repository = com.ds.localtaskmanager.backup.RoomBackupRepository(database,
            com.ds.localtaskmanager.settings.AppSettingsRepository(ApplicationProvider.getApplicationContext()))
        val snapshot = repository.snapshot()
        assertEquals(7, snapshot.schemaVersion)
        val json = kotlinx.serialization.json.Json.encodeToString(com.ds.localtaskmanager.backup.BackupPayload.serializer(), snapshot)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(com.ds.localtaskmanager.backup.BackupPayload.serializer(), json)
        repository.replace(decoded)
        coordinator.reconcileAll("restore")
        coordinator.deliver(ReminderKey(TASK_ID, "once", 0, "CUSTOM"))
        assertEquals(1, notifier.posts.size)
        val merged = repository.previewMerge(decoded)
        repository.applyMerge(merged)
        assertEquals("DELIVERED", database.reminderDao().getRecord(TASK_ID, "once", 0, "CUSTOM")!!.state)
        fun metadata(version: Int) = com.ds.localtaskmanager.backup.BackupMetadata(clock.millis(), "test", "UTC",
            payloadSchemaVersion = version, counts = com.ds.localtaskmanager.backup.BackupCounts(
                snapshot.groups.size, snapshot.definitions.size, snapshot.instances.size, snapshot.ledger.size,
                snapshot.actionLogs.size, snapshot.resultRevisions.size))
        val packed = com.ds.localtaskmanager.backup.DstbCodec.decode(com.ds.localtaskmanager.backup.DstbCodec.encode(metadata(decoded.schemaVersion), decoded))
        com.ds.localtaskmanager.backup.BackupValidator.validate(packed)
        assertEquals(decoded.reminders, packed.payload.reminders)
        val legacySix = com.ds.localtaskmanager.backup.DstbCodec.decode(com.ds.localtaskmanager.backup.DstbCodec.encode(metadata(6), decoded.copy(schemaVersion = 6)))
        com.ds.localtaskmanager.backup.BackupValidator.validate(legacySix)
        assertEquals(decoded.reminders, legacySix.payload.reminders)
        val oldPayload = snapshot.copy(schemaVersion = 5, reminders = emptyList(),
            definitions = snapshot.definitions.map { it.copy(customReminder = null) },
            instances = snapshot.instances.map { it.copy(customReminderAt = null) })
        val legacy = com.ds.localtaskmanager.backup.DstbCodec.decode(com.ds.localtaskmanager.backup.DstbCodec.encode(metadata(5), oldPayload))
        com.ds.localtaskmanager.backup.BackupValidator.validate(legacy)
        assertEquals(emptyList<com.ds.localtaskmanager.backup.ReminderBackup>(), legacy.payload.reminders)
        assertEquals(oldPayload.instances, legacy.payload.instances)
        val bad = decoded.copy(instances = decoded.instances.map { it.copy(customReminderAt = "2026-07-21T23:00") })
        org.junit.Assert.assertThrows(com.ds.localtaskmanager.backup.DstbException::class.java) {
            com.ds.localtaskmanager.backup.BackupValidator.validateExtendedSnapshots(bad)
        }
    }

    @Test fun simultaneousTemplateAndExceptionUpdateUsesFinalDeadline() = runTest {
        val service = RoomImportService(database, Dst1Parser(), clock, ids)
        suspend fun apply(json: String) { service.import(service.preview(encodeDst1ForTest(json))) }
        apply("""{"v":1,"b":"CustomBatch00011","t":[{"i":"ReminderTask0001","n":"提醒","r":1,"x":{"f":1,"t":"22:00"},"a":"21:00"}]}""")
        apply("""{"v":1,"sv":1,"b":"CustomBatch00012","t":[{"i":"ReminderTask0001","n":"提醒","r":1,"x":{"f":1,"t":"23:00"},"a":"22:30"}],"e":[{"i":"ReminderTask0001","y":"2026-07-20","l":"2026-07-20T23:00","h":[60]}]}""")
        val instance = database.instanceDao().getInstance(TASK_ID, "2026-07-20")!!
        assertEquals("2026-07-20T23:00", instance.deadline)
        assertEquals("2026-07-20T22:30", instance.customReminderAt)
        apply("""{"v":1,"b":"CustomBatch00013","t":[{"i":"ReminderTask0001","n":"提醒","r":1,"x":{"f":1,"t":"23:00"},"a":"22:00"}]}""")
        val updated = database.instanceDao().getInstance(TASK_ID, "2026-07-20")!!
        assertEquals("[60]", updated.reminderMinutesJson)
        assertEquals("2026-07-20T22:00", updated.customReminderAt)
    }

    @Test fun parserRejectsInvalidTimeAndLateCustomReminder() = runTest {
        val service = RoomImportService(database, Dst1Parser(), clock, ids)
        for (fields in listOf(
            """"l":null,"a":"2026-02-30T20:00" """,
            """"l":"2026-07-20T20:00","a":"2026-07-20T20:01" """,
            """"x":{"f":1,"t":"22:00"},"a":"23:00" """,
            """"x":{"f":1},"a":"24:00" """
        )) {
            var failed = false
            try { service.preview(encodeDst1ForTest("""{"v":1,"b":"CustomBatch00009","t":[{"i":"ReminderTask0001","n":"提醒","r":1,$fields}]}""")) }
            catch (_: com.ds.localtaskmanager.protocol.Dst1ValidationException) { failed = true }
            org.junit.Assert.assertTrue(fields, failed)
        }
    }

    private suspend fun setInstance(instance: TaskInstanceEntity) { database.instanceDao().upsertInstances(listOf(instance)) }

    private suspend fun insertInstance() {
        database.definitionDao().upsertDefinitions(
            listOf(
                TaskDefinitionEntity(
                    taskId = TASK_ID,
                    name = "Reminder",
                    description = "",
                    groupId = null,
                    required = true,
                    taskDate = "2026-07-20",
                    deadline = "2026-07-20T22:00",
                    points = 0,
                    sortOrder = null,
                    completionMessage = "Done",
                    stepsFingerprint = "",
                    cancelled = false,
                    createdAtEpochMillis = clock.millis(),
                    updatedAtEpochMillis = clock.millis(),
                    reminderMinutesJson = "[60,10]",
                ),
            ),
        )
        database.instanceDao().insertInstance(
            TaskInstanceEntity(
                taskId = TASK_ID,
                occurrenceKey = "once",
                name = "Reminder",
                description = "",
                taskDate = "2026-07-20",
                deadline = "2026-07-20T22:00",
                groupId = null,
                required = true,
                points = 0,
                sortOrder = null,
                completionMessage = "Done",
                status = TaskStatus.PENDING.name,
                completedAtEpochMillis = null,
                createdAtEpochMillis = clock.millis(),
                updatedAtEpochMillis = clock.millis(),
                reminderMinutesJson = "[60,10]",
                publishedAtEpochMillis = Instant.parse("2026-07-20T20:00:00Z").toEpochMilli(),
            ),
        )
    }

    private class FakeScheduler : ReminderScheduler {
        val scheduled = mutableMapOf<String, ReminderRecordEntity>()
        override fun schedule(record: ReminderRecordEntity) {
            scheduled["${record.taskId}|${record.occurrenceKey}|${record.kind}|${record.minutesBeforeDeadline}"] = record
        }
        override fun cancel(record: ReminderRecordEntity) {
            scheduled.remove("${record.taskId}|${record.occurrenceKey}|${record.kind}|${record.minutesBeforeDeadline}")
        }
    }

    private class FakeNotifier : ReminderNotifier {
        var enabled = true
        val posts = mutableListOf<ReminderKey>()
        override fun notificationsEnabled(): Boolean = enabled
        override fun post(instance: TaskInstanceEntity, key: ReminderKey) { posts += key }
    }

    private class MutableClock(var value: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = value
    }

    private companion object {
        const val TASK_ID = "ReminderTask0001"
    }
}
