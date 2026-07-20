package com.ds.localtaskmanager.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ds.localtaskmanager.data.TaskExecutionService
import com.ds.localtaskmanager.data.TaskNoteService
import com.ds.localtaskmanager.data.TaskRepository
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import com.ds.localtaskmanager.ui.execution.ExecutionViewModel
import com.ds.localtaskmanager.ui.execution.ExecutionViewModelFactory
import com.ds.localtaskmanager.ui.execution.TaskDetailRoute
import com.ds.localtaskmanager.ui.history.HistoryScreen
import com.ds.localtaskmanager.ui.profile.ProfileScreen
import com.ds.localtaskmanager.ui.today.ImportDialog
import com.ds.localtaskmanager.ui.today.TodayScreen
import com.ds.localtaskmanager.ui.today.TodayViewModel
import kotlinx.coroutines.flow.StateFlow
import com.ds.localtaskmanager.reminder.ReminderReconciler

private enum class Destination(
    val route: String,
    val label: String,
    val marker: String,
) {
    History("history", "历史", "◷"),
    Today("today", "今日", "●"),
    Profile("profile", "我的", "○"),
}

private const val TASK_ROUTE = "task/{taskId}/{occurrenceKey}"

@Composable
fun DstNavigation(
    todayViewModel: TodayViewModel,
    taskRepository: TaskRepository,
    taskExecutionService: TaskExecutionService,
    taskNoteService: TaskNoteService,
    reminderReconciler: ReminderReconciler,
    notificationTask: StateFlow<TaskInstanceKey?>,
    onNotificationTaskConsumed: () -> Unit,
    onNotificationPermissionChanged: () -> Unit,
) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route
    val onPrimaryDestination = currentRoute in Destination.entries.map(Destination::route)
    val importState by todayViewModel.importState.collectAsStateWithLifecycle()
    val notificationKey by notificationTask.collectAsStateWithLifecycle()

    LaunchedEffect(notificationKey) {
        notificationKey?.let { key ->
            navController.navigate("task/${key.taskId}/${key.occurrenceKey}") { launchSingleTop = true }
            onNotificationTaskConsumed()
        }
    }

    Scaffold(
        bottomBar = {
            if (onPrimaryDestination) {
                NavigationBar {
                    Destination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Text(destination.marker, fontSize = 20.sp) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (currentRoute == Destination.Today.route) {
                FloatingActionButton(
                    onClick = todayViewModel::openImport,
                    modifier = Modifier.semantics { contentDescription = "导入任务" },
                ) {
                    Text("+", fontSize = 26.sp)
                }
            }
        },
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Today.route,
            modifier = Modifier.padding(contentPadding),
            enterTransition = { forwardEnterTransition() },
            exitTransition = { forwardExitTransition() },
            popEnterTransition = { popEnterTransition() },
            popExitTransition = { popExitTransition() },
        ) {
            composable(Destination.History.route) { HistoryScreen() }
            composable(Destination.Today.route) {
                TodayScreen(todayViewModel) { key ->
                    navController.navigate("task/${key.taskId}/${key.occurrenceKey}")
                }
            }
            composable(Destination.Profile.route) {
                ProfileScreen(onNotificationPermissionChanged)
            }
            composable(TASK_ROUTE) { backStackEntry ->
                val key = TaskInstanceKey(
                    taskId = requireNotNull(backStackEntry.arguments?.getString("taskId")),
                    occurrenceKey = requireNotNull(backStackEntry.arguments?.getString("occurrenceKey")),
                )
                val executionViewModel: ExecutionViewModel = viewModel(
                    key = "execution:${key.taskId}:${key.occurrenceKey}",
                    factory = ExecutionViewModelFactory(
                        key,
                        taskExecutionService,
                        taskRepository,
                        taskNoteService,
                        reminderReconciler,
                    ),
                )
                TaskDetailRoute(executionViewModel, navController::popBackStack)
            }
        }
    }

    if (importState.visible) {
        ImportDialog(
            state = importState,
            onInputChange = todayViewModel::updateImportInput,
            onPreview = todayViewModel::previewImport,
            onConfirm = todayViewModel::confirmImport,
            onDismiss = todayViewModel::closeImport,
        )
    }
}

private fun forwardEnterTransition(): EnterTransition =
    fadeIn(tween(200)) + slideInHorizontally(tween(220)) { it / 10 }

private fun forwardExitTransition(): ExitTransition =
    fadeOut(tween(150)) + slideOutHorizontally(tween(180)) { -it / 12 }

private fun popEnterTransition(): EnterTransition =
    fadeIn(tween(180)) + slideInHorizontally(tween(200)) { -it / 12 }

private fun popExitTransition(): ExitTransition =
    fadeOut(tween(150)) + slideOutHorizontally(tween(200)) { it / 10 }
