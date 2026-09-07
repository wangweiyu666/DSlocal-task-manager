package com.ds.localtaskmanager.connected

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.room.withTransaction
import com.ds.localtaskmanager.DstApplication
import com.ds.localtaskmanager.data.DuplicateBatchException
import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.domain.TaskStateMachine
import com.ds.localtaskmanager.protocol.cloudOccurrenceKey
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

sealed interface ConnectedState {
    data object Restoring : ConnectedState
    data object SignedOut : ConnectedState
    data class CodeSent(val email: String) : ConnectedState
    data class InvitationInbox(
        val invitations: List<CloudInvitation>,
        val canEnterExistingSpace: Boolean,
    ) : ConnectedState
    data class Ready(
        val spaceName: String,
        val lastSyncedAt: String?,
        val pending: Int,
        val notifications: List<ConnectedNotification>,
        val isolatedTaskCount: Int = 0,
        val syncing: Boolean = false,
        val serviceMode: String = "NORMAL",
    ) : ConnectedState
    data class PrivacyRequired(val version: Int) : ConnectedState
    data class DeletionPending(val deletionDueAt: String?) : ConnectedState
    data class SensitiveActionPrompt(val action: String) : ConnectedState
    data class SensitiveCodeSent(val email: String, val action: String) : ConnectedState
    data class ExportReady(val fileName: String, val content: String) : ConnectedState
    data class DeletionRequested(val executeAfter: String?) : ConnectedState
    data class WrongRole(val role: String) : ConnectedState
    data class Failure(val message: String, val canRetry: Boolean) : ConnectedState
}

data class ConnectedNotification(
    val groupKey: String,
    val type: String,
    val createdAt: String,
    val notificationIds: List<String>,
    val unread: Boolean,
)

internal data class ResultSnapshot(
    val instance: TaskInstanceEntity,
    val information: com.ds.localtaskmanager.data.InformationSubmissionEntity?,
    val mood: com.ds.localtaskmanager.data.MoodSubmissionEntity?,
    val steps: List<com.ds.localtaskmanager.data.InstanceStepEntity>?,
)

open class ConnectedSyncEngine(
    private val application: DstApplication,
    private val api: CloudApi = CloudApi(),
    private val syncDatabase: ConnectedSyncDatabase = ConnectedSyncDatabase.create(application),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val enqueueWork: ((CloudSessionEntity) -> Unit)? = null,
) {
    companion object {
        private val lock = Any()
        private val instances = WeakHashMap<DstApplication, ConnectedSyncEngine>()
        fun get(application: DstApplication): ConnectedSyncEngine = synchronized(lock) {
            instances.getOrPut(application) { ConnectedSyncEngine(application) }
        }
    }
    private val dao = syncDatabase.dao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = false }
    private val mutableState = MutableStateFlow<ConnectedState>(ConnectedState.Restoring)
    private val sessionOperationMutex = Mutex()
    val state: StateFlow<ConnectedState> = mutableState.asStateFlow()
    private var challengeId: String? = null
    private var sensitiveChallengeId: String? = null
    private var pendingSensitiveAction: String? = null
    private var readyBeforeSensitive: ConnectedState.Ready? = null
    private var autoSyncIntervalMillis: Long? = NOTIFICATION_POLL_INTERVAL_MILLIS
    private var serviceMode: String = "NORMAL"
    private val synchronizationRequested = AtomicBoolean(false)
    private val synchronizationWorkerActive = AtomicBoolean(false)
    private val foregroundLifecycleStarted = AtomicBoolean(false)
    private val syncCoordinator = ConnectedSyncCoordinator(application)
    private var scheduledSignature: String? = null
    @Volatile private var visibleSessionGeneration: String? = null

    suspend fun runBackground(input: androidx.work.Data): SyncAttemptResult = withTimeout(3 * 60 * 1000L) {
        sessionOperationMutex.withLock { attemptSynchronization(input) }
    }

    /** Awaited foreground entry; uses the same session lock and retry state as the Worker. */
    suspend fun synchronizeNow(): SyncAttemptResult = sessionOperationMutex.withLock { attemptSynchronization(null) }

    private suspend fun attemptSynchronization(input: androidx.work.Data?): SyncAttemptResult {
        val session = dao.session() ?: return SyncAttemptResult.INVALID_IDENTITY
        if (!hasSyncIdentity(session)) return SyncAttemptResult.INVALID_IDENTITY
        if (input != null && (session.accountId != input.getString(ConnectedSyncCoordinator.KEY_ACCOUNT_ID) ||
            session.membershipId != input.getString(ConnectedSyncCoordinator.KEY_MEMBERSHIP_ID) ||
            session.spaceId != input.getString(ConnectedSyncCoordinator.KEY_SPACE_ID) ||
            session.sessionGeneration != input.getString(ConnectedSyncCoordinator.KEY_GENERATION)
        )) return SyncAttemptResult.INVALID_IDENTITY
        recoverPendingLocalWorkUnlocked(schedule = false)
        val spaceId = requireNotNull(session.spaceId)
        val latest = dao.session() ?: return SyncAttemptResult.INVALID_IDENTITY
        if (latest.blockedReason != null) return SyncAttemptResult.USER_ACTION
        if (input != null && dao.outbox(spaceId).none { it.state != "PERMANENT_FAILURE" } && !latest.needsSync) return SyncAttemptResult.COMPLETE
        if (latest.retryUntilEpochMillis > nowMillis()) {
            if (input == null) scheduleBackground(latest)
            return SyncAttemptResult.RETRY
        }
        dao.updateSyncControl(latest.sessionGeneration, latest.retryUntilEpochMillis, true, null)
        return try {
            withSessionRecovery(requireNotNull(dao.session()), scheduleRetry = input == null) { active ->
                val account = api.accountStatus(active.accessToken)
                if (account.status != "ACTIVE") {
                    purgeBusinessDataPreservingSession(active)
                    mutableState.value = ConnectedState.DeletionPending(account.deletionDueAt)
                    throw CloudApiException(409, "ACCOUNT_DELETION_PENDING", "账号正在删除恢复期内", false)
                }
                if (account.privacyNoticeVersion < account.requiredPrivacyNoticeVersion) {
                    mutableState.value = ConnectedState.PrivacyRequired(account.requiredPrivacyNoticeVersion)
                    throw CloudApiException(428, "PRIVACY_ACK_REQUIRED", "请先确认隐私说明", false)
                }
                synchronize(active)
            }
                dao.updateSyncControl(latest.sessionGeneration, 0, false, null)
            SyncAttemptResult.COMPLETE
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            when {
                error is CloudApiException && error.code.contains("DELETION") -> {
                    dao.session()?.takeIf { it.sessionGeneration == latest.sessionGeneration }?.let {
                        purgeBusinessDataPreservingSession(it)
                    }
                    mutableState.value = ConnectedState.DeletionPending(null)
                    SyncAttemptResult.USER_ACTION
                }
                isTerminalSessionFailure(error) -> {
                    purge(session.spaceId)
                    mutableState.value = ConnectedState.SignedOut
                    SyncAttemptResult.INVALID_IDENTITY
                }
                error is CloudApiException && error.retryable -> SyncAttemptResult.RETRY
                else -> {
                    val commandFailure = (error as? CloudApiException)?.code == "COMMAND_REJECTED"
                    dao.updateSyncControl(latest.sessionGeneration, 0, !commandFailure,
                        if (commandFailure) null else (error as? CloudApiException)?.code ?: "SYNC_FAILURE")
                    if (mutableState.value !is ConnectedState.PrivacyRequired && mutableState.value !is ConnectedState.DeletionPending)
                        mutableState.value = ConnectedState.Failure(error.message ?: "同步失败", false)
                    SyncAttemptResult.USER_ACTION
                }
            }
        }
    }

    private fun hasSyncIdentity(session: CloudSessionEntity) = session.role == "EXECUTOR" &&
        !session.accountId.isNullOrBlank() && !session.membershipId.isNullOrBlank() &&
        !session.spaceId.isNullOrBlank() && session.sessionGeneration.isNotBlank()

    private suspend fun scheduleBackground(session: CloudSessionEntity) {
        if (!hasSyncIdentity(session) || session.blockedReason != null) return
        val signature = session.sessionGeneration + ":" + session.needsSync + ":" +
            dao.outbox(requireNotNull(session.spaceId)).filter { it.state != "PERMANENT_FAILURE" }.joinToString { it.commandId }
        if (signature == scheduledSignature) return
        (enqueueWork ?: { value -> syncCoordinator.request(value, requireNotNull(value.spaceId)) })(session)
        scheduledSignature = signature
    }
    fun requestBackground(session: CloudSessionEntity, spaceId: String) = syncCoordinator.request(session, spaceId)
    fun onLocalMutationCommitted() {
        scope.launch {
            catchSyncFailure {
                recoverPendingLocalWork(schedule = true)
                if (mutableState.value is ConnectedState.Ready) synchronize()
            }.onFailure { application.diagnosticEvents.record("connected-sync", "RECOVERY_SCHEDULE_FAILED", false) }
        }
    }
    fun recoverAfterStartup() {
        scope.launch {
            catchSyncFailure { recoverPendingLocalWork(schedule = true) }
                .onFailure { application.diagnosticEvents.record("connected-sync", "STARTUP_RECOVERY_FAILED", false) }
        }
    }

    suspend fun recoverPendingLocalWork(schedule: Boolean = true): Boolean = sessionOperationMutex.withLock { recoverPendingLocalWorkUnlocked(schedule) }

    private suspend fun recoverPendingLocalWorkUnlocked(schedule: Boolean): Boolean {
        val session = dao.session() ?: return false
        val spaceId = session.spaceId ?: return false
        if (session.role != "EXECUTOR" || session.accountId.isNullOrBlank() || session.membershipId.isNullOrBlank()) return false
        dao.meta(spaceId)?.let { meta ->
            val membership = CloudMembership(requireNotNull(session.membershipId), "EXECUTOR", spaceId, session.spaceName ?: "", meta.timeZone, meta.timeZoneVersion)
            enqueueLocalWork(session, membership, dao.entities(spaceId))
        }
        val pending = session.blockedReason == null && (session.needsSync || dao.outbox(spaceId).any { it.state != "PERMANENT_FAILURE" })
        if (pending && schedule) scheduleBackground(session)
        return pending
    }

    internal fun startForegroundLifecycle() {
        if (!foregroundLifecycleStarted.compareAndSet(false, true)) return
        val connectivity = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (mutableState.value is ConnectedState.Ready) synchronize()
            }
        })
        scope.launch {
            application.database.instanceDao().observeLatestUpdateEpochMillis().collectLatest {
                val current = mutableState.value as? ConnectedState.Ready ?: return@collectLatest
                val session = dao.session() ?: return@collectLatest
                mutableState.value = readyState(session, current.lastSyncedAt, current.isolatedTaskCount, current.syncing)
            }
        }
        scope.launch {
            while (isActive) {
                delay(autoSyncIntervalMillis ?: PROTECTED_POLL_RECHECK_MILLIS)
                if (autoSyncIntervalMillis != null) refreshNotifications()
            }
        }
    }

    fun restore() {
        scope.launchSessionOperation {
            val session = dao.session()
            if (session == null) mutableState.value = ConnectedState.SignedOut
            else catchSyncFailure {
                withSessionRecovery(session) { bootstrap(it) }
            }.onFailure { error ->
                when {
                    isTerminalSessionFailure(error) -> {
                        purge(session.spaceId)
                        mutableState.value = ConnectedState.SignedOut
                    }
                    session.role == "EXECUTOR" && session.spaceId != null && error is CloudApiException && error.retryable ->
                        mutableState.value = requireNotNull(cachedReady(session))
                    else -> mutableState.value = ConnectedState.Failure(error.message ?: "空间载入失败", error is CloudApiException && error.retryable)
                }
            }
        }
    }

    fun requestCode(email: String) {
        scope.launchSessionOperation {
            mutableState.value = ConnectedState.Restoring
            catchSyncFailure { api.requestChallenge(email.trim()) }
                .onSuccess { challengeId = it; mutableState.value = ConnectedState.CodeSent(email.trim()) }
                .onFailure { mutableState.value = ConnectedState.Failure(it.message ?: "验证码发送失败", true) }
        }
    }

    fun verifyCode(email: String, code: String) {
        val id = challengeId ?: return
        scope.launchSessionOperation {
            mutableState.value = ConnectedState.Restoring
            catchSyncFailure {
                val tokens = api.verify(id, email, code)
                dao.session()?.let { old -> purge(old.spaceId) }
                val session = CloudSessionEntity(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    csrfToken = tokens.csrfToken,
                    accessExpiresAt = tokens.accessExpiresAt,
                    sessionGeneration = uuidV7(),
                )
                dao.saveSession(session)
                withSessionRecovery(session) { bootstrap(it) }
            }.onFailure { mutableState.value = ConnectedState.Failure(it.message ?: "邮箱验证失败", true) }
        }
    }

    fun acceptInvitation(invitationId: String) {
        scope.launchSessionOperation {
            mutableState.value = ConnectedState.Restoring
            val session = dao.session() ?: run { mutableState.value = ConnectedState.SignedOut; return@launchSessionOperation }
            catchSyncFailure {
                withSessionRecovery(session) {
                    api.acceptInvitation(it.accessToken, invitationId)
                    bootstrap(it)
                }
            }.onFailure { mutableState.value = ConnectedState.Failure(it.message ?: "邀请领取失败", true) }
        }
    }

    fun enterExistingSpace() {
        scope.launchSessionOperation {
            mutableState.value = ConnectedState.Restoring
            val session = dao.session() ?: run { mutableState.value = ConnectedState.SignedOut; return@launchSessionOperation }
            catchSyncFailure { withSessionRecovery(session) { bootstrap(it, showInvitations = false) } }
                .onFailure { mutableState.value = ConnectedState.Failure(it.message ?: "空间载入失败", true) }
        }
    }

    fun retryEntry() {
        scope.launchSessionOperation {
            mutableState.value = ConnectedState.Restoring
            val session = dao.session() ?: run { mutableState.value = ConnectedState.SignedOut; return@launchSessionOperation }
            catchSyncFailure { withSessionRecovery(session) { bootstrap(it) } }
                .onFailure { error ->
                    if (isTerminalSessionFailure(error)) {
                        purge(session.spaceId)
                        mutableState.value = ConnectedState.SignedOut
                    } else {
                        mutableState.value = ConnectedState.Failure(error.message ?: "空间载入失败", error is CloudApiException && error.retryable)
                    }
                }
        }
    }

    fun synchronize() {
        if (mutableState.value !is ConnectedState.Ready) return
        synchronizationRequested.set(true)
        startSynchronizationWorker()
    }

    fun synchronizeIfStale() {
        val current = mutableState.value as? ConnectedState.Ready ?: return
        if (current.syncing || !shouldRunAutomaticSync(current.lastSyncedAt)) return
        synchronize()
    }

    private fun startSynchronizationWorker() {
        if (!synchronizationWorkerActive.compareAndSet(false, true)) return
        val current = mutableState.value as? ConnectedState.Ready
        if (current == null) {
            synchronizationRequested.set(false)
            synchronizationWorkerActive.set(false)
            return
        }
        mutableState.value = current.copy(syncing = true)
        scope.launch {
            try {
                while (synchronizationRequested.getAndSet(false)) {
                    val successful = sessionOperationMutex.withLock { runSynchronization() }
                    if (!successful || mutableState.value !is ConnectedState.Ready) {
                        synchronizationRequested.set(false)
                        break
                    }
                }
            } finally {
                (mutableState.value as? ConnectedState.Ready)?.let { latest ->
                    if (latest.syncing) mutableState.value = latest.copy(syncing = false)
                }
                synchronizationWorkerActive.set(false)
                if (synchronizationRequested.get() && mutableState.value is ConnectedState.Ready) {
                    startSynchronizationWorker()
                }
            }
        }
    }

    private suspend fun runSynchronization(): Boolean {
        return attemptSynchronization(null) == SyncAttemptResult.COMPLETE
    }

    fun logout() {
        scope.launchSessionOperation {
            val session = dao.session()
            catchSyncFailure { if (session != null) api.logout(session.accessToken) }
            purge(session?.spaceId)
            mutableState.value = ConnectedState.SignedOut
        }
    }

    fun acknowledgePrivacy(version: Int) {
        scope.launchSessionOperation {
            val session = dao.session() ?: run { mutableState.value = ConnectedState.SignedOut; return@launchSessionOperation }
            catchSyncFailure {
                api.acknowledgePrivacy(session.accessToken, version)
                bootstrap(session)
            }.onFailure { mutableState.value = ConnectedState.Failure(it.message ?: "隐私确认失败", true) }
        }
    }

    fun cancelDeletion() {
        scope.launchSessionOperation {
            val session = dao.session() ?: run { mutableState.value = ConnectedState.SignedOut; return@launchSessionOperation }
            catchSyncFailure {
                api.cancelDeletion(session.accessToken)
                bootstrap(session)
            }.onFailure { mutableState.value = ConnectedState.Failure(it.message ?: "账号恢复失败", true) }
        }
    }

    fun beginSensitiveAction(action: String) {
        val current = mutableState.value as? ConnectedState.Ready ?: return
        readyBeforeSensitive = current
        pendingSensitiveAction = action
        sensitiveChallengeId = null
        mutableState.value = ConnectedState.SensitiveActionPrompt(action)
    }

    fun requestSensitiveCode(email: String) {
        val action = pendingSensitiveAction ?: return
        scope.launchSessionOperation {
            mutableState.value = ConnectedState.Restoring
            catchSyncFailure { api.requestChallenge(email.trim(), "SENSITIVE_ACTION") }
                .onSuccess { sensitiveChallengeId = it; mutableState.value = ConnectedState.SensitiveCodeSent(email.trim(), action) }
                .onFailure { mutableState.value = ConnectedState.Failure(it.message ?: "安全验证码发送失败", true) }
        }
    }

    fun verifySensitiveCode(email: String, code: String) {
        val action = pendingSensitiveAction ?: return
        val id = sensitiveChallengeId ?: return
        scope.launchSessionOperation {
            mutableState.value = ConnectedState.Restoring
            val session = dao.session() ?: run { mutableState.value = ConnectedState.SignedOut; return@launchSessionOperation }
            catchSyncFailure {
                api.verifySensitive(session.accessToken, id, email, code)
                when (action) {
                    "EXPORT" -> {
                        val spaceId = requireNotNull(session.spaceId)
                        val content = api.exportData(session.accessToken, spaceId)
                        mutableState.value = ConnectedState.ExportReady("DStationery-$spaceId-${LocalDate.now()}.dsexport.json", content)
                    }
                    "DELETE_SCHEDULED", "DELETE_IMMEDIATE" -> {
                        val result = api.requestDeletion(session.accessToken, action == "DELETE_IMMEDIATE")
                        val due = result["executeAfter"]?.jsonPrimitive?.contentOrNull
                        purge(session.spaceId)
                        mutableState.value = if (action == "DELETE_IMMEDIATE") ConnectedState.SignedOut else ConnectedState.DeletionRequested(due)
                    }
                    else -> error("未知敏感操作")
                }
            }.onFailure { mutableState.value = ConnectedState.Failure(it.message ?: "敏感操作失败", true) }
        }
    }

    fun leaveSensitiveAction() {
        pendingSensitiveAction = null
        sensitiveChallengeId = null
        mutableState.value = readyBeforeSensitive ?: ConnectedState.SignedOut
    }

    fun markNotificationsRead(ids: List<String>) {
        if (ids.isEmpty()) return
        val expectedGeneration = visibleSessionGeneration ?: return
        val readIds = ids.toSet()
        (mutableState.value as? ConnectedState.Ready)?.let { current ->
            mutableState.value = current.copy(
                notifications = current.notifications.map { item ->
                    item.copy(unread = item.unread && item.notificationIds.any { it !in readIds })
                },
            )
        }
        scope.launchSessionOperation {
            val session = dao.session() ?: return@launchSessionOperation
            if (session.sessionGeneration != expectedGeneration) return@launchSessionOperation
            val spaceId = session.spaceId ?: return@launchSessionOperation
            val commandId = uuidV7()
            enqueue(
                spaceId,
                "notification-read:${ids.sorted().joinToString(",")}",
                buildCommand(
                    type = "NOTIFICATION_READ",
                    entityId = commandId,
                    payload = buildJsonObject { put("notificationIds", buildJsonArray { ids.distinct().take(100).forEach { add(JsonPrimitive(it)) } }) },
                ),
            )
            scheduleBackground(session)
            synchronize()
        }
    }

    private suspend fun refreshNotifications() {
        var shouldSynchronize = false
        sessionOperationMutex.withLock {
            val current = mutableState.value as? ConnectedState.Ready ?: return
            if (current.syncing) return
            val session = dao.session() ?: return
            catchSyncFailure {
                withSessionRecovery(session) { active ->
                    api.notifications(active.accessToken, requireNotNull(active.spaceId))
                }
            }.onSuccess { notifications ->
                val latest = mutableState.value as? ConnectedState.Ready ?: return@onSuccess
                val refreshed = notifications.map { item ->
                    ConnectedNotification(
                        groupKey = item.groupKey,
                        type = item.type,
                        createdAt = item.createdAt,
                        notificationIds = item.notificationIds,
                        unread = item.unread,
                    )
                }
                shouldSynchronize = hasNewNotificationIds(latest.notifications, refreshed)
                mutableState.value = latest.copy(
                    notifications = refreshed,
                )
            }.onFailure { error ->
                if (isTerminalSessionFailure(error)) {
                    purge(session.spaceId)
                    mutableState.value = ConnectedState.SignedOut
                }
            }
        }
        if (shouldSynchronize) synchronize()
    }

    private suspend fun bootstrap(session: CloudSessionEntity, showInvitations: Boolean = true) {
        val account = api.accountStatus(session.accessToken)
        if (account.status == "DELETION_PENDING") {
            purgeBusinessDataPreservingSession(session)
            mutableState.value = ConnectedState.DeletionPending(account.deletionDueAt)
            return
        }
        if (account.privacyNoticeVersion < account.requiredPrivacyNoticeVersion) {
            mutableState.value = ConnectedState.PrivacyRequired(account.requiredPrivacyNoticeVersion)
            return
        }
        val bootstrap = api.bootstrap(session.accessToken)
        val accountId = bootstrap.accountId
        val memberships = bootstrap.memberships
        serviceMode = bootstrap.serviceMode
        autoSyncIntervalMillis = bootstrap.autoSyncIntervalSeconds?.times(1_000L)
        if (showInvitations) {
            val invitations = api.invitations(session.accessToken)
            if (invitations.isNotEmpty()) {
                mutableState.value = ConnectedState.InvitationInbox(invitations, memberships.any { it.role == "EXECUTOR" })
                return
            }
        }
        val membership = if (session.membershipId != null) {
            memberships.firstOrNull { it.id == session.membershipId }
        } else memberships.firstOrNull()
        if (membership == null) {
            purge(session.spaceId)
            mutableState.value = ConnectedState.Failure("此账号当前不属于任何空间", false)
            return
        }
        if (membership.role != "EXECUTOR") {
            dao.saveSession(session.copy(accountId = accountId, membershipId = membership.id, spaceId = membership.spaceId, spaceName = membership.spaceName, role = membership.role))
            mutableState.value = ConnectedState.WrongRole(membership.role)
            return
        }
        val saved = session.copy(accountId = accountId, membershipId = membership.id, spaceId = membership.spaceId, spaceName = membership.spaceName, role = membership.role)
        if (session.membershipId != null && (session.accountId != null && session.accountId != accountId || session.spaceId != null && session.spaceId != membership.spaceId)) {
            purge(session.spaceId)
            mutableState.value = ConnectedState.SignedOut
            return
        }
        dao.saveSession(saved)
        val meta = dao.meta(membership.spaceId)
        mutableState.value = readyState(saved, meta?.lastSyncedAt, syncing = true)
        synchronize(saved, membership)
    }

    private suspend fun synchronize(
        session: CloudSessionEntity,
        knownMembership: CloudMembership? = null,
        keepSyncing: Boolean = false,
    ) {
        val membership = knownMembership ?: api.bootstrap(session.accessToken).let { bootstrap ->
            if (session.accountId != null && bootstrap.accountId != session.accountId) return@let null
            serviceMode = bootstrap.serviceMode
            autoSyncIntervalMillis = bootstrap.autoSyncIntervalSeconds?.times(1_000L)
            bootstrap.memberships.firstOrNull { it.id == session.membershipId }
        }
        if (membership == null || membership.role != "EXECUTOR" ||
            (session.spaceId != null && membership.spaceId != session.spaceId) ||
            (session.membershipId != null && membership.id != session.membershipId)
        ) {
            purge(session.spaceId)
            mutableState.value = ConnectedState.Failure("空间成员资格已失效，本地空间缓存已清除", false)
            throw CloudApiException(401, "MEMBERSHIP_REVOKED", "空间成员资格已失效", false)
        }
        flush(session, membership.spaceId)
        pullRemote(session, membership)
        val entities = dao.entities(membership.spaceId)
        val isolatedTaskCount = materializeTasks(entities, membership.id)
        application.instanceGenerationService.reconcileAll(LocalDate.now(ZoneId.of(membership.timeZone)))
        enqueueLocalWork(session, membership, entities)
        flush(session, membership.spaceId)
        dao.meta(membership.spaceId)?.let { dao.saveMeta(it.copy(lastSyncedAt = Instant.now().toString())) }
        dao.updateSyncControl(session.sessionGeneration, 0, false, null)
        mutableState.value = readyState(session, Instant.now().toString(), isolatedTaskCount, syncing = keepSyncing)
    }

    private suspend fun pullRemote(session: CloudSessionEntity, membership: CloudMembership) {
        val existing = dao.meta(membership.spaceId)
        if (existing?.changesCursor == null) {
            replaceFromSnapshot(session, membership)
            return
        }
        var cursor = requireNotNull(existing.changesCursor)
        try {
            var hasMore: Boolean
            do {
                val page = api.changes(session.accessToken, membership.spaceId, cursor)
                hasMore = pageHasMore(page)
                syncDatabase.withTransaction {
                    page["changes"]!!.jsonArray.forEach { raw ->
                        val change = raw.jsonObject
                        val payloadVersion = change["payloadVersion"]?.jsonPrimitive?.intOrNull ?: 1
                        require(payloadVersion == 1) { "同步数据版本过新，请升级应用" }
                        val key = "${membership.spaceId}:${change.requiredString("entityType")}:${change.requiredString("entityId")}"
                        if (change.requiredString("operation") == "DELETE") dao.deleteEntity(key)
                        else dao.putEntities(listOf(change.toEntity(membership.spaceId, "createdAt")))
                    }
                    cursor = page.requiredString("nextCursor")
                    dao.saveMeta(CloudSyncMetaEntity(membership.spaceId, cursor, existing?.lastSyncedAt, membership.timeZone, membership.timeZoneVersion))
                }
            } while (hasMore)
        } catch (error: CloudApiException) {
            if (error.code == "SYNC_CURSOR_EXPIRED") replaceFromSnapshot(session, membership) else throw error
        }
    }

    private suspend fun replaceFromSnapshot(session: CloudSessionEntity, membership: CloudMembership) {
        val entities = mutableListOf<CloudEntity>()
        var cursor: String? = null
        var changesCursor: String? = null
        do {
            val page = api.snapshot(session.accessToken, membership.spaceId, cursor)
            page["entities"]!!.jsonArray.forEach { raw -> entities += raw.jsonObject.toEntity(membership.spaceId) }
            changesCursor = page.requiredString("changesCursor")
            cursor = if (pageHasMore(page)) page.requiredString("nextCursor") else null
        } while (cursor != null)
        syncDatabase.withTransaction {
            dao.clearEntities(membership.spaceId)
            dao.putEntities(entities)
            dao.saveMeta(CloudSyncMetaEntity(membership.spaceId, changesCursor, dao.meta(membership.spaceId)?.lastSyncedAt, membership.timeZone, membership.timeZoneVersion))
        }
    }

    private suspend fun cachedReady(session: CloudSessionEntity): ConnectedState.Ready? {
        val spaceId = session.spaceId ?: return null
        if (session.role != "EXECUTOR") return null
        val meta = dao.meta(spaceId)
        return readyState(session, meta?.lastSyncedAt)
    }

    private suspend fun readyState(
        session: CloudSessionEntity,
        lastSyncedAt: String?,
        isolatedTaskCount: Int = 0,
        syncing: Boolean = false,
    ): ConnectedState.Ready {
        visibleSessionGeneration = session.sessionGeneration
        val spaceId = requireNotNull(session.spaceId)
        val entities = dao.entities(spaceId)
        val readIds = entities.filter { it.entityType == "notification_read" }.flatMap { entity ->
            val payload = json.parseToJsonElement(entity.payloadJson).jsonObject
            payload["notificationIds"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        }.toSet()
        val notifications = entities.filter { it.entityType == "notification" }
            .groupBy { entity -> json.parseToJsonElement(entity.payloadJson).jsonObject["groupKey"]?.jsonPrimitive?.contentOrNull ?: entity.entityId }
            .map { (groupKey, grouped) ->
                val ordered = grouped.sortedByDescending { it.updatedAt }
                val payload = json.parseToJsonElement(ordered.first().payloadJson).jsonObject
                ConnectedNotification(groupKey, payload["type"]?.jsonPrimitive?.contentOrNull ?: "SPACE_CHANGED", ordered.first().updatedAt, ordered.map { it.entityId }, ordered.any { it.entityId !in readIds })
            }
            .sortedByDescending { it.createdAt }
        val localPending = lastSyncedAt
            ?.let { catchSyncFailure { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?.let { application.database.instanceDao().countUpdatedAfter(it) }
            ?: 0
        return ConnectedState.Ready(
            session.spaceName ?: "离线空间",
            lastSyncedAt,
            maxOf(dao.outbox(spaceId).size, localPending),
            notifications,
            isolatedTaskCount,
            syncing,
            serviceMode,
        )
    }

    private suspend fun ensureFreshSession(session: CloudSessionEntity): CloudSessionEntity {
        if (!needsAccessTokenRefresh(session.accessExpiresAt, Instant.ofEpochMilli(nowMillis()))) return session
        val refreshed = api.refresh(session.refreshToken)
        return session.withTokens(refreshed).also { dao.saveSession(it) }
    }

    private suspend fun <T> withSessionRecovery(
        session: CloudSessionEntity,
        scheduleRetry: Boolean = true,
        operation: suspend (CloudSessionEntity) -> T,
    ): T {
        val stored = dao.session() ?: throw CloudApiException(401, "SESSION_EXPIRED", "请重新登录", false)
        check(stored.sessionGeneration == session.sessionGeneration) { "会话已更换" }
        try {
            if (stored.retryUntilEpochMillis > nowMillis()) {
                val seconds = ((stored.retryUntilEpochMillis - nowMillis() + 999) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                throw CloudApiException(429, "RATE_LIMITED", "请稍后重试", true, seconds)
            }
            val current = ensureFreshSession(stored)
            return try {
                operation(current)
            } catch (error: CloudApiException) {
                if (!isTerminalSessionFailure(error)) throw error
                val refreshed = api.refresh(current.refreshToken)
                val saved = (dao.session() ?: current).withTokens(refreshed)
                dao.saveSession(saved)
                operation(saved)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: CloudApiException) {
            if (error.retryable) {
                val latest = dao.session()
                if (latest?.sessionGeneration == session.sessionGeneration) {
                    val delay = (error.retryAfterSeconds?.coerceIn(1, 86400) ?: 30).toLong() * 1000
                    val until = maxOf(latest.retryUntilEpochMillis, nowMillis() + delay)
                    dao.updateSyncControl(latest.sessionGeneration, until, true, latest.blockedReason)
                    if (scheduleRetry) scheduleBackground(latest.copy(retryUntilEpochMillis = until, needsSync = true))
                }
            }
            throw error
        }
    }

    private fun CoroutineScope.launchSessionOperation(block: suspend () -> Unit) = launch {
        sessionOperationMutex.withLock { block() }
    }

    private suspend fun materializeTasks(entities: List<CloudEntity>, membershipId: String): Int {
        var isolatedTaskCount = 0
        val assignmentStatusByTask = entities.filter { entity ->
            if (entity.entityType != "assignment") return@filter false
            val payload = json.parseToJsonElement(entity.payloadJson).jsonObject
            payload.requiredString("executorMembershipId") == membershipId
        }.associate { entity ->
            val payload = json.parseToJsonElement(entity.payloadJson).jsonObject
            payload.requiredString("taskId") to (payload["status"]?.jsonPrimitive?.contentOrNull ?: "ACTIVE")
        }
        entities.filter { it.entityType == "task" }.sortedBy { it.updatedAt }.forEach { entity ->
            val payload = json.parseToJsonElement(entity.payloadJson).jsonObject
            val status = payload["status"]?.jsonPrimitive?.contentOrNull ?: "ACTIVE"
            val assignmentStatus = assignmentStatusByTask[entity.entityId]
            val assigned = shouldKeepAssignedCloudTask(status, assignmentStatus)
            val content = payload["content"]?.jsonObject
            val canonical = when {
                status == "ACTIVE" && assigned && content != null -> content
                status != "ACTIVE" || !assigned -> buildJsonObject {
                    val removalReason = if (status != "ACTIVE") status else "UNASSIGNED"
                    put("v", 1); put("b", deterministicDstId("$removalReason:${entity.entityId}:${entity.entityVersion}")); put("z", buildJsonArray { add(JsonPrimitive(entity.entityId)) })
                }
                else -> null
            } ?: return@forEach
            val minor = canonical["sv"]?.jsonPrimitive?.intOrNull ?: 0
            try {
                val preview = application.importService.previewCanonicalJson(canonical.toString(), minor)
                application.importService.import(preview)
            } catch (_: DuplicateBatchException) {
                // Re-reading a stable snapshot is intentionally idempotent.
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A malformed or unsupported remote revision must not lock the
                // executor out of already cached work. Keep it quarantined until
                // an administrator publishes a compatible revision.
                isolatedTaskCount += 1
            }
        }
        return isolatedTaskCount
    }

    private suspend fun enqueueLocalWork(session: CloudSessionEntity, membership: CloudMembership, entities: List<CloudEntity>) {
        val assignments = entities.filter { entity ->
            if (entity.entityType != "assignment") return@filter false
            val payload = json.parseToJsonElement(entity.payloadJson).jsonObject
            payload.requiredString("executorMembershipId") == session.membershipId &&
                (payload["status"]?.jsonPrimitive?.contentOrNull ?: "ACTIVE") == "ACTIVE"
        }.associateBy { json.parseToJsonElement(it.payloadJson).jsonObject.requiredString("taskId") }
        val tasks = entities.filter { it.entityType == "task" }.associateBy { it.entityId }
        val instances = application.database.instanceDao().queryHistory(null, null)
        instances.forEach { instance ->
            val task = tasks[instance.taskId] ?: return@forEach
            val assignmentEntity = assignments[instance.taskId] ?: return@forEach
            val assignment = json.parseToJsonElement(assignmentEntity.payloadJson).jsonObject
            val assignmentId = assignment.requiredString("id")
            val meta = dao.meta(membership.spaceId) ?: return@forEach
            val localKey = "${membership.spaceId}:${instance.taskId}:${instance.occurrenceKey}"
            val storedMap = dao.occurrenceMap(localKey)
            val localTime = "${instance.taskDate}T00:00"
            val occurrenceKey = storedMap?.cloudOccurrenceKey ?: cloudOccurrenceKey(instance.taskId, task.entityVersion, meta.timeZoneVersion, java.time.LocalDateTime.parse(localTime))
            val occurrenceRevision = storedMap?.taskRevision ?: task.entityVersion
            val occurrenceTimeZoneVersion = storedMap?.timeZoneVersion ?: meta.timeZoneVersion
            val occurrenceAssignmentId = storedMap?.assignmentId ?: assignmentId
            val scheduledAt = storedMap?.scheduledAt ?: LocalDate.parse(instance.taskDate).atStartOfDay(ZoneId.of(meta.timeZone)).toInstant().toString()
            if (storedMap == null) dao.saveOccurrenceMap(CloudOccurrenceMapEntity(localKey, membership.spaceId, instance.taskId, instance.occurrenceKey, occurrenceKey, scheduledAt, assignmentId, task.entityVersion, meta.timeZoneVersion))
            enqueue(
                membership.spaceId,
                "occurrence:$occurrenceKey",
                buildCommand(
                    type = "OCCURRENCE_UPSERT",
                    entityId = occurrenceKey,
                    payload = buildJsonObject {
                        put("taskId", instance.taskId); put("assignmentId", occurrenceAssignmentId); put("occurrenceKey", occurrenceKey)
                        put("localDate", instance.taskDate); put("scheduledAt", scheduledAt); put("taskRevision", occurrenceRevision); put("timeZoneVersion", occurrenceTimeZoneVersion)
                    },
                ),
            )
            // Read durable undo logs, including undos made before a process restart
            // or followed by another completion while offline.
            application.database.auditDao().getLogs(instance.taskId, instance.occurrenceKey)
                .filter { it.action == "COMPLETION_UNDONE" }
                .forEach { undo ->
                    enqueue(
                        membership.spaceId,
                        "completion-undo:${undo.eventId}",
                        buildCommand("EXECUTION_EVENT", uuidV7(), buildJsonObject {
                            put("assignmentId", occurrenceAssignmentId); put("occurrenceKey", occurrenceKey)
                            put("taskRevision", occurrenceRevision); put("eventType", "COMPLETION_UNDONE")
                            put("occurredAt", Instant.ofEpochMilli(undo.createdAtEpochMillis).toString())
                            put("data", buildCompletionUndoData(instance, undo.createdAtEpochMillis, meta.timeZone))
                        }),
                    )
                }
            if (instance.status in setOf("COMPLETED", "MISSED")) enqueueResult(membership.spaceId, occurrenceAssignmentId, occurrenceKey, occurrenceRevision, instance)
        }
    }

    private suspend fun enqueueResult(spaceId: String, assignmentId: String, occurrenceKey: String, revision: Int, instance: TaskInstanceEntity) {
        val semantic = "result:${instance.taskId}:${instance.occurrenceKey}:${instance.updatedAtEpochMillis}"
        val prepared = application.database.withTransaction {
            val current = application.database.instanceDao().getInstance(instance.taskId, instance.occurrenceKey)
            val mood = if (instance.executionKind == "MOOD" && instance.status == "COMPLETED") {
                application.database.executionDao().getMood(instance.taskId, instance.occurrenceKey)
            } else null
            val steps = if (instance.executionKind == "STEPS" && instance.status == "COMPLETED") {
                application.database.instanceDao().getInstanceSteps(instance.taskId, instance.occurrenceKey)
            } else null
            val information = if (instance.executionKind == "INFORMATION") {
                application.database.executionDao().getSubmission(instance.taskId, instance.occurrenceKey)
            } else null
            prepareResultSnapshot(instance, current, information, mood, steps)
        } ?: return
        prepared.information?.let { submission ->
            enqueue(
                spaceId,
                "information-submission:${instance.taskId}:${instance.occurrenceKey}:${submission.updatedAtEpochMillis}",
                buildCommand(
                    type = "INFORMATION_SUBMISSION",
                    entityId = uuidV7(),
                    payload = buildJsonObject {
                        put("assignmentId", assignmentId)
                        put("occurrenceKey", occurrenceKey)
                        put("taskRevision", revision)
                        put("content", submission.content)
                        put("submittedAt", Instant.ofEpochMilli(requireNotNull(submission.submittedAtEpochMillis)).toString())
                    },
                ),
            )
        }
        enqueue(
            spaceId,
            semantic,
            buildCommand(
                type = "EXECUTION_EVENT",
                entityId = uuidV7(),
                payload = buildJsonObject {
                    put("assignmentId", assignmentId); put("occurrenceKey", occurrenceKey); put("taskRevision", revision)
                    put("eventType", "RESULT_SUBMITTED"); put("occurredAt", Instant.ofEpochMilli(instance.updatedAtEpochMillis).toString())
                    put("data", buildExecutionResultData(prepared.instance, prepared.information?.content, prepared.mood, prepared.steps))
                },
            ),
        )
    }

    private suspend fun enqueue(spaceId: String, semanticKey: String, command: JsonObject) {
        if (dao.hasSemanticKey(semanticKey) > 0) return
        dao.enqueue(CloudOutboxEntity(command.requiredString("commandId"), spaceId, semanticKey, command.toString(), Instant.now().toString()))
    }

    private suspend fun flush(session: CloudSessionEntity, spaceId: String) {
        for (item in dao.outbox(spaceId)) {
            if (item.state == "PERMANENT_FAILURE") {
                // Keep diagnostics, but do not let an old rejected command prevent
                // unrelated new work from being synchronized forever.
                continue
            }
            item.retryAtEpochMillis?.let { deadline ->
                if (deadline > nowMillis()) {
                    val seconds = ((deadline - nowMillis() + 999L) / 1000L).coerceAtLeast(1).toInt()
                    throw CloudApiException(429, "RETRY_DEFERRED", "请在 $seconds 秒后重试", true, seconds)
                }
            }
            val command = json.parseToJsonElement(item.commandJson)
            val result = try {
                val results = api.commands(session.accessToken, spaceId, JsonArray(listOf(command)))["results"]?.jsonArray.orEmpty()
                results.singleOrNull()?.jsonObject?.takeIf { it["commandId"]?.jsonPrimitive?.contentOrNull == item.commandId }
            }
            catch (error: CancellationException) { throw error }
            catch (error: CloudApiException) {
                if (error.retryable) {
                    val retryAt = error.retryAfterSeconds?.let { nowMillis() + it * 1000L }
                    dao.markRetryable(item.commandId, retryAt, error.message ?: error.code)
                    throw error
                }
                if (error.status == 401 || error.status == 428 || error.code.contains("DELETION")) throw error
                dao.markPermanentFailure(item.commandId, error.message ?: error.code)
                throw CloudApiException(error.status, "COMMAND_REJECTED", error.message, false)
            }
            if (result == null) {
                val error = CloudApiException(502, "COMMAND_RECEIPT_INVALID", "云端回执无效", true, 30)
                dao.markRetryable(item.commandId, nowMillis() + 30_000L, error.message)
                throw error
            }
            when (result.requiredString("status")) {
                "accepted", "duplicate" -> {
                    syncDatabase.withTransaction {
                        dao.markSent(CloudSentSemanticEntity(item.semanticKey, spaceId, item.commandId, Instant.now().toString()))
                        dao.deleteCommand(item.commandId)
                    }
                }
                "retryable" -> {
                    val error = CloudApiException(503, "COMMAND_RETRYABLE", result["message"]?.jsonPrimitive?.contentOrNull ?: "命令稍后重试", true, 30)
                    dao.markRetryable(item.commandId, nowMillis() + 30_000L, error.message)
                    throw error
                }
                "conflict", "rejected" -> {
                    val error = CloudApiException(409, "COMMAND_REJECTED", result["message"]?.jsonPrimitive?.contentOrNull ?: "命令被拒绝", false)
                    dao.markPermanentFailure(item.commandId, error.message)
                    throw error
                }
                else -> throw CloudApiException(502, "COMMAND_STATUS_UNKNOWN", "云端回执状态未知", true, 30)
            }
        }
    }

    private suspend fun purge(spaceId: String?) = withContext(NonCancellable) {
        val oldSession = dao.session()
        scheduledSignature = null
        visibleSessionGeneration = null
        if (spaceId != null) {
            dao.clearEntities(spaceId); dao.clearOutbox(spaceId); dao.clearSent(spaceId); dao.clearMeta(spaceId); dao.clearOccurrenceMaps(spaceId)
        }
        dao.clearSession()
        application.database.clearAllTables()
        syncCoordinator.cancel(oldSession)
    }

    private suspend fun purgeBusinessDataPreservingSession(session: CloudSessionEntity) = withContext(NonCancellable) {
        syncDatabase.clearAllTables()
        dao.saveSession(session.copy(accountId = null, membershipId = null, spaceId = null, spaceName = null, role = null,
            sessionGeneration = uuidV7(), retryUntilEpochMillis = 0, needsSync = false, blockedReason = null))
        application.database.clearAllTables()
        scheduledSignature = null
        visibleSessionGeneration = null
        syncCoordinator.cancel(session)
    }

    private fun buildCommand(type: String, entityId: String, payload: JsonObject): JsonObject = buildJsonObject {
        put("commandId", uuidV7()); put("entityId", entityId); put("baseVersion", 0); put("createdAt", Instant.now().toString()); put("type", type); put("payload", payload)
    }

    private fun JsonObject.toEntity(spaceId: String, updatedAtField: String = "updatedAt") = CloudEntity(
        key = "$spaceId:${requiredString("entityType")}:${requiredString("entityId")}",
        spaceId = spaceId,
        entityType = requiredString("entityType"),
        entityId = requiredString("entityId"),
        entityVersion = this["entityVersion"]?.jsonPrimitive?.intOrNull ?: 0,
        payloadVersion = (this["payloadVersion"]?.jsonPrimitive?.intOrNull ?: 1).also { require(it == 1) { "同步数据版本过新，请升级应用" } },
        payloadJson = this["payload"]!!.toString(),
        updatedAt = requiredString(updatedAtField),
    )

    private fun pageHasMore(page: JsonObject) = page["hasMore"]?.jsonPrimitive?.content == "true"

    private fun CloudSessionEntity.withTokens(tokens: CloudTokens) = copy(
        accessToken = tokens.accessToken,
        refreshToken = tokens.refreshToken,
        csrfToken = tokens.csrfToken,
        accessExpiresAt = tokens.accessExpiresAt,
    )
}

internal fun isTerminalSessionFailure(error: Throwable): Boolean =
    error is CloudApiException && error.status == 401 && !error.code.contains("DELETION")

internal fun shouldKeepAssignedCloudTask(taskStatus: String, assignmentStatus: String?): Boolean =
    taskStatus == "ACTIVE" && assignmentStatus == "ACTIVE"

internal fun needsAccessTokenRefresh(
    accessExpiresAt: String,
    now: Instant = Instant.now(),
    refreshSkewSeconds: Long = 60,
): Boolean = catchSyncFailure {
    !Instant.parse(accessExpiresAt).isAfter(now.plusSeconds(refreshSkewSeconds))
}.getOrDefault(true)

internal fun shouldRunAutomaticSync(
    lastSyncedAt: String?,
    now: Instant = Instant.now(),
    minimumIntervalMillis: Long = AUTOMATIC_SYNC_MIN_INTERVAL_MILLIS,
): Boolean = lastSyncedAt
    ?.let { catchSyncFailure { Instant.parse(it) }.getOrNull() }
    ?.plusMillis(minimumIntervalMillis)
    ?.isAfter(now)
    ?.not()
    ?: true

internal fun hasNewNotificationIds(
    previous: List<ConnectedNotification>,
    current: List<ConnectedNotification>,
): Boolean {
    val previousIds = previous.flatMap(ConnectedNotification::notificationIds).toSet()
    return current.any { notification -> notification.notificationIds.any { it !in previousIds } }
}

internal fun prepareResultSnapshot(
    expected: TaskInstanceEntity,
    current: TaskInstanceEntity?,
    information: com.ds.localtaskmanager.data.InformationSubmissionEntity?,
    mood: com.ds.localtaskmanager.data.MoodSubmissionEntity?,
    steps: List<com.ds.localtaskmanager.data.InstanceStepEntity>?,
): ResultSnapshot? {
    if (current != expected) return null
    if (expected.executionKind == "MOOD" && expected.status == "COMPLETED" &&
        (mood?.submittedAtEpochMillis == null || mood.submittedAtEpochMillis != expected.completedAtEpochMillis)
    ) return null
    val finalSteps = if (expected.executionKind == "STEPS" && expected.status == "COMPLETED") {
        steps?.takeIf(::hasFinalStepSnapshot) ?: return null
    } else null
    return ResultSnapshot(expected, information?.takeIf { it.submittedAtEpochMillis != null }, mood, finalSteps)
}

internal fun hasFinalStepSnapshot(steps: List<com.ds.localtaskmanager.data.InstanceStepEntity>): Boolean {
    if (steps.isEmpty() || steps.size > 50) return false
    val ordered = steps.sortedBy { it.position }
    if (ordered.map { it.position } != ordered.indices.toList()) return false
    if (ordered.any { it.stepId.isBlank() || it.stepStatus !in setOf("CONFIRMED", "SKIPPED") }) return false
    if (ordered.any { it.required && it.stepStatus == "SKIPPED" }) return false
    if (ordered.map { it.stepId }.distinct().size != ordered.size) return false
    return true
}

internal fun buildExecutionResultData(
    instance: TaskInstanceEntity,
    informationContent: String?,
    mood: com.ds.localtaskmanager.data.MoodSubmissionEntity? = null,
    steps: List<com.ds.localtaskmanager.data.InstanceStepEntity>? = null,
): JsonObject = buildJsonObject {
    put("status", instance.status)
    put("localOccurrenceKey", instance.occurrenceKey)
    put("taskName", instance.name)
    put("taskDate", instance.taskDate)
    put("executionKind", instance.executionKind)
    if (instance.executionKind == "MOOD" && instance.status == "COMPLETED" && mood?.submittedAtEpochMillis != null) {
        put("moodRating", requireNotNull(mood.rating))
        put("moodText", mood.text)
    }
    if (instance.executionKind == "STEPS" && instance.status == "COMPLETED" && hasFinalStepSnapshot(steps.orEmpty())) {
        put("stepResults", buildJsonArray {
            steps!!.sortedBy { it.position }.forEach { step ->
                add(buildJsonObject {
                    put("stepId", requireNotNull(step.stepId))
                    put("status", step.stepStatus)
                    if (step.stepStatus == "CONFIRMED") {
                        when (step.executionKind) {
                            "COUNTER" -> step.counterValue?.let { put("counterValue", it) }
                            "TIMER" -> step.elapsedMillis?.let { put("elapsedMillis", it) }
                            "INFORMATION" -> step.informationContent?.let { put("informationContent", it) }
                            "MOOD" -> { step.moodRating?.let { put("moodRating", it) }; step.moodText?.let { put("moodText", it) } }
                        }
                    }
                })
            }
        })
    }
    instance.completedAtEpochMillis?.let { put("completedAt", Instant.ofEpochMilli(it).toString()) }
    informationContent?.takeIf { it.isNotBlank() }?.let { put("informationContent", it) }
}

internal fun buildCompletionUndoData(instance: TaskInstanceEntity, occurredAtMillis: Long, timeZone: String): JsonObject {
    val status = TaskStateMachine.statusAt(
        LocalDate.parse(instance.taskDate),
        instance.deadline?.let(LocalDateTime::parse),
        LocalDateTime.ofInstant(Instant.ofEpochMilli(occurredAtMillis), ZoneId.of(timeZone)),
    )
    return buildExecutionResultData(instance.copy(status = status.name, completedAtEpochMillis = null), null)
}

private const val NOTIFICATION_POLL_INTERVAL_MILLIS = 15_000L
private const val AUTOMATIC_SYNC_MIN_INTERVAL_MILLIS = 15_000L
private const val PROTECTED_POLL_RECHECK_MILLIS = 60_000L

private fun deterministicDstId(value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).copyOf(12))

internal fun uuidV7(now: Long = System.currentTimeMillis()): String {
    val random = UUID.randomUUID()
    val high = ((now and 0xffffffffffffL) shl 16) or 0x7000L or (random.mostSignificantBits and 0x0fffL)
    val low = (random.leastSignificantBits and 0x3fffffffffffffffL) or Long.MIN_VALUE
    return UUID(high, low).toString()
}

private inline fun <T> catchSyncFailure(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    Result.failure(error)
}
