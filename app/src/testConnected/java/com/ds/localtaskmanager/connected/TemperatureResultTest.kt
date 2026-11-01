package com.ds.localtaskmanager.connected

import com.ds.localtaskmanager.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TemperatureResultTest {
    private val instance = TaskInstanceEntity(
        taskId = "Task000000000001", occurrenceKey = "once", name = "体温", description = "",
        taskDate = "2026-10-31", deadline = null, groupId = null, required = true, points = 3,
        sortOrder = null, completionMessage = "", status = "COMPLETED", completedAtEpochMillis = 100,
        createdAtEpochMillis = 1, updatedAtEpochMillis = 100, executionKind = "TEMPERATURE",
    )
    private val answer = TemperatureSubmissionEntity(instance.taskId, "once", 365, 1, 100, 100)

    @Test fun temperatureSnapshotRequiresMatchingCommittedAnswer() {
        assertNull(prepareResultSnapshot(instance, instance, null, null, null))
        assertNull(prepareResultSnapshot(instance, instance, null, null, null, temperature = answer.copy(submittedAtEpochMillis = 99)))
        assertNull(prepareResultSnapshot(instance, instance, null, null, null, temperature = answer.copy(temperatureTenthsC = 349)))
        assertNotNull(prepareResultSnapshot(instance, instance, null, null, null, temperature = answer))
    }

    @Test fun temperatureOnlyUploadsFinalAnswerAndUndoCarriesNoReading() {
        assertEquals(365, buildExecutionResultData(instance, null, temperature = answer)["temperatureTenthsC"]?.jsonPrimitive?.int)
        assertFalse(buildExecutionResultData(instance.copy(status = "MISSED", completedAtEpochMillis = null), null, temperature = answer).containsKey("temperatureTenthsC"))
        assertFalse(buildExecutionResultData(instance.copy(status = "PENDING", completedAtEpochMillis = null), null, temperature = answer.copy(submittedAtEpochMillis = null)).containsKey("temperatureTenthsC"))
        assertFalse(buildCompletionUndoData(instance, 200, "UTC").containsKey("temperatureTenthsC"))
    }

    @Test fun temperatureStepsOnlyUploadConfirmedReadings() {
        val steps = listOf(
            InstanceStepEntity(instance.taskId, "once", 0, "必需", true, true, 100, stepId = "Step000000000001", executionKind = "TEMPERATURE", stepStatus = "CONFIRMED", temperatureTenthsC = 365),
            InstanceStepEntity(instance.taskId, "once", 1, "选做", false, false, 100, stepId = "Step000000000002", executionKind = "TEMPERATURE", stepStatus = "SKIPPED", temperatureTenthsC = 370),
        )
        val data = buildExecutionResultData(instance.copy(executionKind = "STEPS"), null, steps = steps)["stepResults"]!!.jsonArray
        assertEquals(365, data[0].jsonObject["temperatureTenthsC"]?.jsonPrimitive?.int)
        assertFalse(data[1].jsonObject.containsKey("temperatureTenthsC"))
        assertFalse(hasFinalStepSnapshot(steps.map { it.copy(temperatureTenthsC = null) }))
    }
}
