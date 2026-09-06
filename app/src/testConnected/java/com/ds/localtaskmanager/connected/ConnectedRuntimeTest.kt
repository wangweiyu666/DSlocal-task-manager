package com.ds.localtaskmanager.connected

import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.data.MoodSubmissionEntity
import com.ds.localtaskmanager.data.InstanceStepEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

    @Test
    fun stepsResultContainsAllFiveLeafTypesAndNoSkippedAnswer() {
        val instance = baseInstance("STEPS")
        val steps = listOf(
            InstanceStepEntity("CloudSteps0001", "once", 0, "计数", true, true, 1, "step-counter-0001", "COUNTER", executionTarget = 3, stepStatus = "CONFIRMED", counterValue = 3),
            InstanceStepEntity("CloudSteps0001", "once", 1, "计时", true, true, 1, "step-timer-000001", "TIMER", executionTarget = 2, stepStatus = "CONFIRMED", elapsedMillis = 2_000),
            InstanceStepEntity("CloudSteps0001", "once", 2, "告知", true, true, 1, "step-info-000001", "INFORMATION", stepStatus = "CONFIRMED", informationContent = "已完成"),
            InstanceStepEntity("CloudSteps0001", "once", 3, "心情", true, true, 1, "step-mood-000001", "MOOD", stepStatus = "CONFIRMED", moodRating = 4, moodText = "不错"),
            InstanceStepEntity("CloudSteps0001", "once", 4, "直接", true, true, 1, "step-direct-0001", "NORMAL", stepStatus = "CONFIRMED"),
            InstanceStepEntity("CloudSteps0001", "once", 5, "选做", false, false, 1, "step-skipped-01", "INFORMATION", stepStatus = "SKIPPED", informationContent = "草稿不应上传"),
        )
        val data = buildExecutionResultData(instance, null, steps = steps)
        val results = data["stepResults"]!!.jsonArray
        assertEquals(6, results.size)
        assertEquals(listOf("step-counter-0001", "step-timer-000001", "step-info-000001", "step-mood-000001", "step-direct-0001", "step-skipped-01"), results.map { it.jsonObject["stepId"]!!.jsonPrimitive.content })
        assertEquals(3, results[0].jsonObject["counterValue"]!!.jsonPrimitive.int)
        assertEquals(2_000, results[1].jsonObject["elapsedMillis"]!!.jsonPrimitive.int)
        assertEquals("已完成", results[2].jsonObject["informationContent"]!!.jsonPrimitive.content)
        assertEquals(4, results[3].jsonObject["moodRating"]!!.jsonPrimitive.int)
        assertTrue(results[1].jsonObject.containsKey("elapsedMillis"))
        assertTrue(results[2].jsonObject.containsKey("informationContent"))
        assertTrue(results[3].jsonObject.containsKey("moodRating"))
        assertEquals("CONFIRMED", results[4].jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("step-skipped-01", results[5].jsonObject["stepId"]!!.jsonPrimitive.content)
        assertEquals("SKIPPED", results[5].jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(setOf("stepId", "status"), results[5].jsonObject.keys)
    }

    @Test
    fun pendingStepsNeverPretendToBeConfirmedAndMissedHasNoStepResults() {
        val pending = listOf(InstanceStepEntity("CloudSteps0001", "once", 0, "计数", true, false, 1, "step-counter-0001", "COUNTER", executionTarget = 3))
        val completed = buildExecutionResultData(baseInstance("STEPS"), null, steps = pending)
        assertFalse(completed.containsKey("stepResults"))
        val missed = buildExecutionResultData(baseInstance("STEPS").copy(status = "MISSED", completedAtEpochMillis = null), null, steps = pending)
        assertFalse(missed.containsKey("stepResults"))
        assertFalse(hasFinalStepSnapshot(pending))
    }

    @Test
    fun newerUndoSnapshotCannotConstructOldCompletedResult() {
        val oldCompleted = baseInstance("STEPS")
        val newerUndo = oldCompleted.copy(status = "PENDING", completedAtEpochMillis = null, updatedAtEpochMillis = oldCompleted.updatedAtEpochMillis + 1)
        val steps = listOf(InstanceStepEntity("CloudSteps0001", "once", 0, "计数", true, true, 1, "step-counter-0001", "COUNTER", stepStatus = "CONFIRMED", counterValue = 3))
        assertEquals(null, prepareResultSnapshot(oldCompleted, newerUndo, null, null, steps))
        assertEquals(oldCompleted, prepareResultSnapshot(oldCompleted, oldCompleted, null, null, steps)?.instance)
        assertFalse(buildExecutionResultData(newerUndo, null).containsKey("stepResults"))
    }

    private fun baseInstance(kind: String) = TaskInstanceEntity(
        taskId = "CloudSteps0001", occurrenceKey = "once", name = "步骤任务", description = "",
        taskDate = "2026-08-23", deadline = null, groupId = null, required = true, points = 2,
        sortOrder = null, completionMessage = "完成", status = "COMPLETED", completedAtEpochMillis = 1_777_000_000_000,
        createdAtEpochMillis = 1_776_000_000_000, updatedAtEpochMillis = 1_777_000_000_000, executionKind = kind,
    )

    private fun notification(groupKey: String, notificationId: String) = ConnectedNotification(
        groupKey = groupKey,
        type = "TASK_UPDATED",
        createdAt = "2026-08-23T00:00:00Z",
        notificationIds = listOf(notificationId),
        unread = true,
    )
}
