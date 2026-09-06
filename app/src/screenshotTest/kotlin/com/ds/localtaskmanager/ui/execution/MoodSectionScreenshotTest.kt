package com.ds.localtaskmanager.ui.execution

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.ds.localtaskmanager.settings.AppThemeMode
import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.domain.execution.ExecutionState
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.ui.theme.DstTheme

@PreviewTest
@Preview(name = "W32 · 心情未选择", showBackground = true, widthDp = 393, heightDp = 620)
@Composable
fun MoodSectionUnselectedScreenshot() {
    MoodScreenshot(AppThemeMode.LIGHT, rating = null, text = "")
}

@PreviewTest
@Preview(name = "W32 · 心情已选择", showBackground = true, widthDp = 393, heightDp = 520)
@Composable
fun MoodSectionSelectedScreenshot() {
    MoodScreenshot(AppThemeMode.LIGHT, rating = 3, text = "今天按计划完成了任务。")
}

@PreviewTest
@Preview(name = "W32 · 心情只读深色", showBackground = true, widthDp = 393, heightDp = 300)
@Composable
fun MoodSectionReadOnlyDarkScreenshot() {
    DstTheme(themeMode = AppThemeMode.DARK) {
        Surface { MoodSection(5, "今天状态很好。", editable = false, working = false, NoteSaveState.SAVED,
            onRatingChange = {}, onTextChange = {}, onRetry = {}) }
    }
}

@PreviewTest
@Preview(name = "W32 · 心情大字体", showBackground = true, widthDp = 393, heightDp = 620, fontScale = 1.6f)
@Composable
fun MoodSectionLargeFontScreenshot() {
    MoodScreenshot(AppThemeMode.LIGHT, rating = 4, text = "字体放大后仍可阅读。")
}

@PreviewTest
@Preview(name = "W32 · 任务详情心情", showBackground = true, widthDp = 393, heightDp = 1000)
@Composable
fun MoodTaskDetailScreenshot() {
    DstTheme(themeMode = AppThemeMode.LIGHT) {
        TaskDetailScreen(
            state = ExecutionUiState(
                loading = false,
                instance = TaskInstanceEntity(
                    taskId = "mood-preview", occurrenceKey = "2026-09-06", name = "记录今天的心情",
                    description = "完成任务后记录今天的状态。", taskDate = "2026-09-06", deadline = null,
                    groupId = null, required = true, points = 10, sortOrder = 0, completionMessage = "完成",
                    status = TaskStatus.PENDING.name, completedAtEpochMillis = null, createdAtEpochMillis = 0,
                    updatedAtEpochMillis = 0, executionKind = "MOOD",
                ),
                execution = ExecutionState.Mood(4, "今天进展顺利。", null),
                moodRating = 4, moodText = "今天进展顺利。", requiredStepsComplete = true,
                executionTargetReached = true, canComplete = true,
            ),
            onBack = {}, onRetry = {}, onStepChange = { _, _ -> }, onCounterChange = {}, onTimerToggle = {},
            onInformationChange = {}, onInformationSave = {}, onNoteChange = {}, onComplete = {}, onUndo = {},
            onDismissCompletion = {}, onDismissError = {},
        )
    }
}

@Composable
private fun MoodScreenshot(theme: AppThemeMode, rating: Int?, text: String) {
    DstTheme(themeMode = theme) {
        Surface {
            Column(Modifier.padding(20.dp)) {
                MoodSection(rating, text, editable = true, working = false, NoteSaveState.SAVED,
                    onRatingChange = {}, onTextChange = {}, onRetry = {})
            }
        }
    }
}
