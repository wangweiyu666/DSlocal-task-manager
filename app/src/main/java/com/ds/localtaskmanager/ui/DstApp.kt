package com.ds.localtaskmanager.ui

import androidx.compose.runtime.Composable
import com.ds.localtaskmanager.data.TaskExecutionService
import com.ds.localtaskmanager.data.TaskNoteService
import com.ds.localtaskmanager.data.TaskRepository
import com.ds.localtaskmanager.ui.navigation.DstNavigation
import com.ds.localtaskmanager.ui.today.TodayViewModel
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import kotlinx.coroutines.flow.StateFlow
import com.ds.localtaskmanager.reminder.ReminderReconciler
import com.ds.localtaskmanager.data.history.HistoryRepository
import com.ds.localtaskmanager.data.result.ResultRepository
import com.ds.localtaskmanager.data.statistics.StatisticsRepository
import com.ds.localtaskmanager.sharing.ShareImageService
import com.ds.localtaskmanager.settings.AppSettingsRepository

@Composable
fun DstApp(
    todayViewModel: TodayViewModel,
    taskRepository: TaskRepository,
    taskExecutionService: TaskExecutionService,
    taskNoteService: TaskNoteService,
    historyRepository: HistoryRepository,
    resultRepository: ResultRepository,
    statisticsRepository: StatisticsRepository,
    shareImageService: ShareImageService,
    settingsRepository: AppSettingsRepository,
    reminderReconciler: ReminderReconciler,
    notificationTask: StateFlow<TaskInstanceKey?>,
    onNotificationTaskConsumed: () -> Unit,
    onNotificationPermissionChanged: () -> Unit,
) = DstNavigation(
    todayViewModel,
    taskRepository,
    taskExecutionService,
    taskNoteService,
    historyRepository,
    resultRepository,
    statisticsRepository,
    shareImageService,
    settingsRepository,
    reminderReconciler,
    notificationTask,
    onNotificationTaskConsumed,
    onNotificationPermissionChanged,
)
