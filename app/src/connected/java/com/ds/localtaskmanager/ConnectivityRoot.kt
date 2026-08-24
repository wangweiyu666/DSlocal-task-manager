package com.ds.localtaskmanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ds.localtaskmanager.connected.ConnectedNotification
import com.ds.localtaskmanager.connected.ConnectedRuntime
import com.ds.localtaskmanager.connected.ConnectedState
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import com.ds.localtaskmanager.ui.AppNotificationUi
import com.ds.localtaskmanager.ui.ConnectedUiState
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
    val runtime = remember(application) { ConnectedRuntime(application) }
    val state by runtime.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { runtime.restore() }
    when (val current = state) {
        ConnectedState.Restoring -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        ConnectedState.SignedOut -> ConnectedLogin(runtime)
        is ConnectedState.CodeSent -> ConnectedLogin(runtime, current.email, codeRequested = true)
        is ConnectedState.InvitationInbox -> Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("待领取的空间邀请", style = MaterialTheme.typography.headlineSmall)
            Text("这些邀请与当前登录邮箱绑定。未领取的邀请会在有效期内于每次登录后再次显示。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            current.invitations.forEach { invitation ->
                Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(invitation.spaceName, style = MaterialTheme.typography.titleMedium)
                        Text("有效期至 ${invitation.expiresAt.replace('T', ' ').take(16)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { runtime.acceptInvitation(invitation.id) }) { Text("接受并加入") }
                    }
                }
            }
            if (current.canEnterExistingSpace) OutlinedButton(onClick = runtime::enterExistingSpace) { Text("稍后处理，进入已有空间") }
            TextButton(onClick = runtime::logout) { Text("退出账号") }
        }
        is ConnectedState.WrongRole -> ConnectedMessage(
            title = "请使用 Web 管理端",
            message = "当前空间角色为 ${current.role}。Android 联网版仅提供执行者功能，服务器仍会独立校验成员角色。",
            action = "退出账号",
            onAction = runtime::logout,
        )
        is ConnectedState.Failure -> ConnectedMessage(
            title = "暂时无法进入空间",
            message = current.message,
            action = if (current.canRetry) "重试" else "退出账号",
            onAction = if (current.canRetry) runtime::retryEntry else runtime::logout,
            secondaryAction = if (current.canRetry) "退出账号" else null,
            onSecondaryAction = if (current.canRetry) runtime::logout else null,
        )
        is ConnectedState.Ready -> DstApp(
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
            connectedState = ConnectedUiState(
                spaceName = current.spaceName,
                syncStatus = current.syncStatus(),
                syncing = current.syncing,
                notifications = current.notifications.map(ConnectedNotification::toUi),
            ),
            onSynchronize = runtime::synchronize,
            onMarkNotificationsRead = runtime::markNotificationsRead,
            onLogout = runtime::logout,
        )
    }
}

private fun ConnectedState.Ready.syncStatus(): String = when {
    syncing -> "正在同步…"
    isolatedTaskCount > 0 -> "$isolatedTaskCount 个云任务待修复 · 本机可继续使用"
    pending > 0 -> "$pending 项等待同步"
    else -> "离线可执行 · 已同步"
}

private fun ConnectedNotification.toUi(): AppNotificationUi = AppNotificationUi(
    groupKey = groupKey,
    title = when (type) {
        "TASK_CREATED" -> "收到新任务"
        "TASK_UPDATED" -> "任务已更新"
        "TASK_CANCELLED" -> "任务已取消"
        "TASK_ARCHIVED" -> "任务已归档"
        "TASK_ACTIVE" -> "任务已恢复"
        "SPACE_TIMEZONE_CHANGED" -> "空间时区已更新"
        "RESULT_SELECTION_CHANGED" -> "任务结果已更新"
        else -> "空间内容已更新"
    },
    createdAt = createdAt,
    notificationIds = notificationIds,
    unread = unread,
)

@Composable
private fun ConnectedLogin(runtime: ConnectedRuntime, initialEmail: String = "", codeRequested: Boolean = false) {
    var email by remember(initialEmail) { mutableStateOf(initialEmail) }
    var code by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("DStationery 联网版", style = MaterialTheme.typography.headlineMedium)
        Text("执行者通过独立邮箱登录；任务和结果可离线保存。", Modifier.padding(top = 8.dp, bottom = 24.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(email, { email = it }, label = { Text("执行者邮箱") }, enabled = !codeRequested, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (codeRequested) OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("6 位验证码") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        Button(
            onClick = { if (codeRequested) runtime.verifyCode(email, code) else runtime.requestCode(email) },
            enabled = if (codeRequested) code.length == 6 else '@' in email,
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        ) { Text(if (codeRequested) "验证并进入" else "发送验证码") }
    }
}

@Composable
private fun ConnectedMessage(
    title: String,
    message: String,
    action: String,
    onAction: () -> Unit,
    secondaryAction: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(message, Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Button(onClick = onAction) { Text(action) }
        if (secondaryAction != null && onSecondaryAction != null) {
            TextButton(onClick = onSecondaryAction) { Text(secondaryAction) }
        }
    }
}
