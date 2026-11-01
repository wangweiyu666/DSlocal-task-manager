package com.ds.localtaskmanager.domain.reminder

import com.ds.localtaskmanager.domain.TaskStatus
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

enum class ReminderState {
    SCHEDULED,
    DELIVERED,
    SKIPPED_BEFORE_PUBLISHED,
    SKIPPED_PAST,
    SKIPPED_PERMISSION,
    CANCELLED,
}

data class ReminderPlan(
    val minutesBeforeDeadline: Int,
    val scheduledForEpochMillis: Long,
    val state: ReminderState,
    val kind: String = "DEADLINE",
)

object ReminderPlanner {
    fun plan(
        deadline: LocalDateTime?,
        reminderMinutes: List<Int>,
        publishedAtEpochMillis: Long,
        status: TaskStatus,
        now: Instant,
        zoneId: ZoneId,
        customReminder: LocalDateTime? = null,
    ): List<ReminderPlan> {
        val active = status == TaskStatus.NOT_STARTED || status == TaskStatus.PENDING
        val times = (if (deadline == null) emptyList() else reminderMinutes.map {
            Triple("DEADLINE", it, deadline.minusMinutes(it.toLong()))
        }) + listOfNotNull(customReminder?.let { Triple("CUSTOM", 0, it) })
        return times.map { (kind, minutes, time) ->
            val scheduled = time.atZone(zoneId).toInstant().toEpochMilli()
            val state = when {
                !active -> ReminderState.CANCELLED
                scheduled < publishedAtEpochMillis -> ReminderState.SKIPPED_BEFORE_PUBLISHED
                scheduled <= now.toEpochMilli() -> ReminderState.SKIPPED_PAST
                else -> ReminderState.SCHEDULED
            }
            ReminderPlan(minutes, scheduled, state, kind)
        }
    }
}

fun String?.decodeReminderMinutes(): List<Int> =
    this?.removePrefix("[")?.removeSuffix("]")
        ?.takeIf(String::isNotBlank)
        ?.split(',')
        ?.map(String::toInt)
        .orEmpty()
