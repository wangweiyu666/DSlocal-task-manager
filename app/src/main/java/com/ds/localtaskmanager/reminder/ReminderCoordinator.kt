package com.ds.localtaskmanager.reminder

import androidx.room.withTransaction
import com.ds.localtaskmanager.data.ActionLogEntity
import com.ds.localtaskmanager.data.AppDatabase
import com.ds.localtaskmanager.data.ReminderRecordEntity
import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.domain.RecordIdGenerator
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import com.ds.localtaskmanager.domain.reminder.ReminderPlanner
import com.ds.localtaskmanager.domain.reminder.ReminderState
import com.ds.localtaskmanager.domain.reminder.decodeReminderMinutes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

data class ReminderKey(
    val taskId: String,
    val occurrenceKey: String,
    val minutesBeforeDeadline: Int,
    val kind: String = "DEADLINE",
)

interface ReminderScheduler {
    fun schedule(record: ReminderRecordEntity)
    fun cancel(record: ReminderRecordEntity)
}

interface ReminderNotifier {
    fun notificationsEnabled(): Boolean
    fun post(instance: TaskInstanceEntity, key: ReminderKey)
}

interface ReminderReconciler {
    suspend fun reconcileAll(reason: String? = null)
}

class ReminderCoordinator(
    private val database: AppDatabase,
    private val scheduler: ReminderScheduler,
    private val notifier: ReminderNotifier,
    private val clock: Clock,
    private val idGenerator: RecordIdGenerator,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) : ReminderReconciler {
    private val mutex = Mutex()

    override suspend fun reconcileAll(reason: String?) = mutex.withLock {
        val records = database.withTransaction {
            val now = clock.millis()
            val instances = database.instanceDao().instancesNeedingReminderReconciliation()
            val updated = instances.flatMap { instance -> reconcileInstance(instance, now) }
            if (reason != null) {
                database.auditDao().insertLogs(
                    listOf(
                        ActionLogEntity(
                            eventId = idGenerator.next(),
                            taskId = null,
                            occurrenceKey = null,
                            batchId = null,
                            action = "REMINDERS_RECONCILED",
                            detail = "{\"reason\":\"${reason.take(80)}\",\"records\":${updated.size}}",
                            createdAtEpochMillis = now,
                        ),
                    ),
                )
            }
            updated
        }
        records.forEach(::applySchedule)
    }

    suspend fun deliver(key: ReminderKey) = mutex.withLock {
        database.withTransaction {
            val dao = database.reminderDao()
            val record = dao.getRecord(key.taskId, key.occurrenceKey, key.minutesBeforeDeadline, key.kind)
                ?: return@withTransaction
            val instance = database.instanceDao().getInstance(key.taskId, key.occurrenceKey)
            val desired = instance?.let { plans(it) }?.firstOrNull {
                it.kind == key.kind && it.minutesBeforeDeadline == key.minutesBeforeDeadline
            }
            if (record.state != ReminderState.SCHEDULED.name) {
                scheduler.cancel(record)
                return@withTransaction
            }
            if (instance == null || desired == null || desired.state == ReminderState.CANCELLED ||
                desired.scheduledForEpochMillis != record.scheduledForEpochMillis) {
                dao.upsertRecords(listOf(record.copy(state = ReminderState.CANCELLED.name, updatedAtEpochMillis = clock.millis())))
                scheduler.cancel(record)
                return@withTransaction
            }
            if (clock.millis() < record.scheduledForEpochMillis) {
                scheduler.schedule(record)
                return@withTransaction
            }
            val peers = dao.recordsForInstance(key.taskId, key.occurrenceKey)
                .filter { it.scheduledForEpochMillis == record.scheduledForEpochMillis }
            val alreadyDelivered = peers.any { it.state == ReminderState.DELIVERED.name }
            val enabled = notifier.notificationsEnabled()
            if (enabled && !alreadyDelivered) notifier.post(instance, key)
            val now = clock.millis()
            val state = if (alreadyDelivered || enabled) ReminderState.DELIVERED else ReminderState.SKIPPED_PERMISSION
            val handled = peers.filter { it.state == ReminderState.SCHEDULED.name }.map {
                it.copy(state = state.name, deliveredAtEpochMillis = if (state == ReminderState.DELIVERED) now else null,
                    updatedAtEpochMillis = now)
            }
            dao.upsertRecords(handled)
            handled.forEach(scheduler::cancel)
            if (enabled && !alreadyDelivered) database.auditDao().insertLogs(listOf(ActionLogEntity(
                eventId = idGenerator.next(), taskId = key.taskId, occurrenceKey = key.occurrenceKey,
                batchId = null, action = "REMINDER_DELIVERED",
                detail = "{\"minutesBeforeDeadline\":${key.minutesBeforeDeadline},\"kind\":\"${key.kind}\"}",
                createdAtEpochMillis = now,
            )))
        }
    }

    private fun plans(instance: TaskInstanceEntity) = ReminderPlanner.plan(
        deadline = instance.deadline?.let(LocalDateTime::parse),
        reminderMinutes = instance.reminderMinutesJson.decodeReminderMinutes(),
        publishedAtEpochMillis = instance.publishedAtEpochMillis,
        status = TaskStatus.valueOf(instance.status),
        now = clock.instant(), zoneId = zoneId(),
        customReminder = instance.customReminderAt?.let(LocalDateTime::parse),
    )

    private suspend fun reconcileInstance(instance: TaskInstanceEntity, now: Long): List<ReminderRecordEntity> {
        val dao = database.reminderDao()
        val existing = dao.recordsForInstance(instance.taskId, instance.occurrenceKey)
            .associateBy { it.kind to it.minutesBeforeDeadline }
        val plans = plans(instance)
        val plannedKeys = plans.mapTo(mutableSetOf()) { it.kind to it.minutesBeforeDeadline }
        val records = plans.map { plan ->
            val old = existing[plan.kind to plan.minutesBeforeDeadline]
            val state = if (
                old?.state == ReminderState.DELIVERED.name &&
                old.scheduledForEpochMillis == plan.scheduledForEpochMillis
            ) ReminderState.DELIVERED.name else plan.state.name
            ReminderRecordEntity(
                taskId = instance.taskId,
                occurrenceKey = instance.occurrenceKey,
                minutesBeforeDeadline = plan.minutesBeforeDeadline,
                kind = plan.kind,
                scheduledForEpochMillis = plan.scheduledForEpochMillis,
                state = state,
                deliveredAtEpochMillis = if (state == ReminderState.DELIVERED.name) old?.deliveredAtEpochMillis else null,
                createdAtEpochMillis = old?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
            )
        } + existing.values.filter { (it.kind to it.minutesBeforeDeadline) !in plannedKeys }.map {
            it.copy(state = if (it.state == ReminderState.DELIVERED.name) it.state else ReminderState.CANCELLED.name, updatedAtEpochMillis = now)
        }
        if (records.isNotEmpty()) dao.upsertRecords(records)
        return records
    }

    private fun applySchedule(record: ReminderRecordEntity) {
        if (record.state == ReminderState.SCHEDULED.name) scheduler.schedule(record) else scheduler.cancel(record)
    }

}
