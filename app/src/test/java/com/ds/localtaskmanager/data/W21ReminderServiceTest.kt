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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class W21ReminderServiceTest {
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

    @Test
    fun `import persists existing h in definition and instance snapshots`() = runTest {
        val service = RoomImportService(database, Dst1Parser(), clock, ids)
        val json = """{"v":1,"b":"W21Batch00000001","t":[{"i":"ReminderTask0001","n":"Reminder","r":1,"l":"2026-07-20T22:00","h":[60,10]}]}"""
        service.import(service.preview(encodeDst1ForTest(json)))

        assertEquals("[60,10]", database.definitionDao().getDefinition("ReminderTask0001")?.reminderMinutesJson)
        assertEquals("[60,10]", database.instanceDao().getInstance("ReminderTask0001")?.reminderMinutesJson)
    }

    @Test
    fun `reconcile is idempotent and delivery records one private notification`() = runTest {
        insertInstance()

        coordinator.reconcileAll()
        coordinator.reconcileAll()
        assertEquals(2, database.reminderDao().scheduledRecords().size)
        assertEquals(2, scheduler.scheduled.size)

        clock.value = Instant.parse("2026-07-20T21:00:00Z")
        coordinator.deliver(ReminderKey(TASK_ID, "once", 60))

        assertEquals(1, notifier.posts.size)
        assertEquals("DELIVERED", database.reminderDao().getRecord(TASK_ID, "once", 60)?.state)
    }

    @Test
    fun `permission denial skips without changing task state`() = runTest {
        insertInstance()
        coordinator.reconcileAll()
        notifier.enabled = false
        clock.value = Instant.parse("2026-07-20T21:00:00Z")

        coordinator.deliver(ReminderKey(TASK_ID, "once", 60))

        assertEquals("SKIPPED_PERMISSION", database.reminderDao().getRecord(TASK_ID, "once", 60)?.state)
        assertEquals(TaskStatus.PENDING.name, database.instanceDao().getInstance(TASK_ID)?.status)
    }

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
        val scheduled = mutableMapOf<Triple<String, String, Int>, ReminderRecordEntity>()
        override fun schedule(record: ReminderRecordEntity) {
            scheduled[Triple(record.taskId, record.occurrenceKey, record.minutesBeforeDeadline)] = record
        }
        override fun cancel(record: ReminderRecordEntity) {
            scheduled.remove(Triple(record.taskId, record.occurrenceKey, record.minutesBeforeDeadline))
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
