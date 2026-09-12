package com.ds.localtaskmanager.ui.execution

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ds.localtaskmanager.data.InstanceStepEntity
import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.execution.ExecutionState
import com.ds.localtaskmanager.ui.theme.DstTheme
import org.junit.Rule
import org.junit.Test

class StepsSectionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun currentStepUnlocksInOrderAndOnlyOptionalStepsCanBeSkipped() {
        val first = step(0, "第一步", required = true, content = "已填写")
        val second = step(1, "第二步", required = false)
        val state = mutableStateOf(uiState(listOf(first, second)))
        composeRule.setContent {
            DstTheme {
                TaskDetailScreen(
                    state = state.value,
                    onBack = {}, onRetry = {}, onStepChange = { _, _ -> },
                    onStepConfirm = { id -> state.value = state.value.copy(steps = state.value.steps.map { if (it.stepId == id) it.copy(stepStatus = "CONFIRMED", completed = true) else it }) },
                    onStepSkip = { position -> state.value = state.value.copy(steps = state.value.steps.map { if (it.position == position) it.copy(stepStatus = "SKIPPED") else it }) },
                    onCounterChange = {}, onTimerToggle = {}, onInformationChange = { _ -> }, onInformationRetry = {},
                    onNoteChange = {}, onComplete = {}, onUndo = {}, onDismissCompletion = {}, onDismissError = {},
                )
            }
        }
        composeRule.onNodeWithText("2. 第二步").assertIsDisplayed()
        composeRule.onNodeWithText("请先处理前一步").assertIsDisplayed()
        composeRule.onNodeWithText("跳过选做步骤").assertDoesNotExist()
        composeRule.onNodeWithText("完成此步骤").assertIsEnabled().performClick()
        composeRule.onNodeWithText("跳过选做步骤").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("已跳过").assertIsDisplayed()
        composeRule.onNodeWithText("重新处理此步骤").assertIsEnabled().performClick()
    }

    @Test
    fun readOnlyStepsHaveNoEditingActionsAndConfirmedAnswersCanBeUndone() {
        val longAnswer = "a".repeat(80)
        val confirmed = step(0, "已完成步骤", required = true, status = "CONFIRMED", content = longAnswer)
        val state = uiState(listOf(confirmed))
        composeRule.setContent {
            DstTheme {
                TaskDetailScreen(
                    state = state,
                    onBack = {}, onRetry = {}, onStepChange = { _, _ -> }, onStepUndo = {},
                    onCounterChange = {}, onTimerToggle = {}, onInformationChange = { _ -> }, onInformationRetry = {},
                    onNoteChange = {}, onComplete = {}, onUndo = {}, onDismissCompletion = {}, onDismissError = {},
                    readOnly = true,
                )
            }
        }
        composeRule.onNodeWithText("填写：${"a".repeat(60)}…").assertIsDisplayed()
        composeRule.onNodeWithText("展开正文").performClick()
        composeRule.onNodeWithText(longAnswer).assertIsDisplayed()
        composeRule.onNodeWithText("撤销此步骤").assertIsNotEnabled()
        composeRule.onNodeWithText("完成任务").assertDoesNotExist()
    }

    private fun uiState(steps: List<InstanceStepEntity>) = ExecutionUiState(
        loading = false,
        instance = TaskInstanceEntity(
            taskId = "steps-ui-task", occurrenceKey = "once", name = "步骤任务", description = "",
            taskDate = "2026-09-06", deadline = null, groupId = null, required = true, points = 1,
            sortOrder = 0, completionMessage = "完成", status = TaskStatus.PENDING.name,
            completedAtEpochMillis = null, createdAtEpochMillis = 0, updatedAtEpochMillis = 0,
            executionKind = "STEPS",
        ),
        steps = steps, execution = ExecutionState.Steps(steps.map { com.ds.localtaskmanager.domain.execution.StepState(it.stepId, it.position, it.name, it.required, it.completed, it.executionKind) }),
        requiredStepsComplete = false, executionTargetReached = true, canComplete = false,
    )

    private fun step(position: Int, name: String, required: Boolean, status: String = "PENDING", content: String? = null) = InstanceStepEntity(
        taskId = "steps-ui-task", occurrenceKey = "once", position = position, name = name, required = required,
        completed = status == "CONFIRMED", updatedAtEpochMillis = 0, stepId = "StepUi${position}00000000", executionKind = "INFORMATION",
        stepStatus = status, informationContent = content,
    )
}
