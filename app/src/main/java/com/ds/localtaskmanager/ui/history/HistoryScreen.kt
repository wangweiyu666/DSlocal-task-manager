package com.ds.localtaskmanager.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.data.history.HistoryDay
import com.ds.localtaskmanager.data.history.HistoryRequirement
import com.ds.localtaskmanager.data.history.HistoryTask
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import com.ds.localtaskmanager.domain.result.DailyResultStatus
import com.ds.localtaskmanager.ui.formatDeadlineForDisplay
import com.ds.localtaskmanager.ui.theme.DstTheme
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun HistoryRoute(
    viewModel: HistoryViewModel,
    onTaskClick: (TaskInstanceKey) -> Unit,
    onDayClick: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    HistoryScreen(
        state = state,
        onSearchChange = viewModel::updateSearch,
        onToggleFilters = viewModel::toggleFilters,
        onToggleStatus = viewModel::toggleStatus,
        onToggleSource = viewModel::toggleSource,
        onRequirementChange = viewModel::setRequirement,
        onOpenCalendar = viewModel::openCalendar,
        onCloseCalendar = viewModel::closeCalendar,
        onCalendarMonthChange = viewModel::changeCalendarMonth,
        onDateSelected = viewModel::selectDate,
        onClearConditions = viewModel::clearConditions,
        onClearDate = viewModel::clearSelectedDate,
        onViewPreviousDate = viewModel::viewPreviousDate,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        onTaskClick = onTaskClick,
        onDayClick = onDayClick,
    )
}

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onSearchChange: (String) -> Unit,
    onToggleFilters: () -> Unit,
    onToggleStatus: (HistoryStatusFilter) -> Unit,
    onToggleSource: (HistorySourceFilter) -> Unit,
    onRequirementChange: (HistoryRequirement) -> Unit,
    onOpenCalendar: () -> Unit,
    onCloseCalendar: () -> Unit,
    onCalendarMonthChange: (Long) -> Unit,
    onDateSelected: (String) -> Unit,
    onClearConditions: () -> Unit,
    onClearDate: () -> Unit,
    onViewPreviousDate: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onTaskClick: (TaskInstanceKey) -> Unit,
    onDayClick: (String) -> Unit,
    previewMode: Boolean = false,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                if (last >= listState.layoutInfo.totalItemsCount - 5) onLoadMore()
            }
    }

    Column(Modifier.fillMaxSize()) {
        HistoryToolbar(state, onSearchChange, onToggleFilters, onOpenCalendar, onClearDate)
        if (state.filtersVisible) {
            FilterPanel(state, onToggleStatus, onToggleSource, onRequirementChange)
        }
        when {
            state.loading -> LoadingContent()
            state.errorMessage != null && state.days.isEmpty() -> ErrorContent(onRetry)
            state.days.isEmpty() -> EmptyContent(state, onClearConditions, onClearDate, onViewPreviousDate)
            previewMode -> Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                state.days.forEach { day -> DaySection(day, state.searchText, onDayClick, onTaskClick) }
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.days, key = HistoryDay::taskDate) { day ->
                    DaySection(day, state.searchText, onDayClick, onTaskClick)
                }
                if (state.loadingMore) {
                    item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                }
            }
        }
    }

    if (state.calendarVisible) {
        HistoryCalendarDialog(
            month = state.calendarMonth,
            recordedDates = state.calendarDates,
            selectedDate = state.selectedDate,
            onMonthChange = onCalendarMonthChange,
            onDateSelected = onDateSelected,
            onDismiss = onCloseCalendar,
        )
    }
}

@Composable
private fun HistoryToolbar(
    state: HistoryUiState,
    onSearchChange: (String) -> Unit,
    onToggleFilters: () -> Unit,
    onOpenCalendar: () -> Unit,
    onClearDate: () -> Unit,
) {
    Column(Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("历史", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
            TextButton(onClick = onOpenCalendar) { Text("日历") }
            TextButton(onClick = onToggleFilters) { Text(if (state.filtersVisible) "收起" else "筛选") }
        }
        OutlinedTextField(
            value = state.searchText,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("搜索任务、积分组或备注") },
            trailingIcon = if (state.searchText.isNotEmpty()) {
                { TextButton(onClick = { onSearchChange("") }) { Text("清除") } }
            } else null,
        )
        state.selectedDate?.let { date ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("正在查看 $date", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = onClearDate) { Text("查看全部") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(
    state: HistoryUiState,
    onToggleStatus: (HistoryStatusFilter) -> Unit,
    onToggleSource: (HistorySourceFilter) -> Unit,
    onRequirementChange: (HistoryRequirement) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("状态", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryStatusFilter.entries.forEach { filter ->
                    FilterChip(filter in state.statusFilters, { onToggleStatus(filter) }, { Text(filter.label) })
                }
            }
            Text("类型", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    HistoryRequirement.ALL to "全部",
                    HistoryRequirement.REQUIRED to "必做",
                    HistoryRequirement.OPTIONAL to "选做",
                ).forEach { (value, label) ->
                    FilterChip(value == state.requirement, { onRequirementChange(value) }, { Text(label) })
                }
            }
            Text("来源", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistorySourceFilter.entries.forEach { filter ->
                    FilterChip(filter in state.sourceFilters, { onToggleSource(filter) }, { Text(filter.label) })
                }
            }
        }
    }
}

@Composable
private fun DaySection(
    day: HistoryDay,
    searchText: String,
    onDayClick: (String) -> Unit,
    onTaskClick: (TaskInstanceKey) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column {
            Column(
                Modifier.fillMaxWidth().clickable { onDayClick(day.taskDate) }.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTaskDate(day.taskDate), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    Text(resultLabel(day.resultStatus), color = resultColor(day.resultStatus), style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    "已完成 ${day.completedCount}/${day.effectiveCount} · 净积分 ${signed(day.netPoints)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            day.tasks.forEachIndexed { index, task ->
                HorizontalDivider()
                HistoryTaskRow(task, searchText, onTaskClick)
                if (index == day.tasks.lastIndex) Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun HistoryTaskRow(task: HistoryTask, searchText: String, onTaskClick: (TaskInstanceKey) -> Unit) {
    val instance = task.instance
    Column(
        Modifier.fillMaxWidth().clickable { onTaskClick(TaskInstanceKey(instance.taskId, instance.occurrenceKey)) }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(highlight(instance.name, searchText), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(statusLabel(instance.status), color = statusColor(instance.status), style = MaterialTheme.typography.labelMedium)
        }
        val metadata = buildList {
            add(if (instance.required) "必做" else "选做")
            add(sourceLabel(instance.category))
            instance.groupNameSnapshot?.takeIf(String::isNotBlank)?.let(::add)
            task.progress?.let(::add)
            instance.deadline?.let { add("截止 ${formatDeadlineForDisplay(it)}") }
        }.joinToString(" · ")
        Text(metadata, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (task.netPoints != 0) Text("实际积分 ${signed(task.netPoints)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun HistoryCalendarDialog(
    month: YearMonth,
    recordedDates: Set<String>,
    selectedDate: String?,
    onMonthChange: (Long) -> Unit,
    onDateSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onMonthChange(-1) }) { Text("上月") }
                Text("${month.year} 年 ${month.monthValue} 月", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { onMonthChange(1) }) { Text("下月") }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                }
                calendarCells(month).chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { date ->
                            Box(Modifier.weight(1f).height(44.dp), contentAlignment = Alignment.Center) {
                                if (date != null) {
                                    val value = date.toString()
                                    TextButton(onClick = { onDateSelected(value) }, enabled = value in recordedDates) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(date.dayOfMonth.toString(), fontWeight = if (value == selectedDate) FontWeight.Bold else FontWeight.Normal)
                                            Text(if (value in recordedDates) "•" else "", color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable private fun LoadingContent() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@Composable
private fun ErrorContent(onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("历史记录加载失败。", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun EmptyContent(
    state: HistoryUiState,
    onClearConditions: () -> Unit,
    onClearDate: () -> Unit,
    onViewPreviousDate: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when {
                state.selectedDate != null -> "这一天没有任务。"
                state.hasActiveConditions -> "没有符合条件的任务。"
                else -> "还没有历史任务。"
            },
            style = MaterialTheme.typography.titleLarge,
        )
        if (state.hasActiveConditions) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = if (state.selectedDate != null) onViewPreviousDate else onClearConditions) {
                Text(if (state.selectedDate != null) "查看上一条记录" else "清除条件")
            }
            if (state.selectedDate != null) TextButton(onClick = onClearDate) { Text("查看全部") }
        }
    }
}

private fun calendarCells(month: YearMonth): List<LocalDate?> {
    val leading = month.atDay(1).dayOfWeek.value - 1
    val values = MutableList<LocalDate?>(leading) { null }
    (1..month.lengthOfMonth()).forEach { values += month.atDay(it) }
    while (values.size % 7 != 0) values += null
    return values
}

private fun highlight(text: String, query: String) = buildAnnotatedString {
    val index = text.indexOf(query, ignoreCase = true)
    if (query.isBlank() || index < 0) append(text) else {
        append(text.substring(0, index))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(index, index + query.length)) }
        append(text.substring(index + query.length))
    }
}

private fun formatTaskDate(value: String): String = LocalDate.parse(value).format(DateTimeFormatter.ofPattern("M 月 d 日 EEEE"))
private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()
private fun resultLabel(status: DailyResultStatus?): String = when (status) {
    DailyResultStatus.COMPLETED -> "全部完成"
    DailyResultStatus.IN_PROGRESS -> "尚未完成"
    DailyResultStatus.INCOMPLETE -> "有任务未完成"
    DailyResultStatus.OPTIONAL_ONLY -> "仅有选做任务"
    null -> "暂无结果"
}
@Composable private fun resultColor(status: DailyResultStatus?) = when (status) {
    DailyResultStatus.INCOMPLETE -> MaterialTheme.colorScheme.error
    DailyResultStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
private fun statusLabel(status: String): String = when (status) {
    TaskStatus.NOT_STARTED.name, TaskStatus.PENDING.name -> "待完成"
    TaskStatus.COMPLETED.name -> "已完成"
    TaskStatus.MISSED.name -> "未完成"
    TaskStatus.CANCELLED.name -> "已撤销"
    else -> "任务记录已更新"
}
@Composable private fun statusColor(status: String) = when (status) {
    TaskStatus.COMPLETED.name -> MaterialTheme.colorScheme.primary
    TaskStatus.MISSED.name -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
private fun sourceLabel(category: String): String = when (category) {
    "DAILY" -> "每日"
    "WEEKLY" -> "每周"
    else -> "临时"
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun HistoryPopulatedPreview() {
    DstTheme {
        PopulatedHistoryPreviewContent()
    }
}

@Preview(name = "历史 · 空状态", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun HistoryEmptyPreview() {
    DstTheme { HistoryPreviewContent(HistoryUiState(loading = false)) }
}

@Preview(name = "历史 · 筛选无结果", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun HistoryFilteredEmptyPreview() {
    DstTheme {
        HistoryPreviewContent(HistoryUiState(loading = false, searchText = "不存在的任务"))
    }
}

@Composable
fun PopulatedHistoryPreviewContent() {
    HistoryPreviewContent(
        HistoryUiState(
                loading = false,
                days = listOf(
                    HistoryDay(
                        taskDate = "2026-07-21",
                        resultStatus = DailyResultStatus.COMPLETED,
                        taskCount = 1,
                        completedCount = 1,
                        effectiveCount = 1,
                        netPoints = 5,
                        tasks = listOf(
                            HistoryTask(
                                instance = TaskInstanceEntity(
                                    taskId = "PreviewTask00001", occurrenceKey = "once", name = "整理今日记录",
                                    description = "", taskDate = "2026-07-21", deadline = "2026-07-21T22:00",
                                    groupId = "PreviewGroup001", required = true, points = 5, sortOrder = null,
                                    completionMessage = "已完成", status = TaskStatus.COMPLETED.name,
                                    completedAtEpochMillis = 1, createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
                                    groupNameSnapshot = "日常",
                                ),
                                note = null, progress = "3/3 步", netPoints = 5,
                            ),
                        ),
                    ),
                ),
            ),
        )
}

@Composable
fun HistoryPreviewContent(state: HistoryUiState) {
    HistoryScreen(
            state = state,
            onSearchChange = {}, onToggleFilters = {}, onToggleStatus = {}, onToggleSource = {},
            onRequirementChange = {}, onOpenCalendar = {}, onCloseCalendar = {}, onCalendarMonthChange = {},
            onDateSelected = {}, onClearConditions = {}, onClearDate = {}, onViewPreviousDate = {}, onRetry = {}, onLoadMore = {},
            onTaskClick = {}, onDayClick = {},
            previewMode = true,
    )
}
