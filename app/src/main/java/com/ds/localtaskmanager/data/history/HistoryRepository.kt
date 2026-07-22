package com.ds.localtaskmanager.data.history

import androidx.room.withTransaction
import com.ds.localtaskmanager.data.ActionLogEntity
import com.ds.localtaskmanager.data.AppDatabase
import com.ds.localtaskmanager.data.InstanceStepEntity
import com.ds.localtaskmanager.data.ResultRevisionEntity
import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.data.dao.HistoryDayRow
import com.ds.localtaskmanager.data.dao.HistoryTaskRow
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.execution.ExecutionState
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import com.ds.localtaskmanager.domain.result.DailyResultStatus

enum class HistoryRequirement { ALL, REQUIRED, OPTIONAL }

data class HistoryQuery(
    val text: String = "",
    val statuses: Set<String> = TaskStatus.entries.map(TaskStatus::name).toSet(),
    val categories: Set<String> = setOf("DAILY", "WEEKLY", "TEMPORARY"),
    val requirement: HistoryRequirement = HistoryRequirement.ALL,
    val selectedDate: String? = null,
)

data class HistoryTask(
    val instance: TaskInstanceEntity,
    val note: String?,
    val progress: String?,
    val netPoints: Int,
)

data class HistoryDay(
    val taskDate: String,
    val resultStatus: DailyResultStatus?,
    val taskCount: Int,
    val completedCount: Int,
    val effectiveCount: Int,
    val netPoints: Int,
    val tasks: List<HistoryTask>,
)

data class HistoryPage(val days: List<HistoryDay>, val endReached: Boolean)

data class HistoryDetail(
    val instance: TaskInstanceEntity,
    val steps: List<InstanceStepEntity>,
    val execution: ExecutionState,
    val note: String,
    val logs: List<ActionLogEntity>,
    val revisions: List<ResultRevisionEntity>,
)

interface HistoryRepository {
    suspend fun loadPage(query: HistoryQuery, throughDate: String, page: Int, pageSize: Int = 30): HistoryPage
    suspend fun datesInRange(query: HistoryQuery, fromDate: String, throughDate: String): Set<String>
    suspend fun getDetail(key: TaskInstanceKey): HistoryDetail?
    suspend fun previousDate(beforeDate: String): String?
}

class RoomHistoryRepository(private val database: AppDatabase) : HistoryRepository {
    override suspend fun previousDate(beforeDate: String): String? =
        database.instanceDao().previousHistoryDate(beforeDate)

    override suspend fun loadPage(
        query: HistoryQuery,
        throughDate: String,
        page: Int,
        pageSize: Int,
    ): HistoryPage = database.withTransaction {
        val required = query.requirement.toDatabaseValue()
        val rows = database.instanceDao().historyDays(
            throughDate = throughDate,
            selectedDate = query.selectedDate,
            query = query.text.trim(),
            statuses = query.statuses.nonEmptyOrAllStatuses(),
            categories = query.categories.nonEmptyOrAllCategories(),
            requiredFilter = required,
            limit = pageSize,
            offset = page * pageSize,
        )
        val tasks = if (rows.isEmpty()) emptyList() else database.instanceDao().historyTasks(
            taskDates = rows.map(HistoryDayRow::taskDate),
            query = query.text.trim(),
            statuses = query.statuses.nonEmptyOrAllStatuses(),
            categories = query.categories.nonEmptyOrAllCategories(),
            requiredFilter = required,
        )
        val tasksByDate = tasks.groupBy { it.instance.taskDate }
        HistoryPage(
            days = rows.map { row -> row.toDomain(tasksByDate[row.taskDate].orEmpty()) },
            endReached = rows.size < pageSize || query.selectedDate != null,
        )
    }

    override suspend fun datesInRange(query: HistoryQuery, fromDate: String, throughDate: String): Set<String> =
        database.instanceDao().historyDatesInRange(
            fromDate,
            throughDate,
            query.text.trim(),
            query.statuses.nonEmptyOrAllStatuses(),
            query.categories.nonEmptyOrAllCategories(),
            query.requirement.toDatabaseValue(),
        ).toSet()

    override suspend fun getDetail(key: TaskInstanceKey): HistoryDetail? = database.withTransaction {
        val instance = database.instanceDao().getInstance(key.taskId, key.occurrenceKey) ?: return@withTransaction null
        val progress = database.executionDao().getProgress(key.taskId, key.occurrenceKey)
        val submission = database.executionDao().getSubmission(key.taskId, key.occurrenceKey)
        HistoryDetail(
            instance = instance,
            steps = database.instanceDao().getInstanceSteps(key.taskId, key.occurrenceKey),
            execution = executionState(instance, progress?.counterValue, progress?.elapsedMillis, submission?.content),
            note = database.executionDao().getNote(key.taskId, key.occurrenceKey)?.content.orEmpty(),
            logs = database.auditDao().getLogs(key.taskId, key.occurrenceKey),
            revisions = database.resultDao().revisionsForDate(instance.taskDate).filter { revision ->
                revision.relatedTaskIdsJson.contains("\"${instance.taskId}\"")
            },
        )
    }

    private fun HistoryDayRow.toDomain(tasks: List<HistoryTaskRow>) = HistoryDay(
        taskDate = taskDate,
        resultStatus = when {
            requiredMissedCount > 0 -> DailyResultStatus.INCOMPLETE
            requiredPendingCount > 0 -> DailyResultStatus.IN_PROGRESS
            requiredCount > 0 -> DailyResultStatus.COMPLETED
            effectiveCount > 0 -> DailyResultStatus.OPTIONAL_ONLY
            else -> null
        },
        taskCount = taskCount,
        completedCount = completedCount,
        effectiveCount = effectiveCount,
        netPoints = netPoints,
        tasks = tasks.map { it.toDomain() },
    )

    private fun HistoryTaskRow.toDomain() = HistoryTask(
        instance = instance,
        note = note,
        progress = when {
            totalSteps > 0 -> "$completedSteps/$totalSteps 步"
            instance.executionKind == "COUNTER" -> "计数 ${counterValue ?: 0}/${instance.executionTarget ?: 0}"
            instance.executionKind == "TIMER" -> "用时 ${formatDuration(elapsedMillis ?: 0)}/${formatDuration((instance.executionTarget ?: 0) * 1_000L)}"
            else -> null
        },
        netPoints = netPoints,
    )

    private fun executionState(
        instance: TaskInstanceEntity,
        counterValue: Int?,
        elapsedMillis: Long?,
        information: String?,
    ): ExecutionState = when (instance.executionKind) {
        "COUNTER" -> ExecutionState.Counter(
            action = if (instance.executionAction == 1) {
                com.ds.localtaskmanager.domain.execution.CounterAction.SLIDER
            } else {
                com.ds.localtaskmanager.domain.execution.CounterAction.CLICK
            },
            value = counterValue ?: 0,
            target = instance.executionTarget ?: 0,
        )
        "TIMER" -> ExecutionState.Timer(
            elapsedMillis = elapsedMillis ?: 0,
            targetMillis = (instance.executionTarget ?: 0) * 1_000L,
        )
        "INFORMATION" -> ExecutionState.Information(information.orEmpty(), null)
        else -> ExecutionState.Normal
    }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1_000
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }
    private fun HistoryRequirement.toDatabaseValue(): Boolean? = when (this) {
        HistoryRequirement.ALL -> null
        HistoryRequirement.REQUIRED -> true
        HistoryRequirement.OPTIONAL -> false
    }
    private fun Set<String>.nonEmptyOrAllStatuses() = ifEmpty { TaskStatus.entries.map(TaskStatus::name).toSet() }.toList()
    private fun Set<String>.nonEmptyOrAllCategories() = ifEmpty { setOf("DAILY", "WEEKLY", "TEMPORARY") }.toList()
}
