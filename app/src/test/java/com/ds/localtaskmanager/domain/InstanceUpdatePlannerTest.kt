package com.ds.localtaskmanager.domain

import com.ds.localtaskmanager.domain.update.InstanceUpdatePlanner
import com.ds.localtaskmanager.domain.update.InstanceUpdateRequest
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstanceUpdatePlannerTest {
    private val oldDate = LocalDate.parse("2026-07-18")
    private val oldDeadline = LocalDateTime.parse("2026-07-18T12:00")
    private val now = LocalDateTime.parse("2026-07-18T13:00")

    @Test
    fun `missing y preserves old date while later deadline reopens missed task`() {
        val plan = plan(incomingDeadline = LocalDateTime.parse("2026-07-20T20:00"))

        assertEquals(oldDate, plan.taskDate)
        assertEquals(TaskStatus.PENDING, plan.status)
        assertTrue(plan.deadlineExtended)
        assertTrue(plan.reopened)
        assertFalse(plan.dateMoved)
    }

    @Test
    fun `explicit different y is required for date migration`() {
        val inferredOnly = plan(
            inferredDate = LocalDate.parse("2026-07-20"),
            incomingDeadline = LocalDateTime.parse("2026-07-20T20:00"),
        )
        val explicit = plan(
            inferredDate = LocalDate.parse("2026-07-20"),
            explicitDate = LocalDate.parse("2026-07-20"),
            incomingDeadline = LocalDateTime.parse("2026-07-20T20:00"),
        )

        assertEquals(oldDate, inferredOnly.taskDate)
        assertFalse(inferredOnly.dateMoved)
        assertEquals(LocalDate.parse("2026-07-20"), explicit.taskDate)
        assertTrue(explicit.dateMoved)
        assertEquals(TaskStatus.NOT_STARTED, explicit.status)
        assertTrue(explicit.reopened)
    }

    @Test
    fun `later deadline still in past does not reopen and completed never reopens`() {
        val stillPast = plan(incomingDeadline = LocalDateTime.parse("2026-07-18T12:30"))
        val completed = plan(
            oldStatus = TaskStatus.COMPLETED,
            incomingDeadline = LocalDateTime.parse("2026-07-20T20:00"),
        )

        assertEquals(TaskStatus.MISSED, stillPast.status)
        assertTrue(stillPast.deadlineExtended)
        assertFalse(stillPast.reopened)
        assertEquals(TaskStatus.COMPLETED, completed.status)
        assertFalse(completed.reopened)
    }

    @Test
    fun `missing deadline defaults from preserved date and infinite deadline is an extension`() {
        val defaulted = plan(deadlineWasExplicit = false, incomingDeadline = null)
        val infinite = plan(deadlineWasExplicit = true, incomingDeadline = null)

        assertEquals(LocalDateTime.parse("2026-07-19T04:00"), defaulted.deadline)
        assertTrue(defaulted.deadlineExtended)
        assertEquals(TaskStatus.PENDING, defaulted.status)
        assertTrue(infinite.deadlineExtended)
        assertEquals(TaskStatus.PENDING, infinite.status)
    }

    private fun plan(
        oldStatus: TaskStatus = TaskStatus.MISSED,
        inferredDate: LocalDate = oldDate,
        explicitDate: LocalDate? = null,
        incomingDeadline: LocalDateTime?,
        deadlineWasExplicit: Boolean = true,
    ) = InstanceUpdatePlanner.plan(
        InstanceUpdateRequest(
            oldDate = oldDate,
            oldDeadline = oldDeadline,
            oldStatus = oldStatus,
            inferredDate = inferredDate,
            incomingDeadline = incomingDeadline,
            explicitDate = explicitDate,
            deadlineWasExplicit = deadlineWasExplicit,
            restored = false,
            now = now,
        ),
    )
}
