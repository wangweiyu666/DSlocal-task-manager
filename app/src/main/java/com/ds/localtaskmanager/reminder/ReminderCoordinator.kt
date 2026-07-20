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
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

data class ReminderKey(
    val taskId: String,
    val occurrenceKey: String,
    val minutesBeforeDeadline: Int,
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
    override suspend fun reconcileAll(reason: String?) {
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

    suspend fun deliver(key: ReminderKey) {
        val outcome = database.withTransaction {
            val dao = database.reminderDao()
            val record = dao.getRecord(key.taskId, key.occurrenceKey, key.minutesBeforeDeadline)
                ?: return@withTransaction DeliveryOutcome.Missing
            val instance = database.instanceDao().getInstance(key.taskId, key.occurrenceKey)
                ?: return@withTransaction DeliveryOutcome.Cancel(record)
            val minutes = instance.reminderMinutesJson.decodeReminderMinutes()
            val active = instance.status == TaskStatus.NOT_STARTED.name || instance.status == TaskStatus.PENDING.name
            when {
                record.state != ReminderState.SCHEDULED.name -> DeliveryOutcome.Cancel(record)
                !active || key.minutesBeforeDeadline !in minutes -> {
                    val cancelled = record.copy(state = ReminderState.CANCELLED.name, updatedAtEpochMillis = clock.millis())
                    dao.upsertRecords(listOf(cancelled))
                    DeliveryOutcome.Cancel(cancelled)
                }
                clock.millis() < record.scheduledForEpochMillis -> DeliveryOutcome.Reschedule(record)
                !notifier.notificationsEnabled() -> {
                    val skipped = record.copy(
                        state = ReminderState.SKIPPED_PERMISSION.name,
                        updatedAtEpochMillis = clock.millis(),
                    )
                    dao.upsertRecords(listOf(skipped))
                    DeliveryOutcome.Cancel(skipped)
                }
                else -> DeliveryOutcome.Post(instance, record)
            }
        }
        when (outcome) {
            is DeliveryOutcome.Post -> {
                notifier.post(outcome.instance, key)
                database.withTransaction {
                    val deliveredAt = clock.millis()
                    database.reminderDao().upsertRecords(
                        listOf(
                            outcome.record.copy(
                                state = ReminderState.DELIVERED.name,
                                deliveredAtEpochMillis = deliveredAt,
                                updatedAtEpochMillis = deliveredAt,
                            ),
                        ),
                    )
                    database.auditDao().insertLogs(
                        listOf(
                            ActionLogEntity(
                                eventId = idGenerator.next(),
                                taskId = key.taskId,
                                occurrenceKey = key.occurrenceKey,
                                batchId = null,
                                action = "REMINDER_DELIVERED",
                                detail = "{\"minutesBeforeDeadline\":${key.minutesBeforeDeadline}}",
                                createdAtEpochMillis = deliveredAt,
                            ),
                        ),
                    )
                }
            }
            is DeliveryOutcome.Reschedule -> scheduler.schedule(outcome.record)
            is DeliveryOutcome.Cancel -> scheduler.cancel(outcome.record)
            DeliveryOutcome.Missing -> Unit
        }
    }

    private suspend fun reconcileInstance(instance: TaskInstanceEntity, now: Long): List<ReminderRecordEntity> {
        val dao = database.reminderDao()
        val existing = dao.recordsForInstance(instance.taskId, instance.occurrenceKey)
            .associateBy(ReminderRecordEntity::minutesBeforeDeadline)
        val plans = ReminderPlanner.plan(
            deadline = instance.deadline?.let(LocalDateTime::parse),
            reminderMinutes = instance.reminderMinutesJson.decodeReminderMinutes(),
            publishedAtEpochMillis = instance.publishedAtEpochMillis,
            status = TaskStatus.valueOf(instance.status),
            now = clock.instant(),
            zoneId = zoneId(),
        )
        val plannedMinutes = plans.mapTo(mutableSetOf()) { it.minutesBeforeDeadline }
        val records = plans.map { plan ->
            val old = existing[plan.minutesBeforeDeadline]
            val state = if (
                old?.state == ReminderState.DELIVERED.name &&
                old.scheduledForEpochMillis == plan.scheduledForEpochMillis
            ) ReminderState.DELIVERED.name else plan.state.name
            ReminderRecordEntity(
                taskId = instance.taskId,
                occurrenceKey = instance.occurrenceKey,
                minutesBeforeDeadline = plan.minutesBeforeDeadline,
                scheduledForEpochMillis = plan.scheduledForEpochMillis,
                state = state,
                deliveredAtEpochMillis = if (state == ReminderState.DELIVERED.name) old?.deliveredAtEpochMillis else null,
                createdAtEpochMillis = old?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
            )
        } + existing.values.filter { it.minutesBeforeDeadline !in plannedMinutes }.map {
            it.copy(state = ReminderState.CANCELLED.name, updatedAtEpochMillis = now)
        }
        if (records.isNotEmpty()) dao.upsertRecords(records)
        return records
    }

    private fun applySchedule(record: ReminderRecordEntity) {
        if (record.state == ReminderState.SCHEDULED.name) scheduler.schedule(record) else scheduler.cancel(record)
    }

    private sealed interface DeliveryOutcome {
        data object Missing : DeliveryOutcome
        data class Post(val instance: TaskInstanceEntity, val record: ReminderRecordEntity) : DeliveryOutcome
        data class Reschedule(val record: ReminderRecordEntity) : DeliveryOutcome
        data class Cancel(val record: ReminderRecordEntity) : DeliveryOutcome
    }
}
