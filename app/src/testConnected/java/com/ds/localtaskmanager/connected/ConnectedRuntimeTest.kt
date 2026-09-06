package com.ds.localtaskmanager.connected

import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.data.MoodSubmissionEntity
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
    fun automaticSyncRunsOnlyWhenTheCachedDataIsStale() {
        val now = Instant.parse("2026-08-23T00:00:15Z")

        assertFalse(shouldRunAutomaticSync("2026-08-23T00:00:01Z", now))
        assertTrue(shouldRunAutomaticSync("2026-08-23T00:00:00Z", now))
        assertTrue(shouldRunAutomaticSync(null, now))
        assertTrue(shouldRunAutomaticSync("not-an-instant", now))
    }

    @Test
    fun aPreviouslyUnseenNotificationRequestsAFullSync() {
        val previous = listOf(notification("group-1", "notification-1"))

        assertFalse(hasNewNotificationIds(previous, previous))
        assertTrue(hasNewNotificationIds(previous, previous + notification("group-2", "notification-2")))
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

        val undone = buildCompletionUndoData(instance, Instant.parse("2026-08-23T05:00:00Z").toEpochMilli(), "Asia/Hong_Kong")
        assertEquals("\"PENDING\"", undone["status"].toString())
        assertFalse(undone.containsKey("completedAt"))
        assertFalse(undone.containsKey("informationContent"))

        val lateUndo = buildCompletionUndoData(instance.copy(deadline = "2026-08-23T12:00"), Instant.parse("2026-08-23T05:00:00Z").toEpochMilli(), "Asia/Hong_Kong")
        assertEquals("\"MISSED\"", lateUndo["status"].toString())
    }

    @Test
    fun moodResultCarriesSubmittedAnswerOnlyForCompletedInstance() {
        val instance = TaskInstanceEntity(
            taskId = "CloudMoodTask0001", occurrenceKey = "once", name = "今天的心情怎么样", description = "",
            taskDate = "2026-08-23", deadline = null, groupId = null, required = true, points = 2,
            sortOrder = null, completionMessage = "已记录", status = "COMPLETED", completedAtEpochMillis = 1_777_000_000_000,
            createdAtEpochMillis = 1_776_000_000_000, updatedAtEpochMillis = 1_777_000_000_000, executionKind = "MOOD",
        )
        val mood = MoodSubmissionEntity("CloudMoodTask0001", "once", 4, "今天状态不错", 1, 2, 2)

        val completed = buildExecutionResultData(instance, null, mood)
        assertEquals("4", completed["moodRating"]?.toString())
        assertEquals("\"今天状态不错\"", completed["moodText"]?.toString())

        val pending = buildExecutionResultData(instance.copy(status = "PENDING", completedAtEpochMillis = null), null, mood)
        assertFalse(pending.containsKey("moodRating"))
        assertFalse(pending.containsKey("moodText"))
        val draft = buildExecutionResultData(instance, null, mood.copy(submittedAtEpochMillis = null))
        assertFalse(draft.containsKey("moodRating"))
        val undone = buildCompletionUndoData(instance, Instant.parse("2026-08-23T05:00:00Z").toEpochMilli(), "Asia/Hong_Kong")
        assertFalse(undone.containsKey("moodRating"))
        assertFalse(undone.containsKey("moodText"))
    }

    private fun notification(groupKey: String, notificationId: String) = ConnectedNotification(
        groupKey = groupKey,
        type = "TASK_UPDATED",
        createdAt = "2026-08-23T00:00:00Z",
        notificationIds = listOf(notificationId),
        unread = true,
    )
}
