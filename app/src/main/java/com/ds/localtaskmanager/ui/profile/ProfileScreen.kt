package com.ds.localtaskmanager.ui.profile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ds.localtaskmanager.R
import com.ds.localtaskmanager.data.statistics.ClassificationStatistics
import com.ds.localtaskmanager.data.statistics.CompletionSummary
import com.ds.localtaskmanager.data.statistics.GroupStatistics
import com.ds.localtaskmanager.data.statistics.PointsOverview
import com.ds.localtaskmanager.data.statistics.StatisticsPeriod
import com.ds.localtaskmanager.data.statistics.TrendPoint
import com.ds.localtaskmanager.ui.theme.LocalReduceMotion
import kotlin.math.roundToInt

@Composable
fun ProfileRoute(
    viewModel: ProfileViewModel,
    onLedger: (StatisticsPeriod, String?, Boolean) -> Unit,
    onArchivedGroups: (StatisticsPeriod) -> Unit,
    onSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    ProfileScreen(
        state = state,
        onPeriod = viewModel::selectPeriod,
        onRetry = viewModel::refresh,
        onArchive = { group -> group.groupId?.let { viewModel.setArchived(it, true) } },
        onLedger = onLedger,
        onArchivedGroups = onArchivedGroups,
        onSettings = onSettings,
    )
}

@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onPeriod: (StatisticsPeriod) -> Unit,
    onRetry: () -> Unit,
    onArchive: (GroupStatistics) -> Unit,
    onLedger: (StatisticsPeriod, String?, Boolean) -> Unit,
    onArchivedGroups: (StatisticsPeriod) -> Unit,
    onSettings: () -> Unit,
) {
    var archiveTarget by remember { mutableStateOf<GroupStatistics?>(null) }
    val dashboard = state.dashboard
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("profile-list"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ProfileHeader(dashboard?.domName, onSettings)
        }
        if (state.loading && dashboard == null) {
            item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else {
            state.errorMessage?.let { message ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onRetry) { Text("重试") }
                        }
                    }
                }
            }
            dashboard?.let { data ->
                item {
                    SectionTitle("统计范围")
                    PeriodSelector(state.period, onPeriod)
                }
                item { OverviewGrid(data.overview) }
                item {
                    StatisticCard("积分趋势") {
                        TrendChart(data.trend)
                    }
                }
                item {
                    StatisticCard("必做完成率") {
                        CompletionContent(data.completion)
                    }
                }
                item {
                    SectionTitle("积分组")
                    Text("按所选周期净积分排序", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(data.groups.filterNot(GroupStatistics::archived), key = { it.groupId ?: "ungrouped" }) { group ->
                    GroupCard(
                        group = group,
                        working = state.workingGroupId == group.groupId,
                        onClick = { onLedger(state.period, group.groupId, group.groupId == null) },
                        onArchive = if (group.groupId == null) null else { { archiveTarget = group } },
                    )
                }
                item {
                    OutlinedButton(onClick = { onArchivedGroups(state.period) }, modifier = Modifier.fillMaxWidth()) {
                        Text("已归档积分组（${data.groups.count(GroupStatistics::archived)}）")
                    }
                }
                item {
                    StatisticCard("任务分类") {
                        Text("按要求", style = MaterialTheme.typography.titleSmall)
                        data.requirement.forEach { ClassificationRow(it) }
                        HorizontalDivider()
                        Text("按来源", style = MaterialTheme.typography.titleSmall)
                        data.categories.forEach { ClassificationRow(it) }
                    }
                }
                item {
                    Button(
                        onClick = { onLedger(state.period, null, false) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("查看积分流水") }
                }
            } ?: item {
                EmptyStatistics()
            }
        }
    }

    archiveTarget?.let { group ->
        AlertDialog(
            onDismissRequest = { archiveTarget = null },
            title = { Text("归档「${group.name}」？") },
            text = { Text("仅从主要统计列表隐藏，不影响任务、积分和历史记录。") },
            dismissButton = { TextButton(onClick = { archiveTarget = null }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    archiveTarget = null
                    onArchive(group)
                }) { Text("确认归档") }
            },
        )
    }
}

@Composable
internal fun ProfileHeader(domName: String?, onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("我的", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
        IconButton(onClick = onSettings, modifier = Modifier.testTag("profile-settings")) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = "设置",
            )
        }
    }
    domName?.let {
        Text("来自「$it」的任务", style = MaterialTheme.typography.bodyLarge)
    } ?: Text("积分与统计", style = MaterialTheme.typography.bodyLarge)
}

@Composable
internal fun OverviewGrid(overview: PointsOverview) {
    val singleColumn = LocalDensity.current.fontScale >= 1.3f
    val values = listOf(
        "累计积分" to overview.cumulative,
        "今日积分" to overview.today,
        "近 7 日" to overview.sevenDays,
        "近 30 日" to overview.thirtyDays,
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (singleColumn) {
            values.forEach { (label, value) -> OverviewCard(label, value, Modifier.fillMaxWidth()) }
        } else {
            values.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (label, value) -> OverviewCard(label, value, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(label: String, value: Int, modifier: Modifier) {
    val reducedMotion = LocalReduceMotion.current
    val animated by animateIntAsState(value, if (reducedMotion) snap() else tween(250), label = "points")
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(signed(animated), style = MaterialTheme.typography.headlineMedium, color = pointsColor(animated))
        }
    }
}

@Composable
private fun PeriodSelector(selected: StatisticsPeriod, onSelect: (StatisticsPeriod) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatisticsPeriod.entries.forEach { period ->
            FilterChip(
                selected = selected == period,
                onClick = { onSelect(period) },
                label = { Text(period.label) },
            )
        }
    }
}

@Composable
internal fun TrendChart(points: List<TrendPoint>) {
    if (points.isEmpty()) {
        Text("完成任务后将在这里生成趋势。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val width = (points.size.coerceAtLeast(7) * 48).dp
    val color = MaterialTheme.colorScheme.primary
    val neutral = MaterialTheme.colorScheme.outlineVariant
    val summary = points.joinToString("，") { "${it.label} ${signed(it.points)} 分" }
    val chartScroll = rememberScrollState()
    LaunchedEffect(chartScroll.maxValue) {
        if (chartScroll.maxValue > 0) chartScroll.scrollTo(chartScroll.maxValue)
    }
    Box(Modifier.fillMaxWidth().horizontalScroll(chartScroll)) {
        Canvas(
            Modifier.width(width).height(150.dp).semantics { contentDescription = "积分趋势：$summary" },
        ) {
            val min = minOf(0, points.minOf(TrendPoint::points))
            val max = maxOf(0, points.maxOf(TrendPoint::points))
            val span = (max - min).coerceAtLeast(1)
            fun y(value: Int) = size.height - ((value - min).toFloat() / span * size.height)
            drawLine(neutral, Offset(0f, y(0)), Offset(size.width, y(0)), strokeWidth = 2f)
            if (points.size == 1) {
                drawCircle(color, radius = 6f, center = Offset(size.width / 2f, y(points.single().points)))
            } else {
                val path = Path()
                points.forEachIndexed { index, point ->
                    val x = index.toFloat() / (points.lastIndex) * size.width
                    val pointY = y(point.points)
                    if (index == 0) path.moveTo(x, pointY) else path.lineTo(x, pointY)
                    drawCircle(color, radius = 5f, center = Offset(x, pointY))
                }
                drawPath(path, color, style = Stroke(width = 4f, cap = StrokeCap.Round))
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(points.first().label, style = MaterialTheme.typography.labelSmall)
        if (points.size > 1) Text(points.last().label, style = MaterialTheme.typography.labelSmall)
    }
    Text(
        "合计 ${signed(points.sumOf(TrendPoint::points))} 分 · 最高 ${signed(points.maxOf(TrendPoint::points))} · 最低 ${signed(points.minOf(TrendPoint::points))}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun CompletionContent(summary: CompletionSummary) {
    val target = summary.fraction ?: 0f
    val reducedMotion = LocalReduceMotion.current
    val animated by animateFloatAsState(target, if (reducedMotion) snap() else tween(250), label = "completion")
    Text(
        summary.fraction?.let { "${(it * 100).roundToInt()}%" } ?: "—",
        style = MaterialTheme.typography.headlineMedium,
    )
    LinearProgressIndicator(
        progress = { animated },
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = if (summary.total == 0) "暂无可计算的必做任务" else "完成 ${summary.completed} 个，共 ${summary.total} 个"
        },
    )
    Text(
        if (summary.total == 0) "暂无可计算的必做任务" else "已完成 ${summary.completed} / ${summary.total}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun GroupCard(
    group: GroupStatistics,
    working: Boolean,
    onClick: () -> Unit,
    onArchive: (() -> Unit)?,
) {
    Card(Modifier.fillMaxWidth().clickable(enabled = !working, onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(signed(group.points), color = pointsColor(group.points), style = MaterialTheme.typography.titleLarge)
            }
            Text(
                group.completion.fraction?.let { "必做完成率 ${(it * 100).roundToInt()}%" } ?: "必做完成率 —",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            onArchive?.let {
                TextButton(onClick = it, enabled = !working, modifier = Modifier.align(Alignment.End)) { Text("归档") }
            }
        }
    }
}

@Composable
private fun ClassificationRow(item: ClassificationStatistics) {
    val counts = item.counts
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(item.label, style = MaterialTheme.typography.bodyLarge)
        Text(
            "已完成 ${counts.completed} · 未完成 ${counts.missed} · 待完成 ${counts.pending} · 已撤销 ${counts.cancelled}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun EmptyStatistics() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("暂无统计数据", style = MaterialTheme.typography.titleMedium)
            Text("完成任务后将在这里生成趋势。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleLarge)

@Composable
private fun StatisticCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private val StatisticsPeriod.label: String
    get() = when (this) {
        StatisticsPeriod.SEVEN_DAYS -> "近 7 日"
        StatisticsPeriod.THIRTY_DAYS -> "近 30 日"
        StatisticsPeriod.ALL -> "全部"
    }

@Composable
private fun pointsColor(value: Int) = when {
    value > 0 -> MaterialTheme.colorScheme.primary
    value < 0 -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()
