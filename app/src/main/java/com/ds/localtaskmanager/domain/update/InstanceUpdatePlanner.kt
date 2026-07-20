package com.ds.localtaskmanager.domain.update

import com.ds.localtaskmanager.domain.TaskStateMachine
import com.ds.localtaskmanager.domain.TaskStatus
import java.time.LocalDate
import java.time.LocalDateTime

data class InstanceUpdateRequest(
    val oldDate: LocalDate?,
    val oldDeadline: LocalDateTime?,
    val oldStatus: TaskStatus?,
    val inferredDate: LocalDate,
    val incomingDeadline: LocalDateTime?,
    val explicitDate: LocalDate?,
    val deadlineWasExplicit: Boolean,
    val restored: Boolean,
    val now: LocalDateTime,
)

data class InstanceUpdatePlan(
    val taskDate: LocalDate,
    val deadline: LocalDateTime?,
    val status: TaskStatus,
    val dateMoved: Boolean,
    val deadlineExtended: Boolean,
    val reopened: Boolean,
)

object InstanceUpdatePlanner {
    fun plan(request: InstanceUpdateRequest): InstanceUpdatePlan {
        val taskDate = request.explicitDate ?: request.oldDate ?: request.inferredDate
        val deadline = if (request.deadlineWasExplicit) {
            request.incomingDeadline
        } else {
            taskDate.plusDays(1).atTime(4, 0)
        }
        val dateMoved = request.oldDate != null && request.explicitDate != null &&
            request.explicitDate != request.oldDate
        val deadlineExtended = request.oldStatus != null && !dateMoved &&
            isLater(request.oldDeadline, deadline)
        val statusAtNewTime = TaskStateMachine.statusAt(taskDate, deadline, request.now)
        val status = when {
            request.oldStatus == null || request.restored -> statusAtNewTime
            request.oldStatus == TaskStatus.COMPLETED -> TaskStatus.COMPLETED
            request.oldStatus == TaskStatus.MISSED && (dateMoved || deadlineExtended) -> statusAtNewTime
            request.oldStatus == TaskStatus.MISSED -> TaskStatus.MISSED
            else -> statusAtNewTime
        }
        return InstanceUpdatePlan(
            taskDate = taskDate,
            deadline = deadline,
            status = status,
            dateMoved = dateMoved,
            deadlineExtended = deadlineExtended,
            reopened = request.oldStatus == TaskStatus.MISSED &&
                status in setOf(TaskStatus.NOT_STARTED, TaskStatus.PENDING),
        )
    }

    private fun isLater(old: LocalDateTime?, new: LocalDateTime?): Boolean = when {
        old == null -> false
        new == null -> true
        else -> new.isAfter(old)
    }
}
