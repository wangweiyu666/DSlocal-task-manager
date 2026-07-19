package com.ds.localtaskmanager.domain.recurrence

import java.time.LocalDate

data class RecurrencePlanRequest(
    val recurrence: EffectiveRecurrence,
    val throughDate: LocalDate,
    val generatedCount: Int,
    val latestOccurrenceDate: LocalDate?,
    val existingDatesInRange: Set<LocalDate> = emptySet(),
)

object RecurrencePlanner {
    fun plan(request: RecurrencePlanRequest): List<LocalDate> {
        val recurrence = request.recurrence
        val countLimit = recurrence.maxOccurrences
        if (countLimit != null && request.generatedCount >= countLimit) return emptyList()

        val firstCandidate = maxOf(
            recurrence.startDate,
            request.latestOccurrenceDate?.plusDays(1) ?: recurrence.startDate,
        )
        val lastCandidate = recurrence.endDate?.let { minOf(it, request.throughDate) }
            ?: request.throughDate
        if (firstCandidate > lastCandidate) return emptyList()

        val result = mutableListOf<LocalDate>()
        var candidate = firstCandidate
        while (candidate <= lastCandidate) {
            val scheduled = when (recurrence.frequency) {
                RecurrenceFrequency.DAILY -> true
                RecurrenceFrequency.WEEKLY -> candidate.dayOfWeek in recurrence.weekdays
            }
            if (scheduled && candidate !in request.existingDatesInRange) {
                if (countLimit != null && request.generatedCount + result.size >= countLimit) break
                result += candidate
            }
            candidate = candidate.plusDays(1)
        }
        return result
    }
}
