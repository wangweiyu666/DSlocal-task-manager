package com.ds.localtaskmanager.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
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
import com.ds.localtaskmanager.data.history.HistoryRepository
import com.ds.localtaskmanager.data.result.ResultRepository
import com.ds.localtaskmanager.data.statistics.StatisticsPeriod
import com.ds.localtaskmanager.data.statistics.StatisticsRepository
import com.ds.localtaskmanager.ui.history.HistoryRoute
import com.ds.localtaskmanager.ui.history.HistoryViewModel
import com.ds.localtaskmanager.ui.history.HistoryViewModelFactory
import com.ds.localtaskmanager.ui.history.HistoryDetailRoute
import com.ds.localtaskmanager.ui.history.HistoryDetailViewModel
import com.ds.localtaskmanager.ui.history.HistoryDetailViewModelFactory
import com.ds.localtaskmanager.ui.history.DayHistoryRoute
import com.ds.localtaskmanager.ui.history.DayHistoryViewModel
import com.ds.localtaskmanager.ui.history.DayHistoryViewModelFactory
import com.ds.localtaskmanager.ui.profile.ArchivedGroupsRoute
import com.ds.localtaskmanager.ui.profile.LedgerRoute
import com.ds.localtaskmanager.ui.profile.LedgerViewModel
import com.ds.localtaskmanager.ui.profile.LedgerViewModelFactory
import com.ds.localtaskmanager.ui.profile.ProfileRoute
import com.ds.localtaskmanager.ui.profile.ProfileViewModel
import com.ds.localtaskmanager.ui.profile.ProfileViewModelFactory
import com.ds.localtaskmanager.ui.today.ImportDialog
import com.ds.localtaskmanager.ui.today.TodayScreen
import com.ds.localtaskmanager.ui.today.TodayContent
import com.ds.localtaskmanager.ui.today.TodayUiState
import com.ds.localtaskmanager.ui.today.TodayViewModel
import com.ds.localtaskmanager.ui.theme.DstTheme
import kotlinx.coroutines.flow.StateFlow
import com.ds.localtaskmanager.R
import com.ds.localtaskmanager.reminder.ReminderReconciler
import com.ds.localtaskmanager.sharing.ShareImageService
import com.ds.localtaskmanager.settings.AppSettingsRepository
import com.ds.localtaskmanager.backup.BackupManager
import com.ds.localtaskmanager.backup.RoomBackupRepository
import com.ds.localtaskmanager.ui.backup.BackupRoute
import com.ds.localtaskmanager.ui.backup.BackupViewModel
import com.ds.localtaskmanager.ui.backup.BackupViewModelFactory
import com.ds.localtaskmanager.ui.settings.SettingsRoute
import com.ds.localtaskmanager.ui.settings.LegalDocument
import com.ds.localtaskmanager.ui.settings.LegalScreen
import com.ds.localtaskmanager.diagnostics.DiagnosticService
import com.ds.localtaskmanager.ui.theme.LocalReduceMotion

private enum class Destination(
    val route: String,
    val label: String,
    val marker: String,
    val iconRes: Int? = null,
) {
    History("history", "历史", "", R.drawable.ic_nav_history),
    Today("today", "今日", "", R.drawable.ic_nav_today),
    Profile("profile", "我的", "", R.drawable.ic_nav_profile),
}

private const val TASK_ROUTE = "task/{taskId}/{occurrenceKey}"
private const val HISTORY_TASK_ROUTE = "history/task/{taskId}/{occurrenceKey}"
private const val HISTORY_DAY_ROUTE = "history/day/{taskDate}"
private const val PROFILE_LEDGER_ROUTE = "profile/ledger/{period}/{groupKey}"
private const val PROFILE_ARCHIVED_ROUTE = "profile/archived/{period}"
private const val PROFILE_SETTINGS_ROUTE = "profile/settings"
private const val PROFILE_BACKUP_ROUTE = "profile/settings/backup"
private const val PROFILE_PRIVACY_ROUTE = "profile/settings/privacy"
private const val PROFILE_LICENSES_ROUTE = "profile/settings/licenses"

@Composable
fun DstNavigation(
    todayViewModel: TodayViewModel,
    taskRepository: TaskRepository,
    taskExecutionService: TaskExecutionService,
    taskNoteService: TaskNoteService,
    historyRepository: HistoryRepository,
    resultRepository: ResultRepository,
    statisticsRepository: StatisticsRepository,
    shareImageService: ShareImageService,
    settingsRepository: AppSettingsRepository,
    diagnosticService: DiagnosticService,
    backupManager: BackupManager,
    backupRepository: RoomBackupRepository,
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
    val todayResultState by todayViewModel.resultState.collectAsStateWithLifecycle()
    val notificationKey by notificationTask.collectAsStateWithLifecycle()
    val backupOperationActive by backupManager.operationActive.collectAsStateWithLifecycle(initialValue = false)
    val backupRestoreActive by backupManager.restoreActive.collectAsStateWithLifecycle(initialValue = false)
    val reduceMotion = LocalReduceMotion.current

    LaunchedEffect(notificationKey) {
        notificationKey?.let { key ->
            navController.navigate("task/${key.taskId}/${key.occurrenceKey}") { launchSingleTop = true }
            onNotificationTaskConsumed()
        }
    }

    Scaffold(
        bottomBar = {
            if (!backupRestoreActive && onPrimaryDestination && !(currentRoute == Destination.Today.route && todayResultState.visible)) {
                DstBottomBar(currentRoute) { destination ->
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        },
        floatingActionButton = {
            if (!backupOperationActive && currentRoute == Destination.Today.route && !todayResultState.visible) {
                FloatingActionButton(
                    onClick = todayViewModel::openImport,
                    modifier = Modifier.semantics { contentDescription = "导入任务" },
                ) {
                    Text("+", fontSize = 26.sp)
                }
            }
        },
    ) { contentPadding ->
        Box(Modifier.fillMaxSize().padding(contentPadding)) {
        NavHost(
            navController = navController,
            startDestination = Destination.Today.route,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { forwardEnterTransition(reduceMotion) },
            exitTransition = { forwardExitTransition(reduceMotion) },
            popEnterTransition = { popEnterTransition(reduceMotion) },
            popExitTransition = { popExitTransition(reduceMotion) },
        ) {
            composable(Destination.History.route) {
                val historyViewModel: HistoryViewModel = viewModel(
                    key = "history",
                    factory = HistoryViewModelFactory(historyRepository),
                )
                HistoryRoute(
                    historyViewModel,
                    onTaskClick = { key -> navController.navigate("history/task/${key.taskId}/${key.occurrenceKey}") },
                    onDayClick = { date -> navController.navigate("history/day/$date") },
                )
            }
            composable(Destination.Today.route) {
                TodayScreen(todayViewModel, shareImageService) { key ->
                    navController.navigate("task/${key.taskId}/${key.occurrenceKey}")
                }
            }
            composable(Destination.Profile.route) {
                val profileViewModel: ProfileViewModel = viewModel(
                    key = "profile-statistics",
                    factory = ProfileViewModelFactory(statisticsRepository, settingsRepository),
                )
                ProfileRoute(
                    viewModel = profileViewModel,
                    onLedger = { period, groupId, ungrouped ->
                        val groupKey = when {
                            ungrouped -> "__UNGROUPED__"
                            groupId != null -> groupId
                            else -> "__ALL__"
                        }
                        navController.navigate("profile/ledger/${period.name}/$groupKey")
                    },
                    onArchivedGroups = { period -> navController.navigate("profile/archived/${period.name}") },
                    onSettings = { navController.navigate(PROFILE_SETTINGS_ROUTE) },
                )
            }
            composable(PROFILE_SETTINGS_ROUTE) {
                SettingsRoute(
                    repository = settingsRepository,
                    diagnosticService = diagnosticService,
                    onBack = navController::popBackStack,
                    onBackup = { navController.navigate(PROFILE_BACKUP_ROUTE) },
                    onPrivacy = { navController.navigate(PROFILE_PRIVACY_ROUTE) },
                    onLicenses = { navController.navigate(PROFILE_LICENSES_ROUTE) },
                    onNotificationPermissionChanged = onNotificationPermissionChanged,
                )
            }
            composable(PROFILE_PRIVACY_ROUTE) {
                LegalScreen(LegalDocument.PRIVACY, navController::popBackStack)
            }
            composable(PROFILE_LICENSES_ROUTE) {
                LegalScreen(LegalDocument.LICENSES, navController::popBackStack)
            }
            composable(PROFILE_BACKUP_ROUTE) {
                val backupViewModel: BackupViewModel = viewModel(
                    key = "backup-and-restore",
                    factory = BackupViewModelFactory(backupManager, backupRepository),
                )
                BackupRoute(
                    viewModel = backupViewModel,
                    settingsRepository = settingsRepository,
                    onBack = navController::popBackStack,
                    onRestoreComplete = {
                        navController.navigate(Destination.Today.route) {
                            popUpTo(navController.graph.findStartDestination().id)
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(PROFILE_LEDGER_ROUTE) { backStackEntry ->
                val period = runCatching {
                    StatisticsPeriod.valueOf(requireNotNull(backStackEntry.arguments?.getString("period")))
                }.getOrDefault(StatisticsPeriod.ALL)
                val groupKey = requireNotNull(backStackEntry.arguments?.getString("groupKey"))
                val ledgerViewModel: LedgerViewModel = viewModel(
                    key = "ledger:${period.name}:$groupKey",
                    factory = LedgerViewModelFactory(
                        repository = statisticsRepository,
                        period = period,
                        groupId = groupKey.takeUnless { it == "__ALL__" || it == "__UNGROUPED__" },
                        ungrouped = groupKey == "__UNGROUPED__",
                    ),
                )
                LedgerRoute(ledgerViewModel, navController::popBackStack)
            }
            composable(PROFILE_ARCHIVED_ROUTE) { backStackEntry ->
                val period = runCatching {
                    StatisticsPeriod.valueOf(requireNotNull(backStackEntry.arguments?.getString("period")))
                }.getOrDefault(StatisticsPeriod.THIRTY_DAYS)
                val archivedViewModel: ProfileViewModel = viewModel(
                    key = "archived-groups:${period.name}",
                    factory = ProfileViewModelFactory(statisticsRepository, settingsRepository),
                )
                LaunchedEffect(period) { archivedViewModel.selectPeriod(period) }
                ArchivedGroupsRoute(
                    viewModel = archivedViewModel,
                    onBack = navController::popBackStack,
                    onLedger = { group ->
                        navController.navigate("profile/ledger/${period.name}/${requireNotNull(group.groupId)}")
                    },
                )
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
                TaskDetailRoute(executionViewModel, shareImageService, navController::popBackStack)
            }
            composable(HISTORY_TASK_ROUTE) { backStackEntry ->
                val key = TaskInstanceKey(
                    taskId = requireNotNull(backStackEntry.arguments?.getString("taskId")),
                    occurrenceKey = requireNotNull(backStackEntry.arguments?.getString("occurrenceKey")),
                )
                val detailViewModel: HistoryDetailViewModel = viewModel(
                    key = "history-detail:${key.taskId}:${key.occurrenceKey}",
                    factory = HistoryDetailViewModelFactory(key, historyRepository, taskNoteService),
                )
                HistoryDetailRoute(detailViewModel, shareImageService, navController::popBackStack)
            }
            composable(HISTORY_DAY_ROUTE) { backStackEntry ->
                val taskDate = requireNotNull(backStackEntry.arguments?.getString("taskDate"))
                val dayViewModel: DayHistoryViewModel = viewModel(
                    key = "history-day:$taskDate",
                    factory = DayHistoryViewModelFactory(taskDate, historyRepository, resultRepository),
                )
                DayHistoryRoute(
                    dayViewModel,
                    onBack = navController::popBackStack,
                    onTaskClick = { key -> navController.navigate("history/task/${key.taskId}/${key.occurrenceKey}") },
                )
            }
        }
        if (backupRestoreActive && currentRoute != PROFILE_BACKUP_ROUTE) {
            Box(
                modifier = Modifier.fillMaxSize().clickable(onClick = {}),
                contentAlignment = Alignment.TopCenter,
            ) {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("正在恢复数据，当前页面暂时只读", Modifier.padding(16.dp))
                }
            }
        }
        }
    }

    if (importState.visible && !backupOperationActive) {
        ImportDialog(
            state = importState,
            onInputChange = todayViewModel::updateImportInput,
            onPreview = todayViewModel::previewImport,
            onConfirm = todayViewModel::confirmImport,
            onDismiss = todayViewModel::closeImport,
        )
    }
}

@Composable
private fun DstBottomBar(
    currentRoute: String?,
    onNavigate: (Destination) -> Unit,
) {
    NavigationBar {
        Destination.entries.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = { onNavigate(destination) },
                icon = {
                    destination.iconRes?.let { iconRes ->
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                        )
                    } ?: Text(destination.marker, fontSize = 30.sp)
                },
                label = { Text(destination.label) },
            )
        }
    }
}

@Preview(
    name = "今日页 · 空状态",
    showBackground = true,
    widthDp = 393,
    heightDp = 852,
)
@Composable
private fun TodayPagePreview() {
    TodayPagePreviewContent()
}

@Composable
fun TodayPagePreviewContent() {
    DstTheme {
        Scaffold(
            bottomBar = { DstBottomBar(Destination.Today.route, onNavigate = {}) },
            floatingActionButton = {
                FloatingActionButton(onClick = {}) { Text("+", fontSize = 26.sp) }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                TodayContent(
                    state = TodayUiState(
                        loading = false,
                        taskDate = "2026-07-20",
                    ),
                    onRetry = {},
                    onTaskClick = {},
                )
            }
        }
    }
}

private fun forwardEnterTransition(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) EnterTransition.None else fadeIn(tween(200)) + slideInHorizontally(tween(220)) { it / 10 }

private fun forwardExitTransition(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) ExitTransition.None else fadeOut(tween(150)) + slideOutHorizontally(tween(180)) { -it / 12 }

private fun popEnterTransition(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) EnterTransition.None else fadeIn(tween(180)) + slideInHorizontally(tween(200)) { -it / 12 }

private fun popExitTransition(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) ExitTransition.None else fadeOut(tween(150)) + slideOutHorizontally(tween(200)) { it / 10 }
