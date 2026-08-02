package com.ds.localtaskmanager.data.result

import androidx.room.withTransaction
import com.ds.localtaskmanager.data.AppDatabase
import com.ds.localtaskmanager.data.ResultRevisionEntity
import com.ds.localtaskmanager.data.dao.ResultTaskRow
import com.ds.localtaskmanager.domain.result.DailyResultCalculator
import com.ds.localtaskmanager.domain.result.DailyResultSnapshot
import com.ds.localtaskmanager.domain.result.DailyResultSummary
import com.ds.localtaskmanager.domain.result.GroupDailyResult
import com.ds.localtaskmanager.domain.result.ResultTaskItem

class RoomResultRepository(
    private val database: AppDatabase,
    private val calculator: DailyResultCalculator = DailyResultCalculator(),
) : ResultRepository {
    override suspend fun getDailyResult(taskDate: String): DailyResultSnapshot? =
        database.withTransaction {
            calculate(taskDate, database.resultDao().resultRowsForDate(taskDate))?.copy(
                domName = database.profileDao().getProfile()?.domName?.takeIf(String::isNotBlank),
            )
        }

    override suspend fun getGroupResult(taskDate: String, groupId: String?): GroupDailyResult? =
        getDailyResult(taskDate)?.groups?.singleOrNull { it.groupId == groupId }

    override suspend fun getRevisionTimeline(taskDate: String): List<ResultRevisionEntity> =
        database.resultDao().revisionsForDate(taskDate)

    override suspend fun getDailySummaries(fromDate: String, throughDate: String): List<DailyResultSummary> =
        database.withTransaction {
            database.resultDao().resultRowsInRange(fromDate, throughDate)
                .groupBy { it.taskDate }
                .mapNotNull { (date, rows) ->
                    calculate(date, rows)?.global?.let {
                        DailyResultSummary(date, it.status ?: return@let null, it.totalPoints, it.requiredCompleted, it.requiredMissed)
                    }
                }
                .sortedBy { it.taskDate }
        }

    internal fun calculate(taskDate: String, rows: List<ResultTaskRow>): DailyResultSnapshot? =
        calculator.calculate(taskDate, rows.map { it.toDomain() })

    private fun ResultTaskRow.toDomain() = ResultTaskItem(
        taskId = taskId,
        occurrenceKey = occurrenceKey,
        taskDate = taskDate,
        groupId = groupId,
        required = required,
        status = status,
        actualPoints = actualPoints,
        groupCompleteMessage = groupCompleteMessage,
        groupIncompleteMessage = groupIncompleteMessage,
        taskName = taskName,
        sortOrder = sortOrder,
        deadline = deadline,
        createdAtEpochMillis = createdAtEpochMillis,
        groupName = groupName,
        groupCreatedAtEpochMillis = groupCreatedAtEpochMillis,
    )
}
