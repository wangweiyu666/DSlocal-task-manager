package com.ds.localtaskmanager.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DeadlineFormatterTest {
    @Test
    fun `deadline is consistently displayed to minutes`() {
        listOf(
            "2026-07-20T22:00" to "2026-07-20 22:00",
            "2026-07-20T22:15" to "2026-07-20 22:15",
            "2026-07-20T10:00:00" to "2026-07-20 10:00",
        ).forEach { (input, expected) ->
            assertEquals(input, expected, formatDeadlineForDisplay(input))
        }
    }
}
