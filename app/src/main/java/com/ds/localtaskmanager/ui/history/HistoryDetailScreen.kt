package com.ds.localtaskmanager.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ds.localtaskmanager.ui.execution.TaskDetailScreen

@Composable
fun HistoryDetailRoute(
    viewModel: HistoryDetailViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
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
    )
}
