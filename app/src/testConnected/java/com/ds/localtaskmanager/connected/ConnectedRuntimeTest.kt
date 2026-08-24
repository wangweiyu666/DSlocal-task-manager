package com.ds.localtaskmanager.connected

import com.ds.localtaskmanager.data.TaskInstanceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ConnectedRuntimeTest {
    @Test
    fun unauthorizedSessionFailureRequiresLocalPurge() {
        assertTrue(isTerminalSessionFailure(CloudApiException(401, "SESSION_EXPIRED", "expired", false)))
        assertTrue(isTerminalSessionFailure(CloudApiException(401, "SESSION_REPLAYED", "replayed", false)))
    }

    @Test
    fun retryableTransportFailureKeepsOfflineCache() {
        assertFalse(isTerminalSessionFailure(CloudApiException(503, "UNAVAILABLE", "retry", true)))
        assertFalse(isTerminalSessionFailure(IllegalStateException("local failure")))
    }

    @Test
    fun validAccessTokenDoesNotRotateDuringManualSync() {
        val now = Instant.parse("2026-08-23T00:00:00Z")
        assertFalse(needsAccessTokenRefresh("2026-08-23T00:10:00Z", now))
    }

    @Test
    fun nearlyExpiredOrMalformedAccessTokenIsRefreshed() {
        val now = Instant.parse("2026-08-23T00:00:00Z")
        assertTrue(needsAccessTokenRefresh("2026-08-23T00:00:30Z", now))
        assertTrue(needsAccessTokenRefresh("not-an-instant", now))
    }

    @Test
    fun onlyActiveAssignmentsMaterializeCloudTasks() {
        assertTrue(shouldKeepAssignedCloudTask("ACTIVE", "ACTIVE"))
        assertFalse(shouldKeepAssignedCloudTask("ACTIVE", "CANCELLED"))
        assertFalse(shouldKeepAssignedCloudTask("ACTIVE", null))
        assertFalse(shouldKeepAssignedCloudTask("ARCHIVED", "ACTIVE"))
    }

    @Test
    fun informationResultCarriesOnlyTheSubmittedUserContent() {
        val instance = TaskInstanceEntity(
            taskId = "CloudTask0000001",
            occurrenceKey = "once",
            name = "每日体温记录",
            description = "请填写测量结果",
            taskDate = "2026-08-23",
            deadline = null,
            groupId = null,
            required = true,
            points = 2,
            sortOrder = null,
            completionMessage = "已记录",
            status = "COMPLETED",
            completedAtEpochMillis = 1_777_000_000_000,
            createdAtEpochMillis = 1_776_000_000_000,
            updatedAtEpochMillis = 1_777_000_000_000,
            executionKind = "INFORMATION",
        )

        val data = buildExecutionResultData(instance, "体温 36.6℃\n无不适")

        assertEquals("每日体温记录", data["taskName"]?.toString()?.trim('"'))
        assertEquals("2026-08-23", data["taskDate"]?.toString()?.trim('"'))
        assertEquals("体温 36.6℃\\n无不适", data["informationContent"]?.toString()?.trim('"'))
        assertFalse(data.containsKey("description"))
    }
}
