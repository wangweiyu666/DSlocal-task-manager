package com.ds.localtaskmanager.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ds.localtaskmanager.DstApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        val pending = goAsync()
        val application = context.applicationContext as DstApplication
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                application.reminderCoordinator.reconcileAll(intent.action)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
