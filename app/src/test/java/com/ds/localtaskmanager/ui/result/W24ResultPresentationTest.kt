package com.ds.localtaskmanager.ui.result

import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.result.DailyResultSnapshot
import com.ds.localtaskmanager.domain.result.DailyResultStatus
import com.ds.localtaskmanager.domain.result.GlobalDailyResult
import com.ds.localtaskmanager.domain.result.GroupDailyResult
import com.ds.localtaskmanager.domain.result.ResultTaskItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class W24ResultPresentationTest {
    @Test
    fun presentationAndPlainTextContainOnlyShareableFields() {
        val snapshot = snapshot()
        val result = snapshot.toPresentation()
        val text = result.toPlainText()

        assertEquals("2026年7月22日 星期三", result.dateLabel)
        assertEquals(listOf("先完成", "后完成"), result.groups.single().tasks.map { it.name })
        assertTrue(text.contains("来自 Dom 测试"))
        assertTrue(text.contains("先完成｜必做 · 已完成 · +5"))
        assertFalse(text.contains("TaskA0000000001"))
        assertFalse(text.contains("普通备注"))
    }

    @Test
    fun pointFormattingKeepsZeroVisible() {
        assertEquals("+5", formatPoints(5))
        assertEquals("0", formatPoints(0))
    }

    private fun snapshot(): DailyResultSnapshot {
        val tasks = listOf(
            task("TaskB0000000001", "后完成", 20, 3),
            task("TaskA0000000001", "先完成", 10, 5),
        )
        return DailyResultSnapshot(
            taskDate = "2026-07-22",
            global = GlobalDailyResult("2026-07-22", DailyResultStatus.COMPLETED, 8, 2, 0, 0, 0, "g"),
            groups = listOf(GroupDailyResult("Group0000000001", DailyResultStatus.COMPLETED, 8, 2, 0, 0, 0, "完成文案", "工作", 1, "f")),
            tasks = tasks,
            domName = "Dom 测试",
        )
    }

    private fun task(id: String, name: String, order: Int, points: Int) = ResultTaskItem(
        taskId = id,
        occurrenceKey = "once",
        taskDate = "2026-07-22",
        groupId = "Group0000000001",
        required = true,
        status = TaskStatus.COMPLETED.name,
        actualPoints = points,
        groupCompleteMessage = "完成文案",
        groupIncompleteMessage = "未完成文案",
        taskName = name,
        sortOrder = order,
        groupName = "工作",
        groupCreatedAtEpochMillis = 1,
    )
}
