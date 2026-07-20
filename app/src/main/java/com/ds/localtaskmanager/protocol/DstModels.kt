package com.ds.localtaskmanager.protocol

import com.ds.localtaskmanager.domain.execution.ExecutionSpec
import com.ds.localtaskmanager.domain.recurrence.RecurrenceSpec
import java.time.LocalDate
import java.time.LocalDateTime

sealed interface Field<out T> {
    data object Missing : Field<Nothing>
    data class Value<T>(val value: T) : Field<T>
}

data class DstBatch(
    val version: Int,
    val batchId: String,
    val domName: Field<String>,
    val note: String?,
    val groups: List<DstGroupPatch>,
    val ungroupedTasks: List<DstTask>,
    val cancelledTaskIds: List<String>,
)

data class DstGroupPatch(
    val groupId: String,
    val name: Field<String>,
    val completeMessage: Field<String>,
    val incompleteMessage: Field<String>,
    val tasks: List<DstTask>,
)

data class DstTask(
    val taskId: String,
    val name: String,
    val required: Boolean,
    val description: String,
    val taskDateDirective: Field<LocalDate>,
    val deadlineDirective: Field<LocalDateTime?>,
    val taskDate: LocalDate,
    val deadline: LocalDateTime?,
    val points: Int,
    val sortOrder: Int?,
    val steps: List<DstStep>,
    val completionMessage: String,
    val groupId: String?,
    val execution: ExecutionSpec,
    val recurrence: RecurrenceSpec,
    val reminderMinutes: List<Int>,
)

data class DstStep(
    val name: String,
    val required: Boolean,
)

fun DstBatch.allTasks(): List<DstTask> = groups.flatMap { it.tasks } + ungroupedTasks
