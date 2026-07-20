package com.ds.localtaskmanager.ui.profile

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun ProfileScreen(onNotificationPermissionChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember {
        context.getSharedPreferences("notification_settings", Context.MODE_PRIVATE)
    }
    var requested by remember { mutableStateOf(preferences.getBoolean("permission_requested", false)) }
    var enabled by remember { mutableStateOf(context.notificationsEnabled()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        requested = true
        preferences.edit().putBoolean("permission_requested", true).apply()
        enabled = granted && context.notificationsEnabled()
        if (granted) onNotificationPermissionChanged()
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = context.notificationsEnabled()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("我的", style = MaterialTheme.typography.headlineLarge)
        Text("积分、统计与设置", style = MaterialTheme.typography.bodyLarge)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("任务提醒", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (enabled) "通知已启用。通知不会显示任务名称或其他任务内容。"
                    else "通知未启用。拒绝权限不会影响任务的其他功能。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !requested) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                },
                            )
                        }
                    },
                ) {
                    Text(if (enabled || requested) "打开通知设置" else "启用任务提醒")
                }
            }
        }
    }
}

private fun Context.notificationsEnabled(): Boolean {
    val manager = getSystemService(NotificationManager::class.java)
    val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return permissionGranted && manager.areNotificationsEnabled()
}
