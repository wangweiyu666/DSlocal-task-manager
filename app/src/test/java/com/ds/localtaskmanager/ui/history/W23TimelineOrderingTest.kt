package com.ds.localtaskmanager.ui.history

import com.ds.localtaskmanager.data.ResultRevisionEntity
import com.ds.localtaskmanager.ui.execution.TaskDetailTimelineItem
import org.junit.Assert.assertEquals
import org.junit.Test

class W23TimelineOrderingTest {
    @Test
    fun `newer completion is first when displayed times are in the same minute`() {
        val displayedMinute = "2026-07-22 10:15"
        val earlier = TaskDetailTimelineItem(
            title = "earlier",
            detail = null,
            timestamp = displayedMinute,
            sortEpochMillis = 1_753_156_500_000,
        )
        val newer = TaskDetailTimelineItem(
            title = "newer",
            detail = null,
            timestamp = displayedMinute,
            sortEpochMillis = 1_753_156_545_000,
        )

        assertEquals(
            listOf("newer", "earlier"),
            listOf(earlier, newer).sortedNewestFirst().map(TaskDetailTimelineItem::title),
        )
    }

    @Test
    fun `points revision identifies daily group and ungrouped scopes`() {
        val global = revision(scope = "GLOBAL", groupId = null)
        val group = revision(scope = "GROUP", groupId = "work")
        val ungrouped = revision(scope = "GROUP", groupId = null)

        assertEquals("每日积分从 3 分调整为 8 分", revisionPointsDetail(global, emptyMap()))
        assertEquals("积分组「工作」的积分从 3 分调整为 8 分", revisionPointsDetail(group, mapOf("work" to "工作")))
        assertEquals("未分组积分从 3 分调整为 8 分", revisionPointsDetail(ungrouped, emptyMap()))
    }

    private fun revision(scope: String, groupId: String?) = ResultRevisionEntity(
        revisionId = "revision-$scope-$groupId",
        taskDate = "2026-07-22",
        scope = scope,
        groupId = groupId,
        oldStatus = null,
        newStatus = null,
        oldPoints = 3,
        newPoints = 8,
        reason = "TASK_COMPLETED",
        batchId = null,
        relatedTaskIdsJson = "[]",
        createdAtEpochMillis = 0,
    )
}
