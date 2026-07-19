package com.ds.localtaskmanager.domain

import com.ds.localtaskmanager.domain.recurrence.EffectiveRecurrence
import com.ds.localtaskmanager.domain.recurrence.RecurrenceDeadline
import com.ds.localtaskmanager.domain.recurrence.RecurrenceFrequency
import com.ds.localtaskmanager.domain.recurrence.RecurrencePlanRequest
import com.ds.localtaskmanager.domain.recurrence.RecurrencePlanner
import com.ds.localtaskmanager.domain.recurrence.WeekdayMask
import com.ds.localtaskmanager.domain.recurrence.deadlineFor
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class RecurrencePlannerTest {
    @Test
    fun `daily plan honors start end existing dates and count`() {
        val recurrence = EffectiveRecurrence(
            frequency = RecurrenceFrequency.DAILY,
            startDate = date(18),
            endDate = date(22),
            maxOccurrences = 4,
            weekdays = emptySet(),
            deadline = RecurrenceDeadline.Default,
        )

        val result = RecurrencePlanner.plan(
            RecurrencePlanRequest(
                recurrence = recurrence,
                throughDate = date(25),
                generatedCount = 1,
                latestOccurrenceDate = date(18),
                existingDatesInRange = setOf(date(20)),
            ),
        )

        assertEquals(listOf(date(19), date(21), date(22)), result)
    }

    @Test
    fun `weekly plan selects ordered weekdays`() {
        val recurrence = EffectiveRecurrence(
            frequency = RecurrenceFrequency.WEEKLY,
            startDate = LocalDate.of(2026, 7, 20),
            endDate = null,
            maxOccurrences = null,
            weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            deadline = RecurrenceDeadline.None,
        )

        assertEquals(
            listOf(
                LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 7, 22),
                LocalDate.of(2026, 7, 24),
            ),
            RecurrencePlanner.plan(
                RecurrencePlanRequest(recurrence, LocalDate.of(2026, 7, 26), 0, null),
            ),
        )
    }

    @Test
    fun `weekday mask and task-day deadlines are stable`() {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY)
        assertEquals(days, WeekdayMask.decode(WeekdayMask.encode(days)))
        assertEquals(
            LocalDateTime.of(2026, 7, 19, 4, 0),
            deadlineFor(date(18), RecurrenceDeadline.Default),
        )
        assertEquals(
            LocalDateTime.of(2026, 7, 19, 2, 0),
            deadlineFor(date(18), RecurrenceDeadline.At(LocalTime.of(2, 0))),
        )
        assertEquals(null, deadlineFor(date(18), RecurrenceDeadline.None))
    }

    private fun date(day: Int): LocalDate = LocalDate.of(2026, 7, day)
}
