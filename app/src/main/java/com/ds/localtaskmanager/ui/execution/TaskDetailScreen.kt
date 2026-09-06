package com.ds.localtaskmanager.ui.execution

import android.view.View
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ds.localtaskmanager.ui.theme.LocalReduceMotion
import com.ds.localtaskmanager.ui.components.BackNavigationIcon
import com.ds.localtaskmanager.ui.navigation.predictiveBackTransform
import com.ds.localtaskmanager.ui.navigation.rememberPredictiveBackState
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
    val predictiveBack = rememberPredictiveBackState(enabled = shareImage == null, onBack = ::leave)

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
        onStepConfirm = viewModel::confirmStep,
        onStepSkip = viewModel::skipStep,
        onStepUndo = viewModel::undoStep,
        onStepInformationChange = viewModel::updateStepInformation,
        onStepMoodChange = viewModel::updateStepMood,
        onStepCounterChange = viewModel::updateStepCounter,
        onStepTimerToggle = { stepId -> if (state.activeStepTimerId == stepId) viewModel.pauseStepTimer(stepId) else viewModel.startStepTimer(stepId) },
        onStepRetrySave = viewModel::retryStepSave,
        onCounterChange = viewModel::setCounter,
        onTimerToggle = { if (state.timerRunning) viewModel.pauseTimer() else viewModel.startTimer() },
        onInformationChange = viewModel::updateInformationDraft,
        onInformationSave = viewModel::saveInformationDraft,
        onNoteChange = viewModel::updateNoteDraft,
        onMoodRatingChange = viewModel::updateMoodRating,
        onMoodTextChange = viewModel::updateMoodText,
        onMoodRetry = viewModel::retryMoodSave,
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
        modifier = Modifier.predictiveBackTransform(predictiveBack),
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
    onStepConfirm: (String) -> Unit = {},
    onStepSkip: (Int) -> Unit = {},
    onStepUndo: (String) -> Unit = {},
    onStepInformationChange: (String, String) -> Unit = { _, _ -> },
    onStepMoodChange: (String, Int?, String) -> Unit = { _, _, _ -> },
    onStepCounterChange: (String, Int) -> Unit = { _, _ -> },
    onStepTimerToggle: (String) -> Unit = {},
    onStepRetrySave: (String) -> Unit = {},
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
    onMoodRatingChange: (Int) -> Unit = {},
    onMoodTextChange: (String) -> Unit = {},
    onMoodRetry: () -> Unit = {},
    readOnly: Boolean = false,
    title: String = "任务详情",
    timeline: List<TaskDetailTimelineItem> = emptyList(),
    modifier: Modifier = Modifier,
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
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { BackNavigationIcon(onBack) },
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
                onStepConfirm = onStepConfirm,
                onStepSkip = onStepSkip,
                onStepUndo = onStepUndo,
                onStepInformationChange = onStepInformationChange,
                onStepMoodChange = onStepMoodChange,
                onStepCounterChange = onStepCounterChange,
                onStepTimerToggle = onStepTimerToggle,
                onStepRetrySave = onStepRetrySave,
                onCounterChange = onCounterChange,
                onTimerToggle = onTimerToggle,
                onInformationChange = onInformationChange,
                onInformationSave = onInformationSave,
                onCopyInformation = onCopyInformation,
                onShareInformation = onShareInformation,
                onNoteChange = onNoteChange,
                onMoodRatingChange = onMoodRatingChange,
                onMoodTextChange = onMoodTextChange,
                onMoodRetry = onMoodRetry,
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
    onStepConfirm: (String) -> Unit,
    onStepSkip: (Int) -> Unit,
    onStepUndo: (String) -> Unit,
    onStepInformationChange: (String, String) -> Unit,
    onStepMoodChange: (String, Int?, String) -> Unit,
    onStepCounterChange: (String, Int) -> Unit,
    onStepTimerToggle: (String) -> Unit,
    onStepRetrySave: (String) -> Unit,
    onCounterChange: (Int) -> Unit,
    onTimerToggle: () -> Unit,
    onInformationChange: (String) -> Unit,
    onInformationSave: () -> Unit,
    onCopyInformation: () -> Unit,
    onShareInformation: () -> Unit,
    onNoteChange: (String) -> Unit,
    onMoodRatingChange: (Int) -> Unit,
    onMoodTextChange: (String) -> Unit,
    onMoodRetry: () -> Unit,
    readOnly: Boolean,
    timeline: List<TaskDetailTimelineItem>,
) {
    val instance = requireNotNull(state.instance)
    val editable = !readOnly && instance.status == TaskStatus.PENDING.name
    val sizeAnimation = if (LocalReduceMotion.current) Modifier else Modifier.animateContentSize(tween(180))
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 28.dp)
            .then(sizeAnimation),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(instance)
        if (instance.description.isNotBlank()) {
            DetailCard("任务说明") { Text(instance.description, style = MaterialTheme.typography.bodyLarge) }
        }
        if (state.steps.isNotEmpty() && instance.executionKind != "STEPS") {
            DetailCard("任务步骤") {
                state.steps.forEachIndexed { index, step ->
                    StepRow(step, editable, state.working, onStepChange)
                    if (index != state.steps.lastIndex) HorizontalDivider()
                }
            }
        }
        if (state.execution is ExecutionState.Mood) {
            MoodSection(state.moodRating, state.moodText, editable, state.working, state.moodSaveState,
                onMoodRatingChange, onMoodTextChange, onMoodRetry)
        }
        if (instance.executionKind == "STEPS" && state.execution is ExecutionState.Steps) {
            StepsExecutionSection(
                steps = state.steps,
                editable = editable,
                working = state.working,
                activeTimerId = state.activeStepTimerId,
                stepSaveStates = state.stepSaveStates,
                onConfirm = onStepConfirm,
                onSkip = onStepSkip,
                onUndo = onStepUndo,
                onInformationChange = onStepInformationChange,
                onMoodChange = onStepMoodChange,
                onCounterChange = onStepCounterChange,
                onTimerToggle = onStepTimerToggle,
                onRetrySave = onStepRetrySave,
            )
            if (editable && state.steps.any { !it.required && it.stepStatus == "PENDING" }) {
                Text("完成任务时，尚未确认的选做步骤会自动跳过。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        } else ExecutionSection(
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
        var privateNoteExpanded by remember(instance.taskId, instance.occurrenceKey) { mutableStateOf(false) }
        val moodTask = instance.executionKind == "MOOD"
        if (moodTask) TextButton(onClick = { privateNoteExpanded = !privateNoteExpanded }) {
            Text("${if (privateNoteExpanded) "▾" else "▸"} 私人备注 · 仅本机保存")
        }
        if (!moodTask || privateNoteExpanded) DetailCard(if (moodTask) "私人备注" else "普通备注") {
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
            if (instance.singleDayAdjusted) Text("单日调整", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
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
        is ExecutionState.Steps -> DetailCard("分步骤执行") {
            execution.items.forEach { item ->
                Text(
                    "${if (item.completed) "✓" else "○"} ${item.position + 1}. ${item.name} · ${if (item.completed) "已确认" else "未处理"}",
                    color = if (item.completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        is ExecutionState.Mood, ExecutionState.Normal, null -> Unit
    }
}

@Composable
private fun StepsExecutionSection(
    steps: List<InstanceStepEntity>,
    editable: Boolean,
    working: Boolean,
    activeTimerId: String?,
    stepSaveStates: Map<String, NoteSaveState> = emptyMap(),
    onConfirm: (String) -> Unit,
    onSkip: (Int) -> Unit,
    onUndo: (String) -> Unit,
    onInformationChange: (String, String) -> Unit,
    onMoodChange: (String, Int?, String) -> Unit,
    onCounterChange: (String, Int) -> Unit,
    onTimerToggle: (String) -> Unit,
    onRetrySave: (String) -> Unit,
) {
    DetailCard("分步骤执行") {
        val openIndex = steps.indexOfFirst { it.stepStatus == "PENDING" }
        steps.forEachIndexed { index, step ->
            val confirmed = step.stepStatus == "CONFIRMED"
            val skipped = step.stepStatus == "SKIPPED"
            val open = editable && !working && (index == openIndex || confirmed)
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}. ${step.name.ifBlank { step.stepId ?: "步骤" }}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(if (confirmed) "已确认" else if (skipped) "已跳过" else if (index == openIndex) "进行中" else "已锁定", style = MaterialTheme.typography.labelMedium)
                }
                if (!confirmed && !skipped && index != openIndex) Text("请先处理前一步", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (step.required) Text("必需步骤", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (confirmed || index == openIndex) StepAnswerEditor(step, editable && !working && index == openIndex, activeTimerId, step.stepId?.let { stepSaveStates[it] }, onInformationChange, onMoodChange, onCounterChange, onTimerToggle, onRetrySave)
                if (confirmed || skipped) OutlinedButton(onClick = { onUndo(step.stepId ?: "") }, enabled = editable && !working && step.stepId != null) { Text(if (skipped) "重新处理此步骤" else "撤销此步骤") }
                if (index == openIndex && !step.required && editable) OutlinedButton(onClick = { onSkip(index) }, enabled = !working) { Text("跳过选做步骤") }
                if (index == openIndex && editable && step.stepId != null) Button(onClick = { onConfirm(step.stepId) }, enabled = !working && stepSatisfiedForUi(step)) { Text("完成此步骤") }
            }
            if (index != steps.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun StepAnswerEditor(
    step: InstanceStepEntity,
    editable: Boolean,
    activeTimerId: String?,
    saveState: NoteSaveState?,
    onInformationChange: (String, String) -> Unit,
    onMoodChange: (String, Int?, String) -> Unit,
    onCounterChange: (String, Int) -> Unit,
    onTimerToggle: (String) -> Unit,
    onRetrySave: (String) -> Unit,
) {
    if (!editable) {
        if (step.executionKind == "MOOD") {
            MoodSection(step.moodRating, step.moodText.orEmpty(), false, false, saveState ?: NoteSaveState.SAVED, {}, {}, {})
        } else if (step.executionKind == "INFORMATION" && !step.informationContent.isNullOrBlank()) {
            var expanded by rememberSaveable(step.stepId) { mutableStateOf(false) }
            Text(if (expanded) step.informationContent.orEmpty() else compactInformation(step.informationContent.orEmpty()), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起正文" else "展开正文") }
        } else Text(stepAnswerSummary(step), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (saveState != null) Text(
            when (saveState) { NoteSaveState.SAVING -> "保存中…"; NoteSaveState.SAVED -> "已保存"; NoteSaveState.ERROR -> "保存失败，请重试" },
            color = if (saveState == NoteSaveState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        when (step.executionKind) {
        "COUNTER" -> {
            Text("${step.counterValue ?: 0} / ${step.executionTarget ?: 0}", style = MaterialTheme.typography.titleLarge)
            if (step.executionAction == 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { step.stepId?.let { onCounterChange(it, (step.counterValue ?: 0) - 1) } }, enabled = editable && (step.counterValue ?: 0) > 0) { Text("−1") }
                    Button(onClick = { step.stepId?.let { onCounterChange(it, (step.counterValue ?: 0) + 1) } }, enabled = editable && (step.counterValue ?: 0) < (step.executionTarget ?: 0)) { Text("+1") }
                }
            } else Slider(value = (step.counterValue ?: 0).toFloat(), onValueChange = { value -> step.stepId?.let { onCounterChange(it, value.roundToInt()) } }, valueRange = 0f..(step.executionTarget ?: 1).toFloat(), enabled = editable, modifier = Modifier.testTag("step-counter-${step.stepId}"))
        }
        "TIMER" -> {
            Text("${formatDuration(step.elapsedMillis ?: 0)} / ${formatDuration((step.executionTarget ?: 0) * 1000L)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleLarge)
            Button(onClick = { step.stepId?.let(onTimerToggle) }, enabled = editable || activeTimerId == step.stepId) { Text(if (activeTimerId == step.stepId) "暂停" else "开始") }
        }
        "INFORMATION" -> OutlinedTextField(
            value = step.informationContent.orEmpty(),
            onValueChange = { text -> step.stepId?.let { onInformationChange(it, text) } },
            enabled = editable,
            modifier = Modifier.fillMaxWidth().testTag("step-information-${step.stepId}"),
            minLines = 3,
            label = { Text("填写信息") },
            supportingText = { Text("${step.informationContent.orEmpty().codePointCount(0, step.informationContent.orEmpty().length)} / 2000") },
        )
        "MOOD" -> {
            MoodSection(step.moodRating, step.moodText.orEmpty(), true, false, saveState ?: NoteSaveState.SAVED,
                onRatingChange = { rating -> step.stepId?.let { onMoodChange(it, rating, step.moodText.orEmpty()) } },
                onTextChange = { text -> step.stepId?.let { onMoodChange(it, step.moodRating, text) } },
                onRetry = { step.stepId?.let(onRetrySave) },
            )
        }
        }
        if (saveState == NoteSaveState.ERROR && step.executionKind != "MOOD") {
            TextButton(onClick = { step.stepId?.let(onRetrySave) }) { Text("重试保存") }
        }
    }
}

private fun stepAnswerSummary(step: InstanceStepEntity): String = when (step.executionKind) {
    "COUNTER" -> "计数：${step.counterValue ?: 0} / ${step.executionTarget ?: 0}"
    "TIMER" -> "计时：${formatDuration(step.elapsedMillis ?: 0)} / ${formatDuration((step.executionTarget ?: 0) * 1000L)}"
    "INFORMATION" -> "填写：${step.informationContent.orEmpty().ifBlank { "未填写" }}"
    "MOOD" -> "心情：${step.moodRating?.toString() ?: "未选择"}${step.moodText.orEmpty().takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}"
    else -> "直接完成"
}

private fun compactInformation(value: String): String {
    val count = value.codePointCount(0, value.length)
    if (count <= 60) return "填写：$value"
    val end = value.offsetByCodePoints(0, 60)
    return "填写：${value.substring(0, end)}…"
}

private fun stepSatisfiedForUi(step: InstanceStepEntity): Boolean = when (step.executionKind) {
    "COUNTER" -> (step.counterValue ?: 0) >= (step.executionTarget ?: Int.MAX_VALUE)
    "TIMER" -> (step.elapsedMillis ?: 0L) >= (step.executionTarget ?: Int.MAX_VALUE) * 1_000L
    "INFORMATION" -> !step.informationContent.isNullOrBlank() && step.informationContent!!.codePointCount(0, step.informationContent!!.length) <= 2000
    "MOOD" -> step.moodRating in 1..5
    else -> true
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
    state.instance?.executionKind == "MOOD" && state.moodRating == null ->
        if (!state.requiredStepsComplete) "请选择今天的心情，并完成所有必需步骤。" else "请选择今天的心情。"
    state.instance?.executionKind == "MOOD" && state.moodText.codePointCount(0, state.moodText.length) > 2000 -> "感受不能超过 2000 个字符。"
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
