package com.ds.localtaskmanager.ui.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.data.history.HistoryDay
import com.ds.localtaskmanager.data.history.HistoryRequirement
import com.ds.localtaskmanager.data.history.HistoryTask
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.result.DailyResultStatus
import com.ds.localtaskmanager.ui.execution.ExecutionUiState
import com.ds.localtaskmanager.ui.execution.TaskDetailScreen
import com.ds.localtaskmanager.ui.execution.TaskDetailTimelineItem
import com.ds.localtaskmanager.ui.theme.DstTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class W23HistoryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun searchFiltersCalendarAndTaskNavigationAreReachable() {
        var state by mutableStateOf(populatedState())
        var openedTask = ""
        composeRule.setContent {
            DstTheme(dynamicColor = false) {
                HistoryScreen(
                    state = state,
                    onSearchChange = { state = state.copy(searchText = it) },
                    onToggleFilters = { state = state.copy(filtersVisible = !state.filtersVisible) },
                    onToggleStatus = {},
                    onToggleSource = {},
                    onRequirementChange = {},
                    onOpenCalendar = { state = state.copy(calendarVisible = true) },
                    onCloseCalendar = { state = state.copy(calendarVisible = false) },
                    onCalendarMonthChange = {},
                    onDateSelected = {},
                    onClearConditions = {},
                    onClearDate = {},
                    onViewPreviousDate = {},
                    onRetry = {},
                    onLoadMore = {},
                    onTaskClick = { openedTask = it.taskId },
                    onDayClick = {},
                )
            }
        }

        composeRule.onNodeWithText("搜索任务、积分组或备注").performTextInput("整理")
        assertEquals("整理", state.searchText)
        composeRule.onNodeWithText("筛选").performClick()
        composeRule.onNodeWithText("待完成").assertIsDisplayed()
        composeRule.onNodeWithText("日历").performClick()
        composeRule.onNodeWithText("2026 年 7 月").assertIsDisplayed()
        composeRule.onNodeWithText("关闭").performClick()
        composeRule.onNodeWithText("整理今日记录").performClick()
        assertEquals("HistoryTask00001", openedTask)
    }

    @Test
    fun historicalDetailIsReadOnlyButOrdinaryNoteRemainsEditable() {
        var note = ""
        val instance = populatedState().days.single().tasks.single().instance.copy(
            status = TaskStatus.PENDING.name,
        )
        composeRule.setContent {
            DstTheme(dynamicColor = false) {
                TaskDetailScreen(
                    state = ExecutionUiState(
                        loading = false,
                        instance = instance,
                        noteDraft = note,
                        canComplete = true,
                    ),
                    onBack = {},
                    onRetry = {},
                    onStepChange = { _, _ -> },
                    onCounterChange = {},
                    onTimerToggle = {},
                    onInformationChange = {},
                    onInformationSave = {},
                    onNoteChange = { note = it },
                    onComplete = {},
                    onUndo = {},
                    onDismissCompletion = {},
                    onDismissError = {},
                    readOnly = true,
                    timeline = listOf(
                        TaskDetailTimelineItem("Completed", "5 points", "2026-07-21 22:00"),
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("task-detail-actions").assertIsNotDisplayed()
        composeRule.onNodeWithText("2026-07-21 22:00").assertIsDisplayed()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("history note")
        assertEquals("history note", note)
    }

    private fun populatedState(): HistoryUiState {
        val instance = TaskInstanceEntity(
            taskId = "HistoryTask00001",
            occurrenceKey = "once",
            name = "整理今日记录",
            description = "",
            taskDate = "2026-07-21",
            deadline = "2026-07-21T22:00",
            groupId = null,
            required = true,
            points = 5,
            sortOrder = null,
            completionMessage = "完成",
            status = TaskStatus.COMPLETED.name,
            completedAtEpochMillis = 1,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
            groupNameSnapshot = "日常",
        )
        return HistoryUiState(
            loading = false,
            calendarMonth = java.time.YearMonth.of(2026, 7),
            calendarDates = setOf("2026-07-21"),
            days = listOf(
                HistoryDay(
                    taskDate = "2026-07-21",
                    resultStatus = DailyResultStatus.COMPLETED,
                    taskCount = 1,
                    completedCount = 1,
                    effectiveCount = 1,
                    netPoints = 5,
                    tasks = listOf(HistoryTask(instance, null, "3/3 步", 5)),
                ),
            ),
        )
    }
}
