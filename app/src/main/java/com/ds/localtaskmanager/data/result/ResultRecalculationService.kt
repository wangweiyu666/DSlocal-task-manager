package com.ds.localtaskmanager.data.result

import com.ds.localtaskmanager.data.AppDatabase
import com.ds.localtaskmanager.data.ResultRevisionEntity
import com.ds.localtaskmanager.domain.RecordIdGenerator
import com.ds.localtaskmanager.domain.result.DailyResultSnapshot
import com.ds.localtaskmanager.domain.result.GlobalDailyResult
import com.ds.localtaskmanager.domain.result.GroupDailyResult
import java.time.Clock

enum class ResultRevisionReason {
    TASK_COMPLETED,
    COMPLETION_UNDONE,
    TASK_IMPORTED,
    RECURRENCE_GENERATED,
    DEADLINE_RECONCILED,
    TASK_DELAYED,
    TASK_REOPENED,
    TASK_DATE_MOVED,
    IMPORT_MIXED,
}

internal class ResultRecalculationService(
    private val database: AppDatabase,
    private val clock: Clock,
    private val idGenerator: RecordIdGenerator,
) {
    private val repository = RoomResultRepository(database)

    suspend fun capture(dates: Collection<String>): Map<String, DailyResultSnapshot?> =
        dates.distinct().associateWith { repository.getDailyResult(it) }

    suspend fun writeChanges(
        before: Map<String, DailyResultSnapshot?>,
        afterDates: Collection<String>,
        reason: ResultRevisionReason,
        batchId: String?,
        relatedTaskIds: Collection<String>,
    ) {
        val dates = (before.keys + afterDates).distinct()
        val after = capture(dates)
        val relatedJson = relatedTaskIds.distinct().sorted().joinToString(",", "[", "]") {
            "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
        dates.forEach { date ->
            val old = before[date]
            val new = after[date]
            writeGlobal(date, old?.global, new?.global, reason, batchId, relatedJson)
            val oldGroups = old?.groups.orEmpty().associateBy { it.groupId }
            val newGroups = new?.groups.orEmpty().associateBy { it.groupId }
            (oldGroups.keys + newGroups.keys).distinct().forEach { groupId ->
                writeGroup(date, groupId, oldGroups[groupId], newGroups[groupId], reason, batchId, relatedJson)
            }
        }
    }

    private suspend fun writeGlobal(
        date: String,
        old: GlobalDailyResult?,
        new: GlobalDailyResult?,
        reason: ResultRevisionReason,
        batchId: String?,
        relatedJson: String,
    ) {
        if (old.sameAs(new)) return
        insert(date, "GLOBAL", null, old?.status?.name, new?.status?.name, old?.totalPoints, new?.totalPoints, reason, batchId, relatedJson)
    }

    private suspend fun writeGroup(
        date: String,
        groupId: String?,
        old: GroupDailyResult?,
        new: GroupDailyResult?,
        reason: ResultRevisionReason,
        batchId: String?,
        relatedJson: String,
    ) {
        if (old.sameAs(new)) return
        insert(date, "GROUP", groupId, old?.status?.name, new?.status?.name, old?.points, new?.points, reason, batchId, relatedJson)
    }

    private suspend fun insert(
        date: String,
        scope: String,
        groupId: String?,
        oldStatus: String?,
        newStatus: String?,
        oldPoints: Int?,
        newPoints: Int?,
        reason: ResultRevisionReason,
        batchId: String?,
        relatedJson: String,
    ) = database.resultDao().insertRevision(
        ResultRevisionEntity(
            revisionId = idGenerator.next(),
            taskDate = date,
            scope = scope,
            groupId = groupId,
            oldStatus = oldStatus,
            newStatus = newStatus,
            oldPoints = oldPoints,
            newPoints = newPoints,
            reason = reason.name,
            batchId = batchId,
            relatedTaskIdsJson = relatedJson,
            createdAtEpochMillis = clock.millis(),
        ),
    )

    private fun GlobalDailyResult?.sameAs(other: GlobalDailyResult?): Boolean =
        this?.status == other?.status && this?.totalPoints == other?.totalPoints && this?.fingerprint == other?.fingerprint

    private fun GroupDailyResult?.sameAs(other: GroupDailyResult?): Boolean =
        this?.status == other?.status && this?.points == other?.points && this?.fingerprint == other?.fingerprint
}
