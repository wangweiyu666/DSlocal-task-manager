package com.ds.localtaskmanager

import androidx.compose.runtime.Composable
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import com.ds.localtaskmanager.ui.DstApp
import com.ds.localtaskmanager.ui.today.TodayViewModel
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun ConnectivityContent(
    application: DstApplication,
    todayViewModel: TodayViewModel,
    notificationTask: StateFlow<TaskInstanceKey?>,
    onNotificationTaskConsumed: () -> Unit,
    onNotificationPermissionChanged: () -> Unit,
) {
    DstApp(
        todayViewModel = todayViewModel,
        taskRepository = application.taskRepository,
        taskExecutionService = application.taskExecutionService,
        taskNoteService = application.taskNoteService,
        historyRepository = application.historyRepository,
        resultRepository = application.resultRepository,
        statisticsRepository = application.statisticsRepository,
        shareImageService = application.shareImageService,
        settingsRepository = application.settingsRepository,
        diagnosticService = application.diagnosticService,
        backupManager = application.backupManager,
        backupRepository = application.backupRepository,
        reminderReconciler = application.reminderCoordinator,
        notificationTask = notificationTask,
        onNotificationTaskConsumed = onNotificationTaskConsumed,
        onNotificationPermissionChanged = onNotificationPermissionChanged,
    )
}
