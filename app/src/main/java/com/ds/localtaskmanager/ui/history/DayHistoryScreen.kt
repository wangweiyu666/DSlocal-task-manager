package com.ds.localtaskmanager.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ds.localtaskmanager.data.history.HistoryTask
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import com.ds.localtaskmanager.domain.result.DailyResultStatus
import com.ds.localtaskmanager.ui.execution.TaskDetailTimelineItem
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ds.localtaskmanager.ui.components.BackNavigationIcon

@Composable
fun DayHistoryRoute(
    viewModel: DayHistoryViewModel,
    onBack: () -> Unit,
    onTaskClick: (TaskInstanceKey) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DayHistoryScreen(state, onBack, viewModel::refresh, onTaskClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayHistoryScreen(
    state: DayHistoryUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onTaskClick: (TaskInstanceKey) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("当日历史") },
                navigationIcon = { BackNavigationIcon(onBack) },
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.errorMessage != null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("当日历史加载失败。", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text("重试") }
            }
            else -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(state.taskDate, style = MaterialTheme.typography.headlineLarge)
                state.result?.global?.let { global ->
                    HistoryCard("当日结果") {
                        Text(dayResultLabel(global.status), style = MaterialTheme.typography.titleLarge)
                        Text(
                            "完成 ${global.requiredCompleted} 项必做 · 未完成 ${global.requiredMissed} 项 · 净积分 ${signed(global.totalPoints)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.result?.groups?.isNotEmpty() == true) {
                    HistoryCard("积分组结果") {
                        state.result.groups.forEachIndexed { index, group ->
                            val groupName = state.tasks.firstOrNull { it.instance.groupId == group.groupId }
                                ?.instance?.groupNameSnapshot ?: "未分组"
                            Row(Modifier.fillMaxWidth()) {
                                Text(groupName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Text("${dayResultLabel(group.status)} · ${signed(group.points)}", style = MaterialTheme.typography.labelLarge)
                            }
                            group.message?.takeIf(String::isNotBlank)?.let {
                                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                            if (index != state.result.groups.lastIndex) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        }
                    }
                }
                HistoryCard("任务") {
                    state.tasks.forEachIndexed { index, task ->
                        DayTaskRow(task, onTaskClick)
                        if (index != state.tasks.lastIndex) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    }
                }
                if (state.revisions.isNotEmpty()) {
                    HistoryCard("结果修订") {
                        state.revisions.forEachIndexed { index, item ->
                            TimelineRow(item)
                            if (index != state.revisions.lastIndex) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DayTaskRow(task: HistoryTask, onTaskClick: (TaskInstanceKey) -> Unit) {
    val instance = task.instance
    Column(
        Modifier.fillMaxWidth().clickable { onTaskClick(TaskInstanceKey(instance.taskId, instance.occurrenceKey)) },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(instance.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(taskStatusLabel(instance.status), style = MaterialTheme.typography.labelMedium)
        }
        Text(
            listOfNotNull(if (instance.required) "必做" else "选做", task.progress, "实际积分 ${signed(task.netPoints)}").joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TimelineRow(item: TaskDetailTimelineItem) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(item.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(item.timestamp, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        item.detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun dayResultLabel(status: DailyResultStatus?): String = when (status) {
    DailyResultStatus.COMPLETED -> "全部完成"
    DailyResultStatus.IN_PROGRESS -> "尚未完成"
    DailyResultStatus.INCOMPLETE -> "有任务未完成"
    DailyResultStatus.OPTIONAL_ONLY -> "仅有选做任务"
    null -> "暂无结果"
}

private fun taskStatusLabel(status: String): String = when (status) {
    TaskStatus.NOT_STARTED.name, TaskStatus.PENDING.name -> "待完成"
    TaskStatus.COMPLETED.name -> "已完成"
    TaskStatus.MISSED.name -> "未完成"
    TaskStatus.CANCELLED.name -> "已撤销"
    else -> "已更新"
}

private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()
