package com.ds.localtaskmanager.domain

import com.ds.localtaskmanager.domain.reminder.ReminderPlanner
import com.ds.localtaskmanager.domain.reminder.ReminderState
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderPlannerTest {
    @Test
    fun `plans only future reminders published with the instance`() {
        val plans = ReminderPlanner.plan(
            deadline = LocalDateTime.parse("2026-07-20T22:00"),
            reminderMinutes = listOf(180, 60, 0),
            publishedAtEpochMillis = Instant.parse("2026-07-20T20:00:00Z").toEpochMilli(),
            status = TaskStatus.PENDING,
            now = Instant.parse("2026-07-20T20:30:00Z"),
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(
            listOf(
                ReminderState.SKIPPED_BEFORE_PUBLISHED,
                ReminderState.SCHEDULED,
                ReminderState.SCHEDULED,
            ),
            plans.map { it.state },
        )
    }

    @Test
    fun `terminal instance cancels every reminder`() {
        val plans = ReminderPlanner.plan(
            deadline = LocalDateTime.parse("2026-07-20T22:00"),
            reminderMinutes = listOf(60, 0),
            publishedAtEpochMillis = 0,
            status = TaskStatus.COMPLETED,
            now = Instant.parse("2026-07-20T20:00:00Z"),
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(listOf(ReminderState.CANCELLED, ReminderState.CANCELLED), plans.map { it.state })
    }

    @Test
    fun `reminder at current instant is not backfilled`() {
        val plans = ReminderPlanner.plan(
            deadline = LocalDateTime.parse("2026-07-20T22:00"),
            reminderMinutes = listOf(60),
            publishedAtEpochMillis = 0,
            status = TaskStatus.PENDING,
            now = Instant.parse("2026-07-20T21:00:00Z"),
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(ReminderState.SKIPPED_PAST, plans.single().state)
    }
}
