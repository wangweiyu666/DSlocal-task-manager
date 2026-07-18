package com.ds.localtaskmanager.data

import androidx.room.withTransaction
import com.ds.localtaskmanager.domain.RecordIdGenerator
import com.ds.localtaskmanager.domain.TaskStateMachine
import com.ds.localtaskmanager.domain.TaskStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class TaskOperationException(message: String) : IllegalStateException(message)

class TaskExecutionService(
    private val database: AppDatabase,
    private val clock: Clock,
    private val idGenerator: RecordIdGenerator,
) {
    private val dao: AppDao get() = database.appDao()

    suspend fun setStep(taskId: String, position: Int, completed: Boolean) =
        database.withTransaction {
            val instance = requireInstance(taskId)
            if (instance.status != TaskStatus.PENDING.name) {
                throw TaskOperationException("只有待完成任务可以修改步骤")
            }
            val changed = dao.updateStep(taskId, "once", position, completed, clock.millis())
            if (changed != 1) throw TaskOperationException("步骤不存在")
            log(taskId, if (completed) "STEP_COMPLETED" else "STEP_UNDONE", position.toString())
        }

    suspend fun complete(taskId: String) = database.withTransaction {
        val instance = reconcile(requireInstance(taskId))
        if (instance.status != TaskStatus.PENDING.name) {
            throw TaskOperationException("只有待完成任务可以完成")
        }
        if (dao.countIncompleteRequiredSteps(taskId, "once") > 0) {
            throw TaskOperationException("仍有必需步骤未完成")
        }
        val now = clock.millis()
        dao.upsertInstances(
            listOf(instance.copy(
                status = TaskStatus.COMPLETED.name,
                completedAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )),
        )
        dao.insertLedger(
            PointsLedgerEntity(
                ledgerId = idGenerator.next(),
                taskId = taskId,
                occurrenceKey = "once",
                groupId = instance.groupId,
                delta = instance.points,
                reason = "COMPLETED",
                createdAtEpochMillis = now,
            ),
        )
        log(taskId, "COMPLETED", null)
    }

    suspend fun undoCompletion(taskId: String) = database.withTransaction {
        val instance = requireInstance(taskId)
        if (instance.status != TaskStatus.COMPLETED.name) {
            throw TaskOperationException("任务尚未完成")
        }
        val completionEntry = dao.getLedger(taskId).lastOrNull { it.reason == "COMPLETED" }
            ?: throw TaskOperationException("缺少完成积分流水")
        val now = clock.millis()
        val nextStatus = TaskStateMachine.statusAt(
            LocalDate.parse(instance.taskDate),
            instance.deadline?.let(LocalDateTime::parse),
            nowDateTime(),
        )
        dao.upsertInstances(
            listOf(instance.copy(
                status = nextStatus.name,
                completedAtEpochMillis = null,
                updatedAtEpochMillis = now,
            )),
        )
        dao.insertLedger(
            PointsLedgerEntity(
                ledgerId = idGenerator.next(),
                taskId = taskId,
                occurrenceKey = "once",
                groupId = completionEntry.groupId,
                delta = -completionEntry.delta,
                reason = "COMPLETION_UNDONE",
                createdAtEpochMillis = now,
            ),
        )
        log(taskId, "COMPLETION_UNDONE", null)
    }

    suspend fun reconcile(taskId: String): TaskInstanceEntity = database.withTransaction {
        reconcile(requireInstance(taskId))
    }

    private suspend fun reconcile(instance: TaskInstanceEntity): TaskInstanceEntity {
        if (instance.status !in setOf(TaskStatus.NOT_STARTED.name, TaskStatus.PENDING.name)) {
            return instance
        }
        val expected = TaskStateMachine.statusAt(
            LocalDate.parse(instance.taskDate),
            instance.deadline?.let(LocalDateTime::parse),
            nowDateTime(),
        )
        if (expected.name == instance.status) return instance
        val updated = instance.copy(status = expected.name, updatedAtEpochMillis = clock.millis())
        dao.upsertInstances(listOf(updated))
        log(instance.taskId, "STATUS_RECONCILED", "${instance.status}->${expected.name}")
        return updated
    }

    private suspend fun requireInstance(taskId: String): TaskInstanceEntity =
        dao.getInstance(taskId) ?: throw TaskOperationException("任务不存在")

    private suspend fun log(taskId: String, action: String, detail: String?) {
        dao.insertLogs(
            listOf(ActionLogEntity(
                eventId = idGenerator.next(),
                taskId = taskId,
                occurrenceKey = "once",
                batchId = null,
                action = action,
                detail = detail,
                createdAtEpochMillis = clock.millis(),
            )),
        )
    }

    private fun nowDateTime(): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(clock.millis()), clock.zone)
}
