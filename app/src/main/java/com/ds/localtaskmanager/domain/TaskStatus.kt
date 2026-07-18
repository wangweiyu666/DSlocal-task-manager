package com.ds.localtaskmanager.domain

import java.time.LocalDate
import java.time.LocalDateTime

enum class TaskStatus {
    NOT_STARTED,
    PENDING,
    COMPLETED,
    MISSED,
    CANCELLED,
}

object TaskStateMachine {
    fun statusAt(
        taskDate: LocalDate,
        deadline: LocalDateTime?,
        now: LocalDateTime,
    ): TaskStatus {
        val startsAt = taskDate.atTime(4, 0)
        return when {
            now.isBefore(startsAt) -> TaskStatus.NOT_STARTED
            deadline != null && !now.isBefore(deadline) -> TaskStatus.MISSED
            else -> TaskStatus.PENDING
        }
    }
}
