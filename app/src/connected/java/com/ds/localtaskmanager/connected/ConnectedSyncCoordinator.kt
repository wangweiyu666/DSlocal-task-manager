package com.ds.localtaskmanager.connected

import android.content.Context
import com.ds.localtaskmanager.diagnostics.SyncTrace
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException

/** Result of a bounded background attempt. Kept explicit so permanent failures are diagnosable. */
enum class SyncAttemptResult { COMPLETE, RETRY, USER_ACTION, INVALID_IDENTITY }

/** Application-scoped coordination point shared by UI-triggered and WorkManager sync. */
class ConnectedSyncCoordinator internal constructor(
    private val context: Context,
    private val scheduler: SyncWorkScheduler = WorkManagerSyncWorkScheduler(context),
) {
    fun request(session: CloudSessionEntity, spaceId: String) {
        val (name, request) = buildRequest(session, spaceId)
        SyncTrace.event("WORK_ENQUEUE", request.id.toString())
        scheduler.enqueue(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun cancel(session: CloudSessionEntity?) {
        if (session == null) return
        session.spaceId?.let { WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(session, it)) }
    }

    companion object {
        const val KEY_ACCOUNT_ID = "accountId"
        const val KEY_MEMBERSHIP_ID = "membershipId"
        const val KEY_SPACE_ID = "spaceId"
        const val KEY_GENERATION = "sessionGeneration"
        fun uniqueWorkName(session: CloudSessionEntity, spaceId: String) =
            "connected-sync:${session.accountId}:${session.membershipId}:$spaceId:${session.sessionGeneration}"
    }

    internal fun buildRequest(session: CloudSessionEntity, spaceId: String): Pair<String, androidx.work.OneTimeWorkRequest> =
        uniqueWorkName(session, spaceId) to OneTimeWorkRequestBuilder<ConnectedSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(
                KEY_ACCOUNT_ID to session.accountId,
                KEY_MEMBERSHIP_ID to session.membershipId,
                KEY_SPACE_ID to spaceId,
                KEY_GENERATION to session.sessionGeneration,
            ))
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
}

internal fun interface SyncWorkScheduler {
    fun enqueue(name: String, policy: ExistingWorkPolicy, request: androidx.work.OneTimeWorkRequest)
}

private class WorkManagerSyncWorkScheduler(private val context: Context) : SyncWorkScheduler {
    override fun enqueue(name: String, policy: ExistingWorkPolicy, request: androidx.work.OneTimeWorkRequest) {
        val operation = WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
        operation.result.addListener({
            runCatching { operation.result.get() }
                .onSuccess { SyncTrace.event("WORK_REGISTERED", request.id.toString()) }
                .onFailure { SyncTrace.event("WORK_REGISTER_FAILED", request.id.toString(), it.javaClass.simpleName) }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
    }
}


class ConnectedSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        SyncTrace.event("WORK_START", id.toString(), "attempt=$runAttemptCount")
        val outcome = ConnectedSyncEngine.get(applicationContext as com.ds.localtaskmanager.DstApplication).runBackground(inputData)
        SyncTrace.event("WORK_OUTCOME", id.toString(), outcome.name)
        when (outcome) {
        SyncAttemptResult.COMPLETE -> Result.success()
        SyncAttemptResult.RETRY -> Result.retry()
        SyncAttemptResult.USER_ACTION -> Result.success()
        SyncAttemptResult.INVALID_IDENTITY -> Result.success()
        }
    } catch (_: TimeoutCancellationException) {
        SyncTrace.event("WORK_TIMEOUT", id.toString())
        Result.retry()
    } catch (error: kotlinx.coroutines.CancellationException) {
        SyncTrace.event("WORK_CANCELLED", id.toString())
        throw error
    } catch (error: Exception) {
        SyncTrace.event("WORK_EXCEPTION", id.toString(), error.javaClass.simpleName)
        throw error
    }
}
