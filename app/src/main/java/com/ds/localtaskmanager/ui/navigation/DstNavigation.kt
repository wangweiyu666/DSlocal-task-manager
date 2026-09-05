package com.ds.localtaskmanager.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.ds.localtaskmanager.ui.AppNotificationUi
import com.ds.localtaskmanager.ui.ConnectedUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    connectedState: ConnectedUiState? = null,
    onAutoSynchronize: () -> Unit = {},
    onSynchronize: () -> Unit = {},
    onMarkNotificationsRead: (List<String>) -> Unit = {},
    onLogout: () -> Unit = {},
    onConnectedAccountAction: (String) -> Unit = {},
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
    val connectedMode = connectedState != null
    val snackbarHostState = remember { SnackbarHostState() }
    var showNotifications by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var knownNotificationIds by remember { mutableStateOf<Set<String>?>(null) }

    LaunchedEffect(notificationKey) {
        notificationKey?.let { key ->
            navController.navigate("task/${key.taskId}/${key.occurrenceKey}") { launchSingleTop = true }
            onNotificationTaskConsumed()
        }
    }

    LaunchedEffect(connectedState?.notifications) {
        val currentNotifications = connectedState?.notifications ?: return@LaunchedEffect
        val currentIds = currentNotifications.flatMap(AppNotificationUi::notificationIds).toSet()
        val knownIds = knownNotificationIds
        if (knownIds == null) {
            knownNotificationIds = currentIds
            return@LaunchedEffect
        }
        val fresh = currentNotifications.filter { item ->
            item.unread && item.notificationIds.any { it !in knownIds }
        }
        knownNotificationIds = knownIds + currentIds
        if (fresh.isNotEmpty()) {
            val message = if (fresh.size == 1) fresh.first().title else "${fresh.first().title}，另有 ${fresh.size - 1} 条更新"
            if (snackbarHostState.showSnackbar(message, actionLabel = "查看", withDismissAction = true) == SnackbarResult.ActionPerformed) {
                showNotifications = true
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    onClick = {
                        if (connectedMode) {
                            if (connectedState?.syncing == false) onSynchronize()
                        } else {
                            todayViewModel.openImport()
                        }
                    },
                    modifier = Modifier.semantics {
                        contentDescription = if (connectedMode) "同步任务" else "导入任务"
                    },
                ) {
                    when {
                        connectedState?.syncing == true -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        connectedMode -> Icon(
                            painter = painterResource(R.drawable.ic_sync),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                        else -> Text("+", fontSize = 26.sp)
                    }
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
                LaunchedEffect(Unit) {
                    if (connectedMode) onAutoSynchronize()
                }
                TodayScreen(todayViewModel, shareImageService, connectedMode = connectedMode) { key ->
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
                    notificationUnreadCount = connectedState?.notifications?.count(AppNotificationUi::unread) ?: 0,
                    onNotifications = connectedState?.let { { showNotifications = true } },
                    connectedSpaceName = connectedState?.spaceName,
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
                     backupEnabled = !connectedMode,
                     connectedSpaceName = connectedState?.spaceName,
                     connectedSyncStatus = connectedState?.syncStatus,
                     connectedSyncing = connectedState?.syncing == true,
                     onSynchronize = onSynchronize,
                     onLogout = connectedState?.let { { confirmLogout = true } },
                     onConnectedAccountAction = connectedState?.let { onConnectedAccountAction },
                )
            }
            composable(PROFILE_PRIVACY_ROUTE) {
                LegalScreen(if (connectedMode) LegalDocument.CONNECTED_PRIVACY else LegalDocument.PRIVACY, navController::popBackStack)
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
                        onCompletionCommitted = onSynchronize,
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

    if (!connectedMode && importState.visible && !backupOperationActive) {
        ImportDialog(
            state = importState,
            onInputChange = todayViewModel::updateImportInput,
            onPreview = todayViewModel::previewImport,
            onConfirm = todayViewModel::confirmImport,
            onDismiss = todayViewModel::closeImport,
        )
    }
    if (showNotifications && connectedState != null) {
        NotificationCenterDialog(
            notifications = connectedState.notifications,
            onMarkAllRead = onMarkNotificationsRead,
            onDismiss = { showNotifications = false },
        )
    }
    if (confirmLogout && connectedState != null) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出并清除本地数据？") },
            text = { Text("退出会清除此设备中的加密会话、空间缓存和未同步命令。未同步内容将无法恢复。") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("确认退出") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun NotificationCenterDialog(
    notifications: List<AppNotificationUi>,
    onMarkAllRead: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val unreadIds = notifications.filter(AppNotificationUi::unread).flatMap(AppNotificationUi::notificationIds)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("应用内通知") },
        text = {
            if (notifications.isEmpty()) {
                Text("暂无通知")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(notifications.take(50), key = AppNotificationUi::groupKey) { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                if (item.unread) "● ${item.title}" else item.title,
                                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                            )
                            if (item.notificationIds.size > 1) {
                                Text("包含 ${item.notificationIds.size} 次更新", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                            }
                            Text(formatNotificationTime(item.createdAt), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = unreadIds.isNotEmpty(),
                onClick = { onMarkAllRead(unreadIds) },
            ) { Text("全部标为已读") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun formatNotificationTime(value: String): String = runCatching {
    NOTIFICATION_TIME_FORMAT.format(Instant.parse(value).atZone(ZoneId.systemDefault()))
}.getOrDefault(value.replace('T', ' ').take(16))

private val NOTIFICATION_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")

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
