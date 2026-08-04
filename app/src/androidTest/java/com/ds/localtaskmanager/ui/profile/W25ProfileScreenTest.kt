package com.ds.localtaskmanager.ui.profile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import com.ds.localtaskmanager.data.statistics.ClassificationStatistics
import com.ds.localtaskmanager.data.statistics.CompletionSummary
import com.ds.localtaskmanager.data.statistics.GroupStatistics
import com.ds.localtaskmanager.data.statistics.PointsOverview
import com.ds.localtaskmanager.data.statistics.StatisticsDashboard
import com.ds.localtaskmanager.data.statistics.StatisticsPeriod
import com.ds.localtaskmanager.data.statistics.TrendPoint
import com.ds.localtaskmanager.ui.theme.DstTheme
import org.junit.Rule
import org.junit.Test

class W25ProfileScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun populatedProfileShowsSignedPointsTrendAndArchivedEntry() {
        composeRule.setContent {
            DstTheme {
                ProfileScreen(
                    state = ProfileUiState(loading = false, dashboard = dashboard()),
                    onPeriod = {}, onRetry = {}, onArchive = {}, onLedger = { _, _, _ -> },
                    onArchivedGroups = {}, onSettings = {},
                )
            }
        }

        composeRule.onNodeWithText("累计积分").assertIsDisplayed()
        composeRule.onNodeWithText("-3").assertIsDisplayed()
        composeRule.onNodeWithText("积分趋势").assertIsDisplayed()
        composeRule.onNodeWithTag("profile-settings").assertIsDisplayed()
        composeRule.onNodeWithTag("profile-list").performScrollToIndex(7)
        composeRule.onNodeWithText("已归档积分组（1）").assertIsDisplayed()
    }

    @Test
    fun profileShowsFailureAndEmptyStates() {
        composeRule.setContent {
            DstTheme {
                ProfileScreen(
                    state = ProfileUiState(loading = false, errorMessage = "查询失败"),
                    onPeriod = {}, onRetry = {}, onArchive = {}, onLedger = { _, _, _ -> },
                    onArchivedGroups = {}, onSettings = {},
                )
            }
        }

        composeRule.onNodeWithText("查询失败").assertIsDisplayed()
        composeRule.onNodeWithText("暂无统计数据").assertIsDisplayed()
        composeRule.onNodeWithText("重试").assertIsDisplayed()
        composeRule.onNodeWithTag("profile-settings").assertIsDisplayed()
    }
}

internal fun w25ProfileDashboard() = StatisticsDashboard(
    domName = "示例 Dom",
    overview = PointsOverview(cumulative = -3, today = 4, sevenDays = -2, thirtyDays = 9),
    trend = listOf(TrendPoint("2026-08-01", 4), TrendPoint("2026-08-02", -7)),
    completion = CompletionSummary(completed = 2, total = 3),
    groups = listOf(
        GroupStatistics("work", "工作", false, 1, 4, CompletionSummary(1, 1)),
        GroupStatistics("archive", "归档", true, 2, -7, CompletionSummary(1, 2)),
    ),
    requirement = listOf(ClassificationStatistics("REQUIRED", "必做", com.ds.localtaskmanager.data.statistics.StatusCounts(2, 0, 1))),
    categories = listOf(ClassificationStatistics("DAILY", "每日", com.ds.localtaskmanager.data.statistics.StatusCounts(2, 0, 1))),
)

private fun dashboard() = w25ProfileDashboard()
