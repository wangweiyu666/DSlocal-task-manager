package com.ds.localtaskmanager.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DeadlineFormatterTest {
    @Test
    fun `whole-hour deadline retains zero minutes`() {
        assertEquals(
            "2026-07-20 22:00",
            formatDeadlineForDisplay("2026-07-20T22:00"),
        )
    }

    @Test
    fun `deadline is consistently displayed to minutes`() {
        assertEquals(
            "2026-07-20 22:15",
            formatDeadlineForDisplay("2026-07-20T22:15"),
        )
        assertEquals(
            "2026-07-20 10:00",
            formatDeadlineForDisplay("2026-07-20T10:00:00"),
        )
    }
}
