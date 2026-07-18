package com.ds.localtaskmanager.domain

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskDayTest {
    @Test
    fun `time before four belongs to previous task day`() {
        val result = TaskDay.from(LocalDateTime.of(2026, 7, 18, 3, 59))

        assertEquals(LocalDate.of(2026, 7, 17), result)
    }

    @Test
    fun `four o'clock starts a new task day`() {
        val result = TaskDay.from(LocalDateTime.of(2026, 7, 18, 4, 0))

        assertEquals(LocalDate.of(2026, 7, 18), result)
    }
}
