package com.ds.localtaskmanager.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ds.localtaskmanager.DstApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DELIVER) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val occurrenceKey = intent.getStringExtra(EXTRA_OCCURRENCE_KEY) ?: return
        val minutes = intent.getIntExtra(EXTRA_MINUTES, -1).takeIf { it >= 0 } ?: return
        val pending = goAsync()
        val application = context.applicationContext as DstApplication
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                application.reminderCoordinator.deliver(ReminderKey(taskId, occurrenceKey, minutes))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DELIVER = "com.ds.localtaskmanager.action.DELIVER_REMINDER"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_OCCURRENCE_KEY = "occurrenceKey"
        const val EXTRA_MINUTES = "minutes"
    }
}
