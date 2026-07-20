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

@Composable
fun DstApp(
    todayViewModel: TodayViewModel,
    taskRepository: TaskRepository,
    taskExecutionService: TaskExecutionService,
    taskNoteService: TaskNoteService,
    reminderReconciler: ReminderReconciler,
    notificationTask: StateFlow<TaskInstanceKey?>,
    onNotificationTaskConsumed: () -> Unit,
    onNotificationPermissionChanged: () -> Unit,
) = DstNavigation(
    todayViewModel,
    taskRepository,
    taskExecutionService,
    taskNoteService,
    reminderReconciler,
    notificationTask,
    onNotificationTaskConsumed,
    onNotificationPermissionChanged,
)
