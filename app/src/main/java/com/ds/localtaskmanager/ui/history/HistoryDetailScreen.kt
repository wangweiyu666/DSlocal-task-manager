package com.ds.localtaskmanager.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ds.localtaskmanager.ui.execution.TaskDetailScreen
import kotlinx.coroutines.launch

@Composable
fun HistoryDetailRoute(
    viewModel: HistoryDetailViewModel,
    shareImageService: com.ds.localtaskmanager.sharing.ShareImageService,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var shareImage by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.ds.localtaskmanager.sharing.GeneratedShareImage?>(null) }
    fun leave() = viewModel.flushNote(onBack)
    BackHandler(onBack = ::leave)

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) viewModel.flushNote {}
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    TaskDetailScreen(
        state = state.detail,
        onBack = ::leave,
        onRetry = viewModel::refresh,
        onStepChange = { _, _ -> },
        onCounterChange = {},
        onTimerToggle = {},
        onInformationChange = {},
        onInformationSave = {},
        onNoteChange = viewModel::updateNote,
        onComplete = {},
        onUndo = {},
        onDismissCompletion = {},
        onDismissError = {},
        readOnly = true,
        title = "历史详情",
        timeline = state.timeline,
        onCopyInformation = {
            state.detail.informationDraft.trim().takeIf(String::isNotEmpty)?.let {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(it))
                android.widget.Toast.makeText(context, "正文已复制", android.widget.Toast.LENGTH_SHORT).show()
            }
        },
        onShareInformation = {
            val instance = state.detail.instance
            val body = state.detail.informationDraft.trim()
            if (instance != null && body.isNotEmpty()) scope.launch {
                runCatching { shareImageService.generateInformation(instance.name, instance.taskDate, body) }
                    .onSuccess { shareImage = it }
                    .onFailure { android.widget.Toast.makeText(context, it.message ?: "暂时无法生成图片", android.widget.Toast.LENGTH_SHORT).show() }
            }
        },
    )
    shareImage?.let {
        com.ds.localtaskmanager.ui.sharing.SharePreviewDialog(it, shareImageService, sensitive = true) { shareImage = null }
    }
}
