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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
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

class ConnectedRuntime(
    private val application: DstApplication,
    private val api: CloudApi = CloudApi(),
) {
    private val syncDatabase = ConnectedSyncDatabase.create(application)
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

    init {
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
            else runCatching {
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
            runCatching { api.requestChallenge(email.trim()) }
                .onSuccess { challengeId = it; mutableState.value = ConnectedState.CodeSent(email.trim()) }
                .onFailure { mutableState.value = ConnectedState.Failure(it.message ?: "验证码发送失败", true) }
        }
    }

    fun verifyCode(email: String, code: String) {
        val id = challengeId ?: return
        scope.launchSessionOperation {
            mutableState.value = ConnectedState.Restoring
            runCatching {
                val tokens = api.verify(id, email, code)
                val session = CloudSessionEntity(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    csrfToken = tokens.csrfToken,
                    accessExpiresAt = tokens.accessExpiresAt,
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
            runCatching {
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
            runCatching { withSessionRecovery(session) { bootstrap(it, showInvitations = false) } }
                .onFailure { mutableState.value = ConnectedState.Failure(it.message ?: "空间载入失败", true) }
        }
    }

    fun retryEntry() {
        scope.launchSessionOperation {
            mutableState.value = ConnectedState.Restoring
            val session = dao.session() ?: run { mutableState.value = ConnectedState.SignedOut; return@launchSessionOperation }
            runCatching { withSessionRecovery(session) { bootstrap(it) } }
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
        val session = dao.session() ?: run {
            mutableState.value = ConnectedState.SignedOut
            return false
        }
        return runCatching {
            withSessionRecovery(session) { synchronize(it, keepSyncing = true) }
        }.fold(
            onSuccess = { true },
            onFailure = { error ->
                if (isTerminalSessionFailure(error)) {
                    purge(session.spaceId)
                    mutableState.value = ConnectedState.SignedOut
                } else {
                    val cached = cachedReady(session)
                    mutableState.value = if (error is CloudApiException && error.retryable && cached != null) cached
                    else ConnectedState.Failure(error.message ?: "同步失败", true)
                }
                false
            },
        )
    }

    fun logout() {
        scope.launchSessionOperation {
            val session = dao.session()
            runCatching { if (session != null) api.logout(session.accessToken) }
            purge(session?.spaceId)
            mutableState.value = ConnectedState.SignedOut
        }
    }

    fun acknowledgePrivacy(version: Int) {
        scope.launchSessionOperation {
            val session = dao.session() ?: run { mutableState.value = ConnectedState.SignedOut; return@launchSessionOperation }
            runCatching {
                api.acknowledgePrivacy(session.accessToken, version)
                bootstrap(session)
            }.onFailure { mutableState.value = ConnectedState.Failure(it.message ?: "隐私确认失败", true) }
        }
    }

    fun cancelDeletion() {
        scope.launchSessionOperation {
            val session = dao.session() ?: run { mutableState.value = ConnectedState.SignedOut; return@launchSessionOperation }
            runCatching {
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
            runCatching { api.requestChallenge(email.trim(), "SENSITIVE_ACTION") }
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
            runCatching {
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
        val readIds = ids.toSet()
        (mutableState.value as? ConnectedState.Ready)?.let { current ->
            mutableState.value = current.copy(
                notifications = current.notifications.map { item ->
                    item.copy(unread = item.unread && item.notificationIds.any { it !in readIds })
                },
            )
        }
        scope.launch {
            val session = dao.session() ?: return@launch
            val spaceId = session.spaceId ?: return@launch
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
            synchronize()
        }
    }

    private suspend fun refreshNotifications() {
        var shouldSynchronize = false
        sessionOperationMutex.withLock {
            val current = mutableState.value as? ConnectedState.Ready ?: return
            if (current.syncing) return
            val session = dao.session() ?: return
            runCatching {
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
        val membership = memberships.firstOrNull()
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
            serviceMode = bootstrap.serviceMode
            autoSyncIntervalMillis = bootstrap.autoSyncIntervalSeconds?.times(1_000L)
            bootstrap.memberships.firstOrNull { it.id == session.membershipId }
        }
        if (membership == null || membership.role != "EXECUTOR") {
            purge(session.spaceId)
            mutableState.value = ConnectedState.Failure("空间成员资格已失效，本地空间缓存已清除", false)
            return
        }
        flush(session, membership.spaceId)
        pullRemote(session, membership)
        val entities = dao.entities(membership.spaceId)
        val isolatedTaskCount = materializeTasks(entities, membership.id)
        application.instanceGenerationService.reconcileAll(LocalDate.now(ZoneId.of(membership.timeZone)))
        enqueueLocalWork(session, membership, entities)
        flush(session, membership.spaceId)
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
                    dao.saveMeta(CloudSyncMetaEntity(membership.spaceId, cursor, Instant.now().toString(), membership.timeZone, membership.timeZoneVersion))
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
            dao.saveMeta(CloudSyncMetaEntity(membership.spaceId, changesCursor, Instant.now().toString(), membership.timeZone, membership.timeZoneVersion))
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
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
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
        if (!needsAccessTokenRefresh(session.accessExpiresAt)) return session
        val refreshed = api.refresh(session.refreshToken)
        return session.withTokens(refreshed).also { dao.saveSession(it) }
    }

    private suspend fun <T> withSessionRecovery(
        session: CloudSessionEntity,
        operation: suspend (CloudSessionEntity) -> T,
    ): T {
        val current = ensureFreshSession(session)
        return try {
            operation(current)
        } catch (error: Throwable) {
            if (!isTerminalSessionFailure(error)) throw error
            val refreshed = api.refresh(current.refreshToken)
            val saved = current.withTokens(refreshed)
            dao.saveSession(saved)
            operation(saved)
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
        val informationContent = if (instance.executionKind == "INFORMATION") {
            application.database.executionDao().getSubmission(instance.taskId, instance.occurrenceKey)
                ?.takeIf { it.submittedAtEpochMillis != null }
        } else null
        informationContent?.let { submission ->
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
                    put("data", buildExecutionResultData(instance, informationContent?.content))
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
            val command = json.parseToJsonElement(item.commandJson)
            val result = try { api.commands(session.accessToken, spaceId, JsonArray(listOf(command)))["results"]!!.jsonArray.first().jsonObject }
            catch (error: CloudApiException) { if (error.retryable) return else throw error }
            when (result.requiredString("status")) {
                "accepted", "duplicate" -> {
                    dao.markSent(CloudSentSemanticEntity(item.semanticKey, spaceId, item.commandId, Instant.now().toString()))
                    dao.deleteCommand(item.commandId)
                }
                "retryable" -> { dao.incrementAttempts(item.commandId); return }
                else -> dao.deleteCommand(item.commandId)
            }
        }
    }

    private suspend fun purge(spaceId: String?) {
        if (spaceId != null) {
            dao.clearEntities(spaceId); dao.clearOutbox(spaceId); dao.clearSent(spaceId); dao.clearMeta(spaceId); dao.clearOccurrenceMaps(spaceId)
        }
        dao.clearSession()
        application.database.clearAllTables()
    }

    private suspend fun purgeBusinessDataPreservingSession(session: CloudSessionEntity) {
        syncDatabase.clearAllTables()
        dao.saveSession(session.copy(accountId = null, membershipId = null, spaceId = null, spaceName = null, role = null))
        application.database.clearAllTables()
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
    error is CloudApiException && error.status == 401

internal fun shouldKeepAssignedCloudTask(taskStatus: String, assignmentStatus: String?): Boolean =
    taskStatus == "ACTIVE" && assignmentStatus == "ACTIVE"

internal fun needsAccessTokenRefresh(
    accessExpiresAt: String,
    now: Instant = Instant.now(),
    refreshSkewSeconds: Long = 60,
): Boolean = runCatching {
    !Instant.parse(accessExpiresAt).isAfter(now.plusSeconds(refreshSkewSeconds))
}.getOrDefault(true)

internal fun shouldRunAutomaticSync(
    lastSyncedAt: String?,
    now: Instant = Instant.now(),
    minimumIntervalMillis: Long = AUTOMATIC_SYNC_MIN_INTERVAL_MILLIS,
): Boolean = lastSyncedAt
    ?.let { runCatching { Instant.parse(it) }.getOrNull() }
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

internal fun buildExecutionResultData(
    instance: TaskInstanceEntity,
    informationContent: String?,
): JsonObject = buildJsonObject {
    put("status", instance.status)
    put("localOccurrenceKey", instance.occurrenceKey)
    put("taskName", instance.name)
    put("taskDate", instance.taskDate)
    put("executionKind", instance.executionKind)
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
