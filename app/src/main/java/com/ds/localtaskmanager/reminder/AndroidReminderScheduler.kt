package com.ds.localtaskmanager.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ds.localtaskmanager.data.ReminderRecordEntity

class AndroidReminderScheduler(context: Context) : ReminderScheduler {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    override fun schedule(record: ReminderRecordEntity) {
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            record.scheduledForEpochMillis,
            pendingIntent(record),
        )
    }

    override fun cancel(record: ReminderRecordEntity) {
        alarmManager.cancel(pendingIntent(record))
    }

    private fun pendingIntent(record: ReminderRecordEntity): PendingIntent {
        val intent = Intent(appContext, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_DELIVER
            data = Uri.Builder()
                .scheme("dst-reminder")
                .authority("instance")
                .appendPath(record.taskId)
                .appendPath(record.occurrenceKey)
                .appendPath(record.minutesBeforeDeadline.toString())
                .build()
            putExtra(ReminderAlarmReceiver.EXTRA_TASK_ID, record.taskId)
            putExtra(ReminderAlarmReceiver.EXTRA_OCCURRENCE_KEY, record.occurrenceKey)
            putExtra(ReminderAlarmReceiver.EXTRA_MINUTES, record.minutesBeforeDeadline)
        }
        return PendingIntent.getBroadcast(
            appContext,
            stableCode(record.taskId, record.occurrenceKey, record.minutesBeforeDeadline),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stableCode(taskId: String, occurrenceKey: String, minutes: Int): Int =
        "$taskId|$occurrenceKey|$minutes".hashCode()
}
