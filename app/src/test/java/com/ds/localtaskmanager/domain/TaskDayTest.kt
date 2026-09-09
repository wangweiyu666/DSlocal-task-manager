package com.ds.localtaskmanager.domain

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskDayTest {
    @Test
    fun `task day changes exactly at four`() {
        listOf(
            LocalDateTime.of(2026, 7, 18, 3, 59) to LocalDate.of(2026, 7, 17),
            LocalDateTime.of(2026, 7, 18, 4, 0) to LocalDate.of(2026, 7, 18),
        ).forEach { (time, expected) ->
            assertEquals("task day at $time", expected, TaskDay.from(time))
        }
    }
}
