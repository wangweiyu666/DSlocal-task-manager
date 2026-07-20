package com.ds.localtaskmanager.ui.today

import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.data.TodayTask
import com.ds.localtaskmanager.domain.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class W22TodayModelsTest {
    @Test
    fun `sections use category order group order and hide cancelled instances`() {
        val tasks = listOf(
            task("temporary", category = "TEMPORARY", groupId = null),
            task("weekly", category = "WEEKLY", groupId = "later", groupCreatedAt = 20),
            task("cancelled", category = "DAILY", status = TaskStatus.CANCELLED.name),
            task("daily-later", category = "DAILY", groupId = "later", groupCreatedAt = 20),
            task("daily-first", category = "DAILY", groupId = "first", groupCreatedAt = 10),
        )

        val sections = buildTodaySections(tasks)

        assertEquals(
            listOf("每日任务:first", "每日任务:later", "每周任务:later", "临时任务:未分组"),
            sections.map { "${it.category.label}:${it.groupName}" },
        )
        assertEquals(false, sections.flatMap { it.tasks }.any { it.instance.name == "cancelled" })
    }

    @Test
    fun `manual order wins then default ordering handles unsorted tasks`() {
        val tasks = listOf(
            task("late optional", required = false, deadline = null, createdAt = 1),
            task("manual second", sortOrder = 20, createdAt = 2),
            task("early required", required = true, deadline = "2026-07-20T10:00:00", createdAt = 3),
            task("manual first", sortOrder = 10, createdAt = 4),
        )

        assertEquals(
            listOf("manual first", "manual second", "early required", "late optional"),
            buildTodaySections(tasks).single().tasks.map { it.instance.name },
        )
    }

    private fun task(
        name: String,
        category: String = "DAILY",
        groupId: String? = "group",
        groupCreatedAt: Long? = 1,
        required: Boolean = true,
        deadline: String? = null,
        sortOrder: Int? = null,
        status: String = TaskStatus.PENDING.name,
        createdAt: Long = 1,
    ): TodayTask = TodayTask(
        instance = TaskInstanceEntity(
            taskId = name.replace(" ", "").padEnd(16, '0').take(16),
            occurrenceKey = "once",
            name = name,
            description = "",
            taskDate = "2026-07-20",
            deadline = deadline,
            groupId = groupId,
            required = required,
            points = 0,
            sortOrder = sortOrder,
            completionMessage = "",
            status = status,
            completedAtEpochMillis = null,
            createdAtEpochMillis = createdAt,
            updatedAtEpochMillis = createdAt,
            category = category,
        ),
        groupName = groupId ?: "未分组",
        groupCreatedAtEpochMillis = groupCreatedAt,
    )
}
