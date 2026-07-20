package com.ds.localtaskmanager.reminder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.ds.localtaskmanager.MainActivity
import com.ds.localtaskmanager.R
import com.ds.localtaskmanager.data.TaskInstanceEntity

class AndroidReminderNotifier(context: Context) : ReminderNotifier {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "任务提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "在任务截止前发送不包含任务内容的隐私提醒"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    enableVibration(true)
                },
            )
        }
    }

    override fun notificationsEnabled(): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permissionGranted && manager.areNotificationsEnabled()
    }

    override fun post(instance: TaskInstanceEntity, key: ReminderKey) {
        if (!notificationsEnabled()) return
        val title = if (instance.required) "必做任务" else "选做任务"
        val body = if (instance.required) "有一项必做任务即将截止" else "有一项选做任务即将截止"
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_TASK_ID, key.taskId)
            putExtra(EXTRA_OCCURRENCE_KEY, key.occurrenceKey)
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            "${key.taskId}|${key.occurrenceKey}".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(appContext, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(appContext)
        }
        manager.notify(
            "${key.taskId}|${key.occurrenceKey}|${key.minutesBeforeDeadline}".hashCode(),
            builder
                .setSmallIcon(R.drawable.ic_task_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val CHANNEL_ID = "task_reminders"
        const val EXTRA_TASK_ID = "com.ds.localtaskmanager.extra.TASK_ID"
        const val EXTRA_OCCURRENCE_KEY = "com.ds.localtaskmanager.extra.OCCURRENCE_KEY"
    }
}
