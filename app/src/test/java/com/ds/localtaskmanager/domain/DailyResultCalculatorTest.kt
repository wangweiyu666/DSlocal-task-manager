package com.ds.localtaskmanager.domain

import com.ds.localtaskmanager.domain.result.DailyResultCalculator
import com.ds.localtaskmanager.domain.result.DailyResultStatus
import com.ds.localtaskmanager.domain.result.ResultTaskItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyResultCalculatorTest {
    private val calculator = DailyResultCalculator()

    @Test
    fun `required status has strict precedence and optional tasks do not lower it`() {
        assertEquals(DailyResultStatus.COMPLETED, result(task(required = true, status = TaskStatus.COMPLETED)).global?.status)
        assertEquals(
            DailyResultStatus.IN_PROGRESS,
            result(task(true, TaskStatus.COMPLETED), task(true, TaskStatus.PENDING)).global?.status,
        )
        assertEquals(
            DailyResultStatus.INCOMPLETE,
            result(task(true, TaskStatus.PENDING), task(true, TaskStatus.MISSED)).global?.status,
        )
        assertEquals(
            DailyResultStatus.COMPLETED,
            result(task(true, TaskStatus.COMPLETED), task(false, TaskStatus.MISSED)).global?.status,
        )
    }

    @Test
    fun `optional only and no result states are distinct`() {
        assertEquals(DailyResultStatus.OPTIONAL_ONLY, result(task(false, TaskStatus.PENDING)).global?.status)
        assertNull(calculator.calculate(DATE, listOf(task(false, TaskStatus.CANCELLED))))
        assertNull(calculator.calculate(DATE, listOf(task(true, TaskStatus.NOT_STARTED))))
    }

    @Test
    fun `calculator aggregates actual ledger points and group messages`() {
        val snapshot = result(
            task(true, TaskStatus.COMPLETED, groupId = "a", points = 7),
            task(false, TaskStatus.COMPLETED, groupId = "a", points = -2),
            task(false, TaskStatus.PENDING, groupId = null, points = 3),
        )

        assertEquals(8, snapshot.global?.totalPoints)
        assertEquals(5, snapshot.groups.single { it.groupId == "a" }.points)
        assertEquals("done", snapshot.groups.single { it.groupId == "a" }.message)
        assertEquals(DailyResultStatus.OPTIONAL_ONLY, snapshot.groups.single { it.groupId == null }.status)
    }

    private fun result(vararg tasks: ResultTaskItem) =
        checkNotNull(calculator.calculate(DATE, tasks.toList()))

    private fun task(
        required: Boolean,
        status: TaskStatus,
        groupId: String? = "a",
        points: Int = 0,
    ) = ResultTaskItem(
        taskId = "task-${status.name}-$required-$groupId-$points",
        occurrenceKey = "once",
        taskDate = DATE,
        groupId = groupId,
        required = required,
        status = status.name,
        actualPoints = points,
        groupCompleteMessage = "done",
        groupIncompleteMessage = "missed",
    )

    private companion object {
        const val DATE = "2026-07-18"
    }
}
