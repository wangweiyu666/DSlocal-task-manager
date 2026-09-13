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
import com.ds.localtaskmanager.ui.today.TodayViewModel
import com.ds.localtaskmanager.ui.today.TodayViewModelFactory
import com.ds.localtaskmanager.ui.theme.DstTheme
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import com.ds.localtaskmanager.reminder.AndroidReminderNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private val notificationTask = MutableStateFlow<TaskInstanceKey?>(null)
    private val todayViewModelDelegate = viewModels<TodayViewModel> {
        val application = application as DstApplication
        TodayViewModelFactory(
            application.taskRepository,
            application.importService,
            application.instanceGenerationService,
            application.resultRepository,
            application.reminderCoordinator,
        )
    }

    private val todayViewModel by todayViewModelDelegate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureNotificationTarget(intent)
        enableEdgeToEdge()
        setContent { AppContent() }
    }

    override fun onResume() {
        super.onResume()
        // First creation reconciles itself; resume must not initialize storage early.
        if (todayViewModelDelegate.isInitialized()) todayViewModel.synchronizeInstances(coalesce = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureNotificationTarget(intent)
    }

    @Composable
    private fun AppContent() {
        val application = application as DstApplication
        val startup by application.startup.collectAsStateWithLifecycle()
        if (startup != StartupState.Ready) {
            DstTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (startup == StartupState.Failed) {
                            TextButton(onClick = application::initialize) { Text("启动失败，点击重试") }
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
            return
        }
        val settings by application.settingsRepository.settings.collectAsStateWithLifecycle()
        DstTheme(
            themeMode = settings.themeMode,
            uiPalette = settings.uiPalette,
            reduceMotion = settings.reduceMotion,
        ) {
            ConnectivityContent(
                application = application,
                todayViewModel = todayViewModel,
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
