package com.ds.localtaskmanager.ui.execution

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.execution.ExecutionState
import com.ds.localtaskmanager.ui.theme.DstTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MoodSectionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun midpointTapCommitsNeutralAnswer() {
        val rating = mutableStateOf<Int?>(null)
        composeRule.setContent {
            DstTheme {
                MoodSection(rating.value, "", editable = true, working = false, NoteSaveState.SAVED,
                    onRatingChange = { rating.value = it }, onTextChange = {}, onRetry = {})
            }
        }

        composeRule.onNodeWithText("尚未选择心情").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("心情").performTouchInput {
            down(center)
            up()
        }
        composeRule.runOnIdle { assertEquals(3, rating.value) }
    }

    @Test
    fun draggingToEitherEndSnapsToFivePointScale() {
        val rating = mutableStateOf<Int?>(3)
        composeRule.setContent {
            DstTheme {
                MoodSection(rating.value, "", editable = true, working = false, NoteSaveState.SAVED,
                    onRatingChange = { rating.value = it }, onTextChange = {}, onRetry = {})
            }
        }

        val slider = composeRule.onNodeWithContentDescription("心情")
        slider.performTouchInput {
            swipe(center, bottomRight.copy(y = center.y), durationMillis = 100)
        }
        composeRule.runOnIdle { assertEquals(5, rating.value) }
        slider.performTouchInput {
            swipe(center, bottomLeft.copy(y = center.y), durationMillis = 100)
        }
        composeRule.runOnIdle { assertEquals(1, rating.value) }
    }

    @Test
    fun accessibilityProgressActionCommitsDiscreteRatings() {
        val rating = mutableStateOf<Int?>(null)
        composeRule.setContent {
            DstTheme {
                MoodSection(rating.value, "", editable = true, working = false, NoteSaveState.SAVED,
                    onRatingChange = { rating.value = it }, onTextChange = {}, onRetry = {})
            }
        }

        val mood = composeRule.onNodeWithContentDescription("心情")
        mood.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "未选择"))
        (1..5).forEach { value ->
            mood.performSemanticsAction(SemanticsActions.SetProgress) { it(value.toFloat()) }
            composeRule.runOnIdle { assertEquals(value, rating.value) }
        }

    }

    @Test
    fun everyVisibleTickAcceptsDirectTouch() {
        val rating = mutableStateOf<Int?>(null)
        composeRule.setContent {
            DstTheme {
                MoodSection(rating.value, "", editable = true, working = false, NoteSaveState.SAVED,
                    onRatingChange = { rating.value = it }, onTextChange = {}, onRetry = {})
            }
        }
        val slider = composeRule.onNodeWithContentDescription("心情")
        (0..4).forEach { index ->
            slider.performTouchInput {
                val fraction = 0.05f + index / 4f * 0.9f
                val point = Offset(
                    bottomLeft.x + (bottomRight.x - bottomLeft.x) * fraction,
                    center.y,
                )
                down(point)
                up()
            }
            composeRule.runOnIdle { assertEquals(index + 1, rating.value) }
        }
    }

    @Test
    fun focusedMoodInputKeepsCompletionActionReachable() {
        var hostView: android.view.View? = null
        composeRule.setContent {
            DstTheme {
                hostView = LocalView.current
                TaskDetailScreen(
                    state = ExecutionUiState(
                        loading = false,
                        instance = TaskInstanceEntity(
                            taskId = "mood-ui", occurrenceKey = "once", name = "今天的心情怎么样",
                            description = "", taskDate = "2026-09-06", deadline = null, groupId = null,
                            required = true, points = 10, sortOrder = 0, completionMessage = "完成",
                            status = TaskStatus.PENDING.name, completedAtEpochMillis = null,
                            createdAtEpochMillis = 0, updatedAtEpochMillis = 0, executionKind = "MOOD",
                        ),
                        execution = ExecutionState.Mood(3, "", null), moodRating = 3,
                        requiredStepsComplete = true, executionTargetReached = true, canComplete = true,
                    ),
                    onBack = {}, onRetry = {}, onStepChange = { _, _ -> }, onCounterChange = {},
                    onTimerToggle = {}, onInformationChange = {}, onInformationSave = {}, onNoteChange = {},
                    onComplete = {}, onUndo = {}, onDismissCompletion = {}, onDismissError = {},
                )
            }
        }
        composeRule.onNodeWithText("想说点什么？（选填）").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            hostView?.let { ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) } == true
        }
        composeRule.onNodeWithText("完成任务").assertIsDisplayed()
    }

    @Test
    fun readOnlyMoodShowsAnswerAndTextWithoutEditor() {
        composeRule.setContent {
            DstTheme {
                MoodSection(5, "今天完成得很好", editable = false, working = false, NoteSaveState.SAVED,
                    onRatingChange = {}, onTextChange = {}, onRetry = {})
            }
        }

        composeRule.onNodeWithText("今天完成得很好").assertIsDisplayed()
        composeRule.onAllNodesWithText("想说点什么？（选填）").assertCountEquals(0)
    }
}
