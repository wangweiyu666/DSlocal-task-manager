package com.ds.localtaskmanager.connected

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

fun interface HourlySyncScheduler {
    fun update(session: CloudSessionEntity?)
}

internal class WorkManagerHourlySyncScheduler(
    context: Context,
    private val enqueue: (String, ExistingPeriodicWorkPolicy, PeriodicWorkRequest) -> Unit = { name, policy, request ->
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(name, policy, request)
        Unit
    },
    private val cancel: (String) -> Unit = { name ->
        WorkManager.getInstance(context).cancelUniqueWork(name)
        Unit
    },
) : HourlySyncScheduler {
    override fun update(session: CloudSessionEntity?) {
        if (session == null || session.role != "EXECUTOR" || session.blockedReason != null ||
            session.accountId.isNullOrBlank() || session.membershipId.isNullOrBlank() ||
            session.spaceId.isNullOrBlank() || session.sessionGeneration.isBlank()
        ) {
            cancel(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<ConnectedSyncWorker>(1, TimeUnit.HOURS)
            .setInitialDelay(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(
                ConnectedSyncCoordinator.KEY_ACCOUNT_ID to session.accountId,
                ConnectedSyncCoordinator.KEY_MEMBERSHIP_ID to session.membershipId,
                ConnectedSyncCoordinator.KEY_SPACE_ID to session.spaceId,
                ConnectedSyncCoordinator.KEY_GENERATION to session.sessionGeneration,
                ConnectedSyncCoordinator.KEY_PERIODIC to true,
            ))
            .build()
        // UPDATE keeps the original schedule; reopening the app must not postpone it.
        // The generation in inputData rejects already-running work from an old login.
        enqueue(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    companion object {
        const val WORK_NAME = "connected-hourly-sync"
    }
}
