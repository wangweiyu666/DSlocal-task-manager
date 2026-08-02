package com.ds.localtaskmanager.ui.execution

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ds.localtaskmanager.data.InstanceStepEntity
import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.execution.CounterAction
import com.ds.localtaskmanager.domain.execution.ExecutionState
import com.ds.localtaskmanager.ui.formatDeadlineForDisplay
import java.time.LocalDateTime
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

data class TaskDetailTimelineItem(
    val title: String,
    val detail: String?,
    val timestamp: String,
    val sortEpochMillis: Long = 0L,
)

@Composable
fun TaskDetailRoute(
    viewModel: ExecutionViewModel,
    shareImageService: com.ds.localtaskmanager.sharing.ShareImageService,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var shareImage by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.ds.localtaskmanager.sharing.GeneratedShareImage?>(null) }

    fun leave() = viewModel.flushNote(onBack)
    BackHandler(onBack = ::leave)

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.onForegroundLost()
                viewModel.flushNote {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onForegroundLost()
        }
    }
    KeepScreenOn(view, state.timerRunning)

    TaskDetailScreen(
        state = state,
        onBack = ::leave,
        onRetry = { viewModel.refresh() },
        onStepChange = viewModel::setStep,
        onCounterChange = viewModel::setCounter,
        onTimerToggle = { if (state.timerRunning) viewModel.pauseTimer() else viewModel.startTimer() },
        onInformationChange = viewModel::updateInformationDraft,
        onInformationSave = viewModel::saveInformationDraft,
        onNoteChange = viewModel::updateNoteDraft,
        onComplete = viewModel::complete,
        onUndo = viewModel::undoCompletion,
        onDismissCompletion = viewModel::clearCompletionFeedback,
        onDismissError = viewModel::clearError,
        onCopyInformation = {
            viewModel.prepareInformationForShare { body ->
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(body))
                android.widget.Toast.makeText(context, "正文已复制", android.widget.Toast.LENGTH_SHORT).show()
            }
        },
        onShareInformation = {
            viewModel.prepareInformationForShare { body ->
                val instance = state.instance ?: return@prepareInformationForShare
                scope.launch {
                    runCatching { shareImageService.generateInformation(instance.name, instance.taskDate, body) }
                        .onSuccess { shareImage = it }
                        .onFailure { android.widget.Toast.makeText(context, it.message ?: "暂时无法生成图片", android.widget.Toast.LENGTH_SHORT).show() }
                }
            }
        },
    )
    shareImage?.let {
        com.ds.localtaskmanager.ui.sharing.SharePreviewDialog(it, shareImageService, sensitive = true) { shareImage = null }
    }
}

@Composable
private fun KeepScreenOn(view: View, enabled: Boolean) {
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    state: ExecutionUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onStepChange: (Int, Boolean) -> Unit,
    onCounterChange: (Int) -> Unit,
    onTimerToggle: () -> Unit,
    onInformationChange: (String) -> Unit,
    onInformationSave: () -> Unit,
    onNoteChange: (String) -> Unit,
    onComplete: () -> Unit,
    onUndo: () -> Unit,
    onDismissCompletion: () -> Unit,
    onDismissError: () -> Unit,
    onCopyInformation: () -> Unit = {},
    onShareInformation: () -> Unit = {},
    readOnly: Boolean = false,
    title: String = "任务详情",
    timeline: List<TaskDetailTimelineItem> = emptyList(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showUndoConfirmation by remember { mutableStateOf(false) }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            onDismissError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
        bottomBar = {
            if (!readOnly) state.instance?.let { instance ->
                when (instance.status) {
                    TaskStatus.PENDING.name -> ActionBar {
                        Button(
                            onClick = onComplete,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.canComplete && !state.working,
                        ) {
                            Text(if (state.working) "处理中…" else "完成任务")
                        }
                    }
                    TaskStatus.COMPLETED.name -> ActionBar {
                        OutlinedButton(
                            onClick = { showUndoConfirmation = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.working,
                        ) { Text("撤销完成") }
                    }
                }
            }
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.instance == null -> ErrorContent(onRetry, Modifier.padding(padding))
            else -> TaskDetailContent(
                state = state,
                modifier = Modifier.padding(padding),
                onStepChange = onStepChange,
                onCounterChange = onCounterChange,
                onTimerToggle = onTimerToggle,
                onInformationChange = onInformationChange,
                onInformationSave = onInformationSave,
                onCopyInformation = onCopyInformation,
                onShareInformation = onShareInformation,
                onNoteChange = onNoteChange,
                readOnly = readOnly,
                timeline = timeline,
            )
        }
    }

    state.completionFeedback?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissCompletion,
            confirmButton = { TextButton(onClick = onDismissCompletion) { Text("知道了") } },
            title = { Text("任务已完成") },
            text = { Text(message) },
        )
    }
    if (showUndoConfirmation) {
        val expired = state.instance?.deadline?.let { deadline ->
            runCatching { !LocalDateTime.now().isBefore(LocalDateTime.parse(deadline)) }.getOrDefault(false)
        } == true
        AlertDialog(
            onDismissRequest = { showUndoConfirmation = false },
            dismissButton = { TextButton(onClick = { showUndoConfirmation = false }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    showUndoConfirmation = false
                    onUndo()
                }) { Text("确认撤销") }
            },
            title = { Text("撤销完成？") },
            text = {
                Text(
                    if (expired) {
                        "将扣回本次积分。任务已过截止时间，撤销后会变为未完成，不能继续执行。"
                    } else {
                        "将扣回本次积分，并恢复为待完成状态；当前步骤和执行进度会保留。"
                    },
                )
            },
        )
    }
}

@Composable
private fun TaskDetailContent(
    state: ExecutionUiState,
    modifier: Modifier,
    onStepChange: (Int, Boolean) -> Unit,
    onCounterChange: (Int) -> Unit,
    onTimerToggle: () -> Unit,
    onInformationChange: (String) -> Unit,
    onInformationSave: () -> Unit,
    onCopyInformation: () -> Unit,
    onShareInformation: () -> Unit,
    onNoteChange: (String) -> Unit,
    readOnly: Boolean,
    timeline: List<TaskDetailTimelineItem>,
) {
    val instance = requireNotNull(state.instance)
    val editable = !readOnly && instance.status == TaskStatus.PENDING.name
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 28.dp)
            .animateContentSize(tween(180)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(instance)
        if (instance.description.isNotBlank()) {
            DetailCard("任务说明") { Text(instance.description, style = MaterialTheme.typography.bodyLarge) }
        }
        if (state.steps.isNotEmpty()) {
            DetailCard("任务步骤") {
                state.steps.forEachIndexed { index, step ->
                    StepRow(step, editable, state.working, onStepChange)
                    if (index != state.steps.lastIndex) HorizontalDivider()
                }
            }
        }
        ExecutionSection(
            execution = state.execution,
            editable = editable,
            working = state.working,
            informationDraft = state.informationDraft,
            onCounterChange = onCounterChange,
            onTimerToggle = onTimerToggle,
            timerRunning = state.timerRunning,
            onInformationChange = onInformationChange,
            onInformationSave = onInformationSave,
            onCopyInformation = onCopyInformation,
            onShareInformation = onShareInformation,
        )
        DetailCard("普通备注") {
            OutlinedTextField(
                value = state.noteDraft,
                onValueChange = onNoteChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text("仅保存在本机，不会展示给 Dom") },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when (state.noteSaveState) {
                    NoteSaveState.SAVED -> "已保存"
                    NoteSaveState.SAVING -> "保存中…"
                    NoteSaveState.ERROR -> "保存失败，继续编辑或返回时将重试"
                },
                color = if (state.noteSaveState == NoteSaveState.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (timeline.isNotEmpty()) {
            DetailCard("历史记录") {
                timeline.forEachIndexed { index, item ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(item.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text(item.timestamp, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                        item.detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (index != timeline.lastIndex) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
            }
        }
        if (!readOnly && editable && !state.canComplete) {
            Text(
                completionHint(state),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Header(instance: TaskInstanceEntity) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(instance.name, style = MaterialTheme.typography.headlineLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(instance.status)
            Text(if (instance.required) "必做" else "选做", style = MaterialTheme.typography.labelLarge)
            Text("${instance.points} 分", style = MaterialTheme.typography.labelLarge)
        }
        Text(
            buildString {
                append("任务日 ${instance.taskDate}")
                instance.deadline?.let { append(" · 截止 ${formatDeadlineForDisplay(it)}") }
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StatusPill(status: String) {
    Surface(
        color = when (status) {
            TaskStatus.COMPLETED.name -> MaterialTheme.colorScheme.tertiaryContainer
            TaskStatus.MISSED.name -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.primaryContainer
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Text(statusLabel(status), modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun DetailCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StepRow(
    step: InstanceStepEntity,
    editable: Boolean,
    working: Boolean,
    onStepChange: (Int, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = step.completed,
            onCheckedChange = { onStepChange(step.position, it) },
            enabled = editable && !working,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(step.name, style = MaterialTheme.typography.bodyLarge)
            if (step.required) Text("必需步骤", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ExecutionSection(
    execution: ExecutionState?,
    editable: Boolean,
    working: Boolean,
    informationDraft: String,
    onCounterChange: (Int) -> Unit,
    onTimerToggle: () -> Unit,
    timerRunning: Boolean,
    onInformationChange: (String) -> Unit,
    onInformationSave: () -> Unit,
    onCopyInformation: () -> Unit,
    onShareInformation: () -> Unit,
) {
    when (execution) {
        is ExecutionState.Counter -> DetailCard("计数") {
            Text("${execution.value} / ${execution.target}", style = MaterialTheme.typography.titleLarge)
            if (execution.action == CounterAction.SLIDER) {
                var sliderValue by remember(execution.value) { mutableFloatStateOf(execution.value.toFloat()) }
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { onCounterChange(sliderValue.roundToInt()) },
                    valueRange = 0f..execution.target.toFloat(),
                    steps = (execution.target - 1).coerceAtLeast(0),
                    enabled = editable && !working,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { onCounterChange(execution.value - 1) },
                        enabled = editable && !working && execution.value > 0,
                    ) { Text("−1") }
                    Button(
                        onClick = { onCounterChange(execution.value + 1) },
                        enabled = editable && !working && execution.value < execution.target,
                    ) { Text("+1") }
                }
            }
        }
        is ExecutionState.Timer -> DetailCard("计时") {
            Text(
                "${formatDuration(execution.elapsedMillis)} / ${formatDuration(execution.targetMillis)}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.headlineLarge,
            )
            LinearProgressIndicator(
                progress = { (execution.elapsedMillis.toFloat() / execution.targetMillis).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onTimerToggle,
                enabled = editable && !working && (timerRunning || execution.elapsedMillis < execution.targetMillis),
            ) { Text(if (timerRunning) "暂停" else "开始") }
        }
        is ExecutionState.Information -> DetailCard("信息告知") {
            OutlinedTextField(
                value = informationDraft,
                onValueChange = onInformationChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = editable && !working,
                minLines = 5,
                label = { Text("告知正文") },
                supportingText = { Text("${informationDraft.codePointCount(0, informationDraft.length)} / 2000") },
            )
            Button(onClick = onInformationSave, enabled = editable && !working) { Text("保存草稿") }
            if (informationDraft.trim().isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onCopyInformation, enabled = !working) { Text("复制正文") }
                    Button(onClick = onShareInformation, enabled = !working) { Text("分享图片") }
                }
            }
            if (!editable && execution.content.isNotBlank()) {
                Text("完成后正文已锁定", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
        }
        ExecutionState.Normal, null -> Unit
    }
}

@Composable
private fun ActionBar(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.testTag("task-detail-actions"), shadowElevation = 4.dp) {
        Box(Modifier.fillMaxWidth().imePadding().padding(16.dp), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun ErrorContent(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("无法打开任务", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}

private fun completionHint(state: ExecutionUiState): String = when {
    !state.requiredStepsComplete && !state.executionTargetReached -> "完成所有必需步骤并达成执行目标后，才能完成任务。"
    !state.requiredStepsComplete -> "完成所有必需步骤后，才能完成任务。"
    !state.executionTargetReached -> "达成执行目标后，才能完成任务。"
    else -> ""
}

private fun statusLabel(status: String): String = when (status) {
    TaskStatus.NOT_STARTED.name -> "未开始"
    TaskStatus.PENDING.name -> "待完成"
    TaskStatus.COMPLETED.name -> "已完成"
    TaskStatus.MISSED.name -> "未完成"
    TaskStatus.CANCELLED.name -> "已撤销"
    else -> status
}

private fun formatDuration(millis: Long): String {
    val seconds = millis / 1_000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
