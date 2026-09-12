package com.ds.localtaskmanager.ui.execution

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.ds.localtaskmanager.data.*
import com.ds.localtaskmanager.domain.execution.*
import com.ds.localtaskmanager.settings.AppThemeMode
import com.ds.localtaskmanager.ui.theme.DstTheme

@PreviewTest
@Preview(name = "通知确认", widthDp = 393, heightDp = 900)
@Composable
fun NoticeTaskScreenshot() = ConditionalPreview("NOTICE", false, AppThemeMode.LIGHT)

@PreviewTest
@Preview(name = "单选未选择", widthDp = 393, heightDp = 950)
@Composable
fun ChoiceTaskScreenshot() = ConditionalPreview("CHOICE", false, AppThemeMode.LIGHT)

@PreviewTest
@Preview(name = "单选深色大字体", widthDp = 393, heightDp = 1150, fontScale = 1.6f)
@Composable
fun ChoiceLargeDarkScreenshot() = ConditionalPreview("CHOICE", false, AppThemeMode.DARK)

@PreviewTest
@Preview(name = "选择前隐藏分支", widthDp = 393, heightDp = 1000)
@Composable
fun ConditionalHiddenScreenshot() = ConditionalPreview("STEPS", false, AppThemeMode.LIGHT)

@PreviewTest
@Preview(name = "确认后展开通知分支", widthDp = 393, heightDp = 1150)
@Composable
fun ConditionalExpandedScreenshot() = ConditionalPreview("STEPS", true, AppThemeMode.LIGHT)

@PreviewTest
@Preview(name = "分支深色大字体", widthDp = 393, heightDp = 1400, fontScale = 1.6f)
@Composable
fun ConditionalLargeDarkScreenshot() = ConditionalPreview("STEPS", true, AppThemeMode.DARK)

@Composable
private fun ConditionalPreview(kind: String, expanded: Boolean, theme: AppThemeMode) {
    val options = listOf(ChoiceOption("Option0000000001", "今天休息", 0), ChoiceOption("Option0000000002", "继续今天的练习", 9))
    val config = ExecutionSpec.Choice(options).configJson()
    val notice = "练习结束后请整理桌面，并将物品放回原处。阅读后确认已知晓。"
    val steps = listOf(
        InstanceStepEntity("preview", "once", 0, "选择今天的安排", true, expanded, 0, "Step000000000001", "CHOICE", stepStatus = if (expanded) "CONFIRMED" else "PENDING", executionConfigJson = config, selectedOptionId = options[1].id.takeIf { expanded }),
        InstanceStepEntity("preview", "once", 1, "练习后提醒", true, false, 0, "Step000000000002", "NOTICE", executionConfigJson = ExecutionSpec.Notice(notice).configJson(), conditionStepId = "Step000000000001", conditionOptionId = options[1].id),
    )
    val instance = TaskInstanceEntity("preview", "once", if (kind == "NOTICE") "今日通知" else "今天的安排", "按自己的实际情况选择。", "2026-09-12", null, null, true, 3, 0, "完成", "PENDING", null, 0, 0, executionKind = kind)
    DstTheme(themeMode = theme) {
        TaskDetailScreen(
            state = ExecutionUiState(loading = false, instance = instance, steps = if (kind == "STEPS") steps else emptyList(), execution = when (kind) {
                "NOTICE" -> ExecutionState.Notice(notice)
                "CHOICE" -> ExecutionState.Choice(options, null)
                else -> ExecutionState.Steps(steps.map { it.branchState() })
            }, requiredStepsComplete = kind != "STEPS", executionTargetReached = kind == "NOTICE", canComplete = kind == "NOTICE"),
            onBack = {}, onRetry = {}, onStepChange = { _, _ -> }, onCounterChange = {}, onTimerToggle = {}, onInformationChange = {}, onInformationRetry = {}, onNoteChange = {}, onComplete = {}, onUndo = {}, onDismissCompletion = {}, onDismissError = {},
        )
    }
}
