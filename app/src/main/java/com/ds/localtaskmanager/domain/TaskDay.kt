package com.ds.localtaskmanager.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

object TaskDay {
    private val boundary: LocalTime = LocalTime.of(4, 0)

    fun from(localDateTime: LocalDateTime): LocalDate =
        if (localDateTime.toLocalTime().isBefore(boundary)) {
            localDateTime.toLocalDate().minusDays(1)
        } else {
            localDateTime.toLocalDate()
        }
}
