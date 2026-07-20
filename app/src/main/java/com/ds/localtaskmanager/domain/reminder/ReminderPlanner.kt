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
)

object ReminderPlanner {
    fun plan(
        deadline: LocalDateTime?,
        reminderMinutes: List<Int>,
        publishedAtEpochMillis: Long,
        status: TaskStatus,
        now: Instant,
        zoneId: ZoneId,
    ): List<ReminderPlan> {
        if (deadline == null || reminderMinutes.isEmpty()) return emptyList()
        val active = status == TaskStatus.NOT_STARTED || status == TaskStatus.PENDING
        return reminderMinutes.map { minutes ->
            val scheduled = deadline.minusMinutes(minutes.toLong()).atZone(zoneId).toInstant().toEpochMilli()
            val state = when {
                !active -> ReminderState.CANCELLED
                scheduled < publishedAtEpochMillis -> ReminderState.SKIPPED_BEFORE_PUBLISHED
                scheduled <= now.toEpochMilli() -> ReminderState.SKIPPED_PAST
                else -> ReminderState.SCHEDULED
            }
            ReminderPlan(minutes, scheduled, state)
        }
    }
}

fun String?.decodeReminderMinutes(): List<Int> =
    this?.removePrefix("[")?.removeSuffix("]")
        ?.takeIf(String::isNotBlank)
        ?.split(',')
        ?.map(String::toInt)
        .orEmpty()
