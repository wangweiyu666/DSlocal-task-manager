package com.ds.localtaskmanager.ui.today

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ds.localtaskmanager.data.TodayTask
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import com.ds.localtaskmanager.ui.formatDeadlineForDisplay
import com.ds.localtaskmanager.sharing.ShareImageService
import com.ds.localtaskmanager.ui.theme.LocalReduceMotion

@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    shareImageService: ShareImageService,
    onTaskClick: (TaskInstanceKey) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resultState by viewModel.resultState.collectAsStateWithLifecycle()
    val reduceMotion = LocalReduceMotion.current
    AnimatedContent(
        targetState = resultState.visible,
        transitionSpec = {
            if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None
            else fadeIn(tween(220)) togetherWith fadeOut(tween(180))
        },
        label = "today-result",
    ) { showingResult ->
        if (showingResult) {
            TodayResultScreen(resultState, shareImageService, viewModel::retryResult, viewModel::closeResult)
        } else {
            TodayContent(state, viewModel::synchronizeInstances, onTaskClick, viewModel::openResult)
        }
    }
}

@Composable
fun TodayContent(
    state: TodayUiState,
    onRetry: () -> Unit,
    onTaskClick: (TaskInstanceKey) -> Unit,
    onOpenResult: () -> Unit = {},
) {
    when {
        state.loading && state.sections.isEmpty() -> LoadingState()
        state.error != null && state.sections.isEmpty() -> ErrorState(state.error, onRetry)
        state.sections.isEmpty() -> ResultPullContainer({ true }, onOpenResult) { TodayEmptyState(state.taskDate, it) }
        else -> TodayList(state, onTaskClick, onOpenResult)
    }
}

@Composable
private fun TodayEmptyState(taskDate: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .fillMaxSize()
            .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 96.dp),
    ) {
        Text("今日", style = MaterialTheme.typography.headlineLarge)
        Text(
            "任务日 $taskDate",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "还没有任务，点击右下角导入。",
            modifier = Modifier.padding(top = 32.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun TodayList(
    state: TodayUiState,
    onTaskClick: (TaskInstanceKey) -> Unit,
    onOpenResult: () -> Unit,
) {
    val listState = rememberLazyListState()
    ResultPullContainer({ !listState.canScrollBackward }, onOpenResult) { modifier ->
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("今日", style = MaterialTheme.typography.headlineLarge)
            Text(
                "任务日 ${state.taskDate}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.sections.isEmpty()) {
            item {
                Text(
                    "还没有任务，点击右下角导入。",
                    modifier = Modifier.padding(top = 32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            TodayCategory.entries.forEach { category ->
                val categorySections = state.sections.filter { it.category == category }
                if (categorySections.isNotEmpty()) {
                    item(key = "category:${category.storageValue}") {
                        Text(
                            category.label,
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    items(categorySections, key = TodayGroupUi::key) { section ->
                        TodayGroupBlock(section, onTaskClick)
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun TodayGroupBlock(
    section: TodayGroupUi,
    onTaskClick: (TaskInstanceKey) -> Unit,
) {
    var expanded by rememberSaveable(section.key) { mutableStateOf(false) }
    val canFold = section.tasks.size > COLLAPSED_TASK_COUNT
    val visibleTasks = if (canFold && !expanded) section.tasks.take(COLLAPSED_TASK_COUNT) else section.tasks
    val sizeAnimation = if (LocalReduceMotion.current) Modifier else Modifier.animateContentSize(tween(200))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(sizeAnimation),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            section.groupName,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium,
        )
        visibleTasks.forEach { task ->
            TaskCard(task, onTaskClick)
        }
        if (canFold) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                color = Color.Transparent,
            ) {
                Text(
                    if (expanded) "收起" else "展开其余 ${section.tasks.size - COLLAPSED_TASK_COUNT} 项",
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: TodayTask,
    onTaskClick: (TaskInstanceKey) -> Unit,
) {
    val instance = task.instance
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTaskClick(TaskInstanceKey(instance.taskId, instance.occurrenceKey)) },
        colors = CardDefaults.cardColors(
            containerColor = if (instance.status == TaskStatus.COMPLETED.name) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(instance.status)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(instance.name, style = MaterialTheme.typography.titleMedium)
                if (instance.singleDayAdjusted) {
                    Text("单日调整", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    buildString {
                        append(if (instance.required) "必做" else "选做")
                        append(" · ${instance.points} 分")
                        instance.deadline?.let { append(" · 截止 ${formatDeadlineForDisplay(it)}") }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                statusLabel(instance.status),
                color = statusColor(instance.status),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun StatusDot(status: String) {
    Surface(
        modifier = Modifier
            .size(10.dp)
            .semantics { contentDescription = statusLabel(status) },
        shape = CircleShape,
        color = statusColor(status),
        content = {},
    )
}

@Composable
private fun statusColor(status: String): Color = when (status) {
    TaskStatus.COMPLETED.name -> MaterialTheme.colorScheme.primary
    TaskStatus.MISSED.name -> MaterialTheme.colorScheme.error
    TaskStatus.NOT_STARTED.name -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.primary
}

private fun statusLabel(status: String): String = when (status) {
    TaskStatus.NOT_STARTED.name -> "未开始"
    TaskStatus.PENDING.name -> "待完成"
    TaskStatus.COMPLETED.name -> "已完成"
    TaskStatus.MISSED.name -> "未完成"
    TaskStatus.CANCELLED.name -> "已撤销"
    else -> status
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = "正在加载今日任务" })
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("今日任务加载失败", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}

private const val COLLAPSED_TASK_COUNT = 3
