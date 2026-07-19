package com.ds.localtaskmanager.domain.recurrence

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

sealed interface RecurrenceSpec {
    data object None : RecurrenceSpec

    data class Daily(
        val startDate: LocalDate?,
        val endDate: LocalDate?,
        val maxOccurrences: Int?,
        val deadline: RecurrenceDeadline,
    ) : RecurrenceSpec

    data class Weekly(
        val startDate: LocalDate?,
        val endDate: LocalDate?,
        val maxOccurrences: Int?,
        val weekdays: Set<DayOfWeek>,
        val deadline: RecurrenceDeadline,
    ) : RecurrenceSpec
}

sealed interface RecurrenceDeadline {
    data object Default : RecurrenceDeadline
    data object None : RecurrenceDeadline
    data class At(val time: LocalTime) : RecurrenceDeadline
}

enum class RecurrenceFrequency(val databaseValue: Int, val category: String) {
    DAILY(1, "DAILY"),
    WEEKLY(2, "WEEKLY"),
}

data class EffectiveRecurrence(
    val frequency: RecurrenceFrequency,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val maxOccurrences: Int?,
    val weekdays: Set<DayOfWeek>,
    val deadline: RecurrenceDeadline,
)

object WeekdayMask {
    fun encode(days: Set<DayOfWeek>): Int = days.fold(0) { mask, day ->
        mask or (1 shl (day.value - 1))
    }

    fun decode(mask: Int?): Set<DayOfWeek> = if (mask == null) {
        emptySet()
    } else {
        DayOfWeek.entries.filterTo(linkedSetOf()) { day ->
            mask and (1 shl (day.value - 1)) != 0
        }
    }
}

fun deadlineFor(
    occurrenceDate: LocalDate,
    deadline: RecurrenceDeadline,
): LocalDateTime? = when (deadline) {
    RecurrenceDeadline.None -> null
    RecurrenceDeadline.Default -> occurrenceDate.plusDays(1).atTime(4, 0)
    is RecurrenceDeadline.At -> {
        val date = if (!deadline.time.isAfter(LocalTime.of(4, 0))) {
            occurrenceDate.plusDays(1)
        } else {
            occurrenceDate
        }
        date.atTime(deadline.time)
    }
}
