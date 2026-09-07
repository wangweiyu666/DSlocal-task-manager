package com.ds.localtaskmanager.connected

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import com.ds.localtaskmanager.DstApplication
import com.ds.localtaskmanager.data.TaskDefinitionEntity
import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.data.InformationSubmissionEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConnectedSyncEngineTest {
    private lateinit var application: DstApplication
    private lateinit var syncDatabase: ConnectedSyncDatabase

    @Before
    fun setUp() = runBlocking {
        application = ApplicationProvider.getApplicationContext<Context>() as DstApplication
        application.database.clearAllTables()
        application.database.openHelper.writableDatabase
        syncDatabase = Room.inMemoryDatabaseBuilder(application, ConnectedSyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        try {
            syncDatabase.close()
        } finally {
            application.database.close()
        }
    }

    @Test
    fun `startup recovery queues completed result and occurrence once without draft`() = runBlocking {
        seedCompletedTask()
        val scheduled = mutableListOf<CloudSessionEntity>()
        val engine = engine { scheduled += it }

        assertTrue(engine.recoverPendingLocalWork())
        val first = syncDatabase.dao().outbox(SPACE_ID)
        assertEquals(3, first.size)
        assertEquals(2, first.count { it.semanticKey.startsWith("occurrence:") })
        assertEquals(1, first.count { it.semanticKey.startsWith("result:") })
        assertTrue(first.all { "private draft answer" !in it.commandJson })
        assertTrue(first.all { "information-submission" !in it.commandJson })
        val firstIds = first.associate { it.semanticKey to it.commandId }

        assertTrue(engine.recoverPendingLocalWork())
        val second = syncDatabase.dao().outbox(SPACE_ID)
        assertEquals(3, second.size)
        assertEquals(firstIds, second.associate { it.semanticKey to it.commandId })
        assertEquals(1, scheduled.size)
    }

    @Test
    fun `background identity mismatch does no network and leaves newer session untouched`() = runBlocking {
        val session = session(generation = "new-generation", accountId = "new-account", membershipId = "new-member")
        syncDatabase.dao().saveSession(session)
        val requests = AtomicInteger()
        val api = CloudApi("https://example.invalid") { _: URL ->
            requests.incrementAndGet()
            unexpectedConnection()
        }
        val engine = ConnectedSyncEngine(
            application = application,
            api = api,
            syncDatabase = syncDatabase,
            enqueueWork = null,
            nowMillis = { NOW },
        )

        val mismatches = listOf(
            ConnectedSyncCoordinator.KEY_ACCOUNT_ID to "old-account",
            ConnectedSyncCoordinator.KEY_MEMBERSHIP_ID to "old-member",
            ConnectedSyncCoordinator.KEY_SPACE_ID to "old-space",
            ConnectedSyncCoordinator.KEY_GENERATION to "old-generation",
        )
        mismatches.forEach { (field, value) ->
            val input = Data.Builder()
                .putString(ConnectedSyncCoordinator.KEY_ACCOUNT_ID, session.accountId)
                .putString(ConnectedSyncCoordinator.KEY_MEMBERSHIP_ID, session.membershipId)
                .putString(ConnectedSyncCoordinator.KEY_SPACE_ID, session.spaceId)
                .putString(ConnectedSyncCoordinator.KEY_GENERATION, session.sessionGeneration)
                .apply { putString(field, value) }
                .build()
            assertEquals(SyncAttemptResult.INVALID_IDENTITY, engine.runBackground(input))
        }
        assertEquals(0, requests.get())
        assertEquals(session, syncDatabase.dao().session())
    }

    @Test
    fun `background run with no pending work completes without network`() = runBlocking {
        val session = session(needsSync = false)
        syncDatabase.dao().saveSession(session)
        val requests = AtomicInteger()
        val engine = ConnectedSyncEngine(
            application = application,
            api = CloudApi("https://example.invalid") { _: URL ->
                requests.incrementAndGet()
                unexpectedConnection()
            },
            syncDatabase = syncDatabase,
            enqueueWork = null,
            nowMillis = { NOW },
        )

        assertEquals(SyncAttemptResult.COMPLETE, engine.runBackground(validInput(session)))
        assertEquals(0, requests.get())
    }

    @Test
    fun `retry deadline survives engine recreation without network`() = runBlocking {
        val deadline = NOW + 3_600_000L
        val session = session(retryUntilEpochMillis = deadline, needsSync = true)
        syncDatabase.dao().saveSession(session)
        val requests = AtomicInteger()
        fun newEngine() = ConnectedSyncEngine(
            application = application,
            api = CloudApi("https://example.invalid") { _: URL ->
                requests.incrementAndGet()
                unexpectedConnection()
            },
            syncDatabase = syncDatabase,
            enqueueWork = null,
            nowMillis = { NOW },
        )

        assertEquals(SyncAttemptResult.RETRY, newEngine().runBackground(validInput(session)))
        assertEquals(SyncAttemptResult.RETRY, newEngine().runBackground(validInput(session)))
        assertEquals(deadline, syncDatabase.dao().session()?.retryUntilEpochMillis)
        assertEquals(0, requests.get())
    }

    @Test
    fun `lost command response retries identical command and duplicate closes outbox`() = runBlocking {
        val session = session()
        syncDatabase.dao().saveSession(session)
        syncDatabase.dao().saveMeta(CloudSyncMetaEntity(SPACE_ID, "cursor-1", "old-last-sync", "Asia/Hong_Kong", 1))
        syncDatabase.dao().enqueue(
            CloudOutboxEntity(
                commandId = "command-lost-1",
                spaceId = SPACE_ID,
                semanticKey = "notification-read:n-1",
                commandJson = "{\"commandId\":\"command-lost-1\",\"type\":\"NOTIFICATION_READ\",\"payload\":{\"notificationIds\":[\"n-1\"]}}",
                queuedAt = "2026-09-06T00:00:00Z",
            ),
        )
        var now = NOW
        val commandBodies = mutableListOf<String>()
        var commandCalls = 0
        fun api() = CloudApi("https://example.invalid") { url ->
            val path = url.path + url.query?.let { "?$it" }.orEmpty()
            val response = when {
                path == "/v1/account" -> "{\"account\":{\"status\":\"ACTIVE\",\"privacyNoticeVersion\":1,\"requiredPrivacyNoticeVersion\":1}}"
                path == "/v1/bootstrap" -> "{\"account\":{\"id\":\"$ACCOUNT_ID\"},\"memberships\":[{\"id\":\"$MEMBERSHIP_ID\",\"role\":\"EXECUTOR\",\"space\":{\"id\":\"$SPACE_ID\",\"name\":\"测试空间\",\"timeZone\":\"Asia/Hong_Kong\",\"timeZoneVersion\":1}}],\"service\":{\"mode\":\"NORMAL\"}}"
                path.startsWith("/v1/spaces/$SPACE_ID/changes") -> "{\"changes\":[],\"nextCursor\":\"cursor-2\",\"hasMore\":false}"
                path == "/v1/spaces/$SPACE_ID/commands" -> {
                    commandCalls += 1
                    if (commandCalls == 1) null else "{\"results\":[{\"commandId\":\"command-lost-1\",\"status\":\"duplicate\"}]}"
                }
                else -> error("unexpected route $path")
            }
            val body = ByteArrayOutputStream()
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun usingProxy() = false
                override fun disconnect() = Unit
                override fun getOutputStream() = body
                override fun getResponseCode(): Int {
                    if (path == "/v1/spaces/$SPACE_ID/commands") {
                        commandBodies += body.toString(Charsets.UTF_8.name())
                        if (commandCalls == 1) throw IOException("response lost")
                    }
                    return 200
                }
                override fun getInputStream() = ByteArrayInputStream(requireNotNull(response).toByteArray())
            }
        }

        val first = ConnectedSyncEngine(application, api(), syncDatabase, enqueueWork = {}, nowMillis = { now })
        assertEquals(SyncAttemptResult.RETRY, first.synchronizeNow())
        assertEquals("old-last-sync", syncDatabase.dao().meta(SPACE_ID)?.lastSyncedAt)
        assertEquals(true, syncDatabase.dao().session()?.needsSync)
        assertTrue((syncDatabase.dao().session()?.retryUntilEpochMillis ?: 0) > NOW)
        assertEquals(1, syncDatabase.dao().outbox(SPACE_ID).size)
        val firstCommandJson = commandBodies.single()
        assertTrue(firstCommandJson.contains("command-lost-1"))
        assertTrue(firstCommandJson.contains("NOTIFICATION_READ"))

        now = NOW + 31_000L
        val second = ConnectedSyncEngine(application, api(), syncDatabase, enqueueWork = {}, nowMillis = { now })
        assertEquals(SyncAttemptResult.COMPLETE, second.runBackground(validInput(session)))
        assertEquals(listOf(firstCommandJson, firstCommandJson), commandBodies)
        assertEquals(0, syncDatabase.dao().outbox(SPACE_ID).size)
        assertEquals(1, syncDatabase.dao().hasSemanticKey("notification-read:n-1"))
        assertEquals("command-lost-1", queryString(syncDatabase, "SELECT commandId FROM cloud_sent_semantic WHERE semanticKey = 'notification-read:n-1'"))
    }

    @Test
    fun `foreground and background synchronization share one in-flight request`() = runBlocking {
        val session = session(needsSync = true)
        syncDatabase.dao().saveSession(session)
        syncDatabase.dao().saveMeta(CloudSyncMetaEntity(SPACE_ID, "cursor-1", "old-last-sync", "Asia/Hong_Kong", 1))
        syncDatabase.dao().enqueue(
            CloudOutboxEntity("command-concurrent-1", SPACE_ID, "notification-read:n-2", "{\"commandId\":\"command-concurrent-1\",\"type\":\"NOTIFICATION_READ\"}", "2026-09-06T00:00:00Z"),
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val commands = AtomicInteger()
        val api = CloudApi("https://example.invalid") { url ->
            val path = url.path + url.query?.let { "?$it" }.orEmpty()
            val response = when {
                path == "/v1/account" -> "{\"account\":{\"status\":\"ACTIVE\",\"privacyNoticeVersion\":1,\"requiredPrivacyNoticeVersion\":1}}"
                path == "/v1/bootstrap" -> "{\"account\":{\"id\":\"$ACCOUNT_ID\"},\"memberships\":[{\"id\":\"$MEMBERSHIP_ID\",\"role\":\"EXECUTOR\",\"space\":{\"id\":\"$SPACE_ID\",\"name\":\"测试空间\",\"timeZone\":\"Asia/Hong_Kong\",\"timeZoneVersion\":1}}]}"
                path.startsWith("/v1/spaces/$SPACE_ID/changes") -> "{\"changes\":[],\"nextCursor\":\"cursor-2\",\"hasMore\":false}"
                path == "/v1/spaces/$SPACE_ID/commands" -> { commands.incrementAndGet(); "{\"results\":[{\"commandId\":\"command-concurrent-1\",\"status\":\"accepted\"}]}" }
                else -> error("unexpected route $path")
            }
            val body = ByteArrayOutputStream()
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun usingProxy() = false
                override fun disconnect() = Unit
                override fun getOutputStream() = body
                override fun getResponseCode(): Int {
                    val count = active.incrementAndGet()
                    maxActive.getAndUpdate { old -> maxOf(old, count) }
                    try {
                        if (path == "/v1/account") {
                            entered.countDown()
                            release.await(5, TimeUnit.SECONDS)
                        }
                        return 200
                    } finally {
                        active.decrementAndGet()
                    }
                }
                override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
            }
        }
        val engine = ConnectedSyncEngine(application, api, syncDatabase, enqueueWork = {}, nowMillis = { NOW })
        val foreground = async(Dispatchers.IO) { engine.synchronizeNow() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val background = async(Dispatchers.IO) { engine.runBackground(validInput(session)) }
        release.countDown()

        assertEquals(SyncAttemptResult.COMPLETE, foreground.await())
        assertEquals(SyncAttemptResult.COMPLETE, background.await())
        assertEquals(1, maxActive.get())
        assertEquals(1, commands.get())
        assertEquals(0, syncDatabase.dao().outbox(SPACE_ID).size)
    }

    @Test
    fun `rejected command is permanently retained and is not retried as a command`() = runBlocking {
        val session = session(needsSync = true)
        syncDatabase.dao().saveSession(session)
        syncDatabase.dao().saveMeta(CloudSyncMetaEntity(SPACE_ID, "cursor-1", "old", "Asia/Hong_Kong", 1))
        syncDatabase.dao().enqueue(CloudOutboxEntity("reject-1", SPACE_ID, "reject-key", "{\"commandId\":\"reject-1\",\"type\":\"NOTIFICATION_READ\"}", "2026-09-06T00:00:00Z"))
        val requests = AtomicInteger()
        val engine = ConnectedSyncEngine(application, scriptedApi(requests, commandStatus = "rejected"), syncDatabase, enqueueWork = {}, nowMillis = { NOW })

        assertEquals(SyncAttemptResult.USER_ACTION, engine.runBackground(validInput(session)))
        val retained = syncDatabase.dao().outbox(SPACE_ID).single()
        assertEquals("reject-1", retained.commandId)
        assertEquals("{\"commandId\":\"reject-1\",\"type\":\"NOTIFICATION_READ\"}", retained.commandJson)
        assertEquals("PERMANENT_FAILURE", retained.state)
        assertTrue(requests.get() > 0)
    }

    @Test
    fun `account retry-after persists deadline across engine recreation`() = runBlocking {
        val session = session(needsSync = true)
        syncDatabase.dao().saveSession(session)
        syncDatabase.dao().saveMeta(CloudSyncMetaEntity(SPACE_ID, "cursor-1", "old", "Asia/Hong_Kong", 1))
        var requests = 0
        fun api() = CloudApi("https://example.invalid") { url ->
            requests++
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun usingProxy() = false
                override fun disconnect() = Unit
                override fun getResponseCode() = 503
                override fun getHeaderField(name: String?) = if (name == "Retry-After") "3600" else null
                override fun getErrorStream() = ByteArrayInputStream("{\"error\":{\"code\":\"TEMPORARY\",\"retryable\":true}}".toByteArray())
            }
        }
        val first = ConnectedSyncEngine(application, api(), syncDatabase, enqueueWork = {}, nowMillis = { NOW })
        assertEquals(SyncAttemptResult.RETRY, first.runBackground(validInput(session)))
        assertTrue((syncDatabase.dao().session()?.retryUntilEpochMillis ?: 0) >= NOW + 3_600_000L)
        val before = requests
        val second = ConnectedSyncEngine(application, api(), syncDatabase, enqueueWork = {}, nowMillis = { NOW + 1_000L })
        assertEquals(SyncAttemptResult.RETRY, second.synchronizeNow())
        assertEquals(before, requests)
    }

    @Test
    fun `cancellation during account response propagates and preserves pending command`() = runBlocking {
        val session = session(needsSync = true)
        syncDatabase.dao().saveSession(session)
        syncDatabase.dao().saveMeta(CloudSyncMetaEntity(SPACE_ID, "cursor-1", "old", "Asia/Hong_Kong", 1))
        syncDatabase.dao().enqueue(CloudOutboxEntity("cancel-1", SPACE_ID, "cancel-key", "{\"commandId\":\"cancel-1\"}", "2026-09-06T00:00:00Z"))
        val engine = ConnectedSyncEngine(application, CloudApi("https://example.invalid") { url ->
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun usingProxy() = false
                override fun disconnect() = Unit
                override fun getResponseCode(): Int = throw CancellationException("cancelled")
            }
        }, syncDatabase, enqueueWork = {}, nowMillis = { NOW })

        try {
            engine.runBackground(validInput(session))
            error("Expected cancellation")
        } catch (error: CancellationException) {
            assertEquals("cancelled", error.message)
        }
        assertEquals("cancel-1", syncDatabase.dao().outbox(SPACE_ID).single().commandId)
        assertEquals("{\"commandId\":\"cancel-1\"}", syncDatabase.dao().outbox(SPACE_ID).single().commandJson)
        assertEquals("PENDING", syncDatabase.dao().outbox(SPACE_ID).single().state)
        assertEquals(true, syncDatabase.dao().session()?.needsSync)
    }

    private fun engine(enqueue: ((CloudSessionEntity) -> Unit)?): ConnectedSyncEngine = ConnectedSyncEngine(
        application = application,
        api = CloudApi("https://example.invalid") { _: URL -> unexpectedConnection() },
        syncDatabase = syncDatabase,
        enqueueWork = enqueue,
        nowMillis = { NOW },
    )

    private fun validInput(session: CloudSessionEntity) = Data.Builder()
        .putString(ConnectedSyncCoordinator.KEY_ACCOUNT_ID, session.accountId)
        .putString(ConnectedSyncCoordinator.KEY_MEMBERSHIP_ID, session.membershipId)
        .putString(ConnectedSyncCoordinator.KEY_SPACE_ID, session.spaceId)
        .putString(ConnectedSyncCoordinator.KEY_GENERATION, session.sessionGeneration)
        .build()

    private suspend fun seedCompletedTask() {
        application.database.definitionDao().upsertDefinitions(
            listOf(
                TaskDefinitionEntity(
                    taskId = TASK_ID,
                    name = "已完成任务",
                    description = "",
                    groupId = null,
                    required = true,
                    taskDate = DATE,
                    deadline = null,
                    points = 3,
                    sortOrder = null,
                    completionMessage = "完成",
                    stepsFingerprint = "",
                    cancelled = false,
                    createdAtEpochMillis = NOW,
                    updatedAtEpochMillis = NOW,
                ),
                TaskDefinitionEntity(
                    taskId = INFO_TASK_ID, name = "填写私密答案", description = "", groupId = null,
                    required = true, taskDate = DATE, deadline = null, points = 1, sortOrder = null,
                    completionMessage = "完成", stepsFingerprint = "", cancelled = false,
                    createdAtEpochMillis = NOW, updatedAtEpochMillis = NOW, executionKind = "INFORMATION",
                ),
            ),
        )
        application.database.instanceDao().upsertInstances(
            listOf(
                TaskInstanceEntity(
                    taskId = TASK_ID,
                    occurrenceKey = "once",
                    name = "已完成任务",
                    description = "",
                    taskDate = DATE,
                    deadline = null,
                    groupId = null,
                    required = true,
                    points = 3,
                    sortOrder = null,
                    completionMessage = "完成",
                    status = "COMPLETED",
                    completedAtEpochMillis = NOW,
                    createdAtEpochMillis = NOW,
                    updatedAtEpochMillis = NOW,
                ),
                TaskInstanceEntity(
                    taskId = INFO_TASK_ID,
                    occurrenceKey = "once",
                    name = "填写私密答案",
                    description = "",
                    taskDate = DATE,
                    deadline = null,
                    groupId = null,
                    required = true,
                    points = 1,
                    sortOrder = null,
                    completionMessage = "完成",
                    status = "PENDING",
                    completedAtEpochMillis = null,
                    createdAtEpochMillis = NOW,
                    updatedAtEpochMillis = NOW,
                    executionKind = "INFORMATION",
                ),
            ),
        )
        application.database.executionDao().upsertSubmission(
            InformationSubmissionEntity(INFO_TASK_ID, "once", "private draft answer", NOW, NOW, null),
        )
        syncDatabase.dao().saveSession(session())
        syncDatabase.dao().saveMeta(CloudSyncMetaEntity(SPACE_ID, "cursor-1", null, "Asia/Hong_Kong", 1))
        syncDatabase.dao().putEntities(
            listOf(
                CloudEntity(
                    key = "task:$TASK_ID",
                    spaceId = SPACE_ID,
                    entityType = "task",
                    entityId = TASK_ID,
                    entityVersion = 4,
                    payloadVersion = 1,
                    payloadJson = "{\"taskId\":\"$TASK_ID\",\"status\":\"ACTIVE\",\"content\":{\"v\":1}}",
                    updatedAt = "2026-09-06T00:00:00Z",
                ),
                CloudEntity(
                    key = "task:$INFO_TASK_ID", spaceId = SPACE_ID, entityType = "task", entityId = INFO_TASK_ID,
                    entityVersion = 2, payloadVersion = 1,
                    payloadJson = "{\"taskId\":\"$INFO_TASK_ID\",\"status\":\"ACTIVE\",\"content\":{\"v\":1}}",
                    updatedAt = "2026-09-06T00:00:00Z",
                ),
                CloudEntity(
                    key = "assignment:$TASK_ID",
                    spaceId = SPACE_ID,
                    entityType = "assignment",
                    entityId = "assignment-1",
                    entityVersion = 1,
                    payloadVersion = 1,
                    payloadJson = "{\"id\":\"assignment-1\",\"taskId\":\"$TASK_ID\",\"executorMembershipId\":\"$MEMBERSHIP_ID\",\"status\":\"ACTIVE\"}",
                    updatedAt = "2026-09-06T00:00:00Z",
                ),
                CloudEntity(
                    key = "assignment:$INFO_TASK_ID", spaceId = SPACE_ID, entityType = "assignment", entityId = "assignment-2",
                    entityVersion = 1, payloadVersion = 1,
                    payloadJson = "{\"id\":\"assignment-2\",\"taskId\":\"$INFO_TASK_ID\",\"executorMembershipId\":\"$MEMBERSHIP_ID\",\"status\":\"ACTIVE\"}",
                    updatedAt = "2026-09-06T00:00:00Z",
                ),
            ),
        )
    }

    private fun session(
        generation: String = GENERATION,
        accountId: String = ACCOUNT_ID,
        membershipId: String = MEMBERSHIP_ID,
        retryUntilEpochMillis: Long = 0,
        needsSync: Boolean = false,
    ) = CloudSessionEntity(
        accessToken = "access",
        refreshToken = "refresh",
        csrfToken = "csrf",
        accessExpiresAt = "2099-01-01T00:00:00Z",
        accountId = accountId,
        membershipId = membershipId,
        spaceId = SPACE_ID,
        spaceName = "测试空间",
        role = "EXECUTOR",
        sessionGeneration = generation,
        retryUntilEpochMillis = retryUntilEpochMillis,
        needsSync = needsSync,
    )

    private fun unexpectedConnection(): HttpURLConnection = error("unexpected network request")

    private fun queryString(database: ConnectedSyncDatabase, sql: String): String =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun scriptedApi(requests: AtomicInteger, commandStatus: String): CloudApi = CloudApi("https://example.invalid") { url ->
        requests.incrementAndGet()
        val path = url.path + url.query?.let { "?$it" }.orEmpty()
        val response = when {
            path == "/v1/account" -> "{\"account\":{\"status\":\"ACTIVE\",\"privacyNoticeVersion\":1,\"requiredPrivacyNoticeVersion\":1}}"
            path == "/v1/bootstrap" -> "{\"account\":{\"id\":\"$ACCOUNT_ID\"},\"memberships\":[{\"id\":\"$MEMBERSHIP_ID\",\"role\":\"EXECUTOR\",\"space\":{\"id\":\"$SPACE_ID\",\"name\":\"测试空间\",\"timeZone\":\"Asia/Hong_Kong\",\"timeZoneVersion\":1}}]}"
            path.startsWith("/v1/spaces/$SPACE_ID/changes") -> "{\"changes\":[],\"nextCursor\":\"cursor-2\",\"hasMore\":false}"
            path == "/v1/spaces/$SPACE_ID/commands" -> "{\"results\":[{\"commandId\":\"reject-1\",\"status\":\"$commandStatus\"}]}"
            else -> error("unexpected route $path")
        }
        object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getResponseCode() = 200
            override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
        }
    }

    private companion object {
        const val ACCOUNT_ID = "account-1"
        const val MEMBERSHIP_ID = "member-1"
        const val GENERATION = "generation-1"
        const val SPACE_ID = "space-1"
        const val TASK_ID = "EngineTask000001"
        const val INFO_TASK_ID = "EngineInfo000001"
        const val DATE = "2026-09-06"
        const val NOW = 1_777_000_000_000L
    }
}
