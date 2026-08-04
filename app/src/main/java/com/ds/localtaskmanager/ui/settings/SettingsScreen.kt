package com.ds.localtaskmanager.ui.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ds.localtaskmanager.settings.AppSettings
import com.ds.localtaskmanager.settings.AppSettingsRepository
import com.ds.localtaskmanager.settings.AppThemeMode
import com.ds.localtaskmanager.ui.components.BackNavigationIcon
import kotlinx.coroutines.launch

@Composable
fun SettingsRoute(
    repository: AppSettingsRepository,
    onBack: () -> Unit,
    onNotificationPermissionChanged: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by repository.settings.collectAsStateWithLifecycle()
    var notificationsEnabled by remember { mutableStateOf(context.notificationsEnabled()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        repository.setNotificationPermissionRequested()
        notificationsEnabled = granted && context.notificationsEnabled()
        if (granted) onNotificationPermissionChanged()
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val current = context.notificationsEnabled()
                if (current && !notificationsEnabled) onNotificationPermissionChanged()
                notificationsEnabled = current
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsScreen(
        settings = settings,
        notificationsEnabled = notificationsEnabled,
        appVersion = context.appVersionName(),
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onThemeMode = repository::setThemeMode,
        onReduceMotion = repository::setReduceMotion,
        onNotificationAction = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !settings.notificationPermissionRequested
            ) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else if (!context.openNotificationSettings()) {
                scope.launch { snackbarHostState.showSnackbar("无法打开系统设置") }
            }
        },
        onResetPrivacy = {
            repository.resetInformationPrivacy()
            scope.launch { snackbarHostState.showSnackbar("下次分享信息告知图片时将再次提醒") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    notificationsEnabled: Boolean,
    appVersion: String,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit,
    onThemeMode: (AppThemeMode) -> Unit,
    onReduceMotion: (Boolean) -> Unit,
    onNotificationAction: () -> Unit,
    onResetPrivacy: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("settings-screen"),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("设置") },
                navigationIcon = { BackNavigationIcon(onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection("外观") {
                    AppThemeMode.entries.forEachIndexed { index, mode ->
                        if (index > 0) HorizontalDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onThemeMode(mode) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(mode.displayName, style = MaterialTheme.typography.bodyLarge)
                                if (mode == AppThemeMode.SYSTEM) {
                                    Text(
                                        "随系统浅色或深色模式切换",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            RadioButton(selected = settings.themeMode == mode, onClick = null)
                        }
                    }
                    HorizontalDivider()
                    Text(
                        "字体与字号跟随系统设置",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            SettingsSection("辅助体验") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onReduceMotion(!settings.reduceMotion) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("减少动效", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "减少页面切换、展开和数字变化动画",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.reduceMotion,
                            onCheckedChange = onReduceMotion,
                            modifier = Modifier.testTag("reduce-motion-switch"),
                        )
                    }
            }
            SettingsSection("通知") {
                    SettingsCardContent {
                        Text("任务提醒", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (notificationsEnabled) "通知已允许" else "通知未允许",
                            color = if (notificationsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "提醒时间由导入的任务决定，设置页不能修改。通知不会显示任务名称或其他任务内容。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onNotificationAction) {
                            Text(
                                if (notificationsEnabled || settings.notificationPermissionRequested) "打开系统通知设置"
                                else "启用任务提醒",
                            )
                        }
                    }
            }
            SettingsSection("隐私与分享") {
                    SettingsCardContent {
                        Text("信息告知分享提示", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (settings.informationPrivacyConfirmed) "已经确认过分享隐私提示。"
                            else "下次分享信息告知图片时将显示提醒。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onResetPrivacy,
                            enabled = settings.informationPrivacyConfirmed,
                            modifier = Modifier.testTag("reset-share-privacy"),
                        ) { Text("下次分享时重新提醒") }
                    }
            }
            SettingsSection("关于") {
                    SettingsCardContent {
                        AboutRow("应用版本", appVersion)
                        AboutRow("任务协议", "DST1 v1")
                        AboutRow("本地数据库", "Room v5")
                        HorizontalDivider()
                        Text(
                            "任务和设置数据仅保存在本机，应用不申请网络权限。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Card(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun SettingsCardContent(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val AppThemeMode.displayName: String
    get() = when (this) {
        AppThemeMode.SYSTEM -> "跟随系统"
        AppThemeMode.LIGHT -> "浅色"
        AppThemeMode.DARK -> "深色"
    }

private fun Context.notificationsEnabled(): Boolean {
    val manager = getSystemService(NotificationManager::class.java)
    val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return permissionGranted && manager.areNotificationsEnabled()
}

private fun Context.openNotificationSettings(): Boolean {
    val notificationIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    }
    if (runCatching { startActivity(notificationIntent) }.isSuccess) return true
    val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:$packageName")
    }
    return runCatching { startActivity(detailsIntent) }.isSuccess
}

@Suppress("DEPRECATION")
private fun Context.appVersionName(): String = runCatching {
    packageManager.getPackageInfo(packageName, 0).versionName ?: "未知"
}.getOrDefault("未知")
