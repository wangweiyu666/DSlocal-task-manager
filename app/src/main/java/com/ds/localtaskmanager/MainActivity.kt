package com.ds.localtaskmanager

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ds.localtaskmanager.ui.DstApp
import com.ds.localtaskmanager.ui.today.TodayViewModel
import com.ds.localtaskmanager.ui.today.TodayViewModelFactory
import com.ds.localtaskmanager.ui.theme.DstTheme
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import com.ds.localtaskmanager.reminder.AndroidReminderNotifier
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val notificationTask = MutableStateFlow<TaskInstanceKey?>(null)
    private val todayViewModel: TodayViewModel by viewModels {
        val application = application as DstApplication
        TodayViewModelFactory(
            application.taskRepository,
            application.importService,
            application.instanceGenerationService,
            application.resultRepository,
            application.reminderCoordinator,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureNotificationTarget(intent)
        enableEdgeToEdge()
        setContent { AppContent() }
    }

    override fun onResume() {
        super.onResume()
        todayViewModel.synchronizeInstances()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureNotificationTarget(intent)
    }

    @Composable
    private fun AppContent() {
        val application = application as DstApplication
        val settings by application.settingsRepository.settings.collectAsStateWithLifecycle()
        DstTheme(themeMode = settings.themeMode, reduceMotion = settings.reduceMotion) {
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
                reminderReconciler = application.reminderCoordinator,
                notificationTask = notificationTask,
                onNotificationTaskConsumed = { notificationTask.value = null },
                onNotificationPermissionChanged = { todayViewModel.synchronizeInstances() },
            )
        }
    }

    private fun captureNotificationTarget(intent: Intent?) {
        val taskId = intent?.getStringExtra(AndroidReminderNotifier.EXTRA_TASK_ID) ?: return
        val occurrenceKey = intent.getStringExtra(AndroidReminderNotifier.EXTRA_OCCURRENCE_KEY) ?: return
        notificationTask.value = TaskInstanceKey(taskId, occurrenceKey)
    }
}
