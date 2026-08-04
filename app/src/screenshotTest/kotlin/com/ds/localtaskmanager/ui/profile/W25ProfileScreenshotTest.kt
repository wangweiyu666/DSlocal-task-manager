package com.ds.localtaskmanager.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.ds.localtaskmanager.data.statistics.ClassificationStatistics
import com.ds.localtaskmanager.data.statistics.CompletionSummary
import com.ds.localtaskmanager.data.statistics.GroupStatistics
import com.ds.localtaskmanager.data.statistics.PointsOverview
import com.ds.localtaskmanager.data.statistics.StatisticsDashboard
import com.ds.localtaskmanager.data.statistics.StatusCounts
import com.ds.localtaskmanager.data.statistics.TrendPoint
import com.ds.localtaskmanager.ui.theme.DstTheme

@PreviewTest
@Preview(name = "W25 · 我的统计", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
fun ProfilePopulatedScreenshot() {
    DstTheme {
        ProfileStatisticsContent()
    }
}

internal fun w25ScreenshotDashboard() = StatisticsDashboard(
    domName = "示例 Dom",
    overview = PointsOverview(cumulative = -3, today = 4, sevenDays = -2, thirtyDays = 9),
    trend = listOf(TrendPoint("2026-08-01", 4), TrendPoint("2026-08-02", -7)),
    completion = CompletionSummary(completed = 2, total = 3),
    groups = listOf(
        GroupStatistics("work", "工作", false, 1, 4, CompletionSummary(1, 1)),
        GroupStatistics("archive", "归档", true, 2, -7, CompletionSummary(1, 2)),
    ),
    requirement = listOf(ClassificationStatistics("REQUIRED", "必做", StatusCounts(2, 0, 1))),
    categories = listOf(ClassificationStatistics("DAILY", "每日", StatusCounts(2, 0, 1))),
)

@PreviewTest
@Preview(name = "W25 · 我的空状态", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
fun ProfileEmptyScreenshot() {
    DstTheme {
        ProfileEmptyContent()
    }
}

@Composable
private fun ProfileStatisticsContent() {
    val data = w25ScreenshotDashboard()
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("我的", style = MaterialTheme.typography.headlineLarge)
        Text("来自「${data.domName}」的任务", style = MaterialTheme.typography.bodyLarge)
        OverviewGrid(data.overview)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("积分趋势", style = MaterialTheme.typography.titleMedium)
                TrendChart(data.trend)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("必做完成率", style = MaterialTheme.typography.titleMedium)
                CompletionContent(data.completion)
            }
        }
        GroupCard(data.groups.first(), working = false, onClick = {}, onArchive = {})
    }
}

@Composable
private fun ProfileEmptyContent() {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("我的", style = MaterialTheme.typography.headlineLarge)
        EmptyStatistics()
    }
}
