package com.ds.localtaskmanager.data.statistics

import androidx.room.withTransaction
import com.ds.localtaskmanager.data.AppDatabase
import com.ds.localtaskmanager.data.dao.ClassificationCountRow
import com.ds.localtaskmanager.data.dao.GroupTaskCountRow
import com.ds.localtaskmanager.data.dao.LedgerDisplayRow
import com.ds.localtaskmanager.domain.TaskStatus
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

enum class StatisticsPeriod(val days: Long?) {
    SEVEN_DAYS(7),
    THIRTY_DAYS(30),
    ALL(null),
}

enum class LedgerType {
    EARNED,
    DEDUCTED,
    TRANSFER,
}

data class PointsOverview(
    val cumulative: Int,
    val today: Int,
    val sevenDays: Int,
    val thirtyDays: Int,
)

data class TrendPoint(val label: String, val points: Int)

data class CompletionSummary(val completed: Int, val total: Int) {
    val fraction: Float? get() = if (total == 0) null else completed.toFloat() / total
}

data class StatusCounts(
    val completed: Int = 0,
    val missed: Int = 0,
    val pending: Int = 0,
    val cancelled: Int = 0,
) {
    val total: Int get() = completed + missed + pending + cancelled
}

data class GroupStatistics(
    val groupId: String?,
    val name: String,
    val archived: Boolean,
    val createdAtEpochMillis: Long,
    val points: Int,
    val completion: CompletionSummary,
)

data class ClassificationStatistics(
    val key: String,
    val label: String,
    val counts: StatusCounts,
)

data class StatisticsDashboard(
    val domName: String?,
    val overview: PointsOverview,
    val trend: List<TrendPoint>,
    val completion: CompletionSummary,
    val groups: List<GroupStatistics>,
    val requirement: List<ClassificationStatistics>,
    val categories: List<ClassificationStatistics>,
)

sealed interface LedgerItem {
    val stableId: String
    val taskName: String
    val createdAtEpochMillis: Long

    data class Change(
        override val stableId: String,
        override val taskName: String,
        override val createdAtEpochMillis: Long,
        val groupId: String?,
        val groupName: String,
        val delta: Int,
        val type: LedgerType,
    ) : LedgerItem

    data class Transfer(
        override val stableId: String,
        override val taskName: String,
        override val createdAtEpochMillis: Long,
        val sourceNames: List<String>,
        val targetName: String,
        val points: Int,
    ) : LedgerItem
}

data class LedgerQuery(
    val text: String = "",
    val period: StatisticsPeriod = StatisticsPeriod.ALL,
    val groupId: String? = null,
    val ungroupedOnly: Boolean = false,
    val types: Set<LedgerType> = LedgerType.entries.toSet(),
)

data class LedgerPage(val items: List<LedgerItem>, val endReached: Boolean)

interface StatisticsRepository {
    suspend fun dashboard(period: StatisticsPeriod): StatisticsDashboard
    suspend fun setGroupArchived(groupId: String, archived: Boolean)
    suspend fun ledger(query: LedgerQuery, page: Int, pageSize: Int = 50): LedgerPage
}

class RoomStatisticsRepository(
    private val database: AppDatabase,
    private val clock: Clock = Clock.systemDefaultZone(),
) : StatisticsRepository {
    private val dao get() = database.statisticsDao()

    override suspend fun dashboard(period: StatisticsPeriod): StatisticsDashboard = database.withTransaction {
        val today = currentTaskDate()
        val fromDate = period.fromDate(today)
        val sevenFrom = today.minusDays(6).toString()
        val thirtyFrom = today.minusDays(29).toString()
        val counts = dao.classificationCounts(fromDate, today.toString())
        val groups = dao.groups()
        val groupPoints = dao.groupPoints(fromDate, today.toString()).associate { it.groupId to it.points }
        val groupCounts = dao.groupTaskCounts(fromDate, today.toString()).groupBy(GroupTaskCountRow::groupId)
        val knownGroupIds = (groupPoints.keys + groupCounts.keys).toSet()

        StatisticsDashboard(
            domName = dao.domName()?.takeIf(String::isNotBlank),
            overview = PointsOverview(
                cumulative = dao.netPoints(null, today.toString()),
                today = dao.netPoints(today.toString(), today.toString()),
                sevenDays = dao.netPoints(sevenFrom, today.toString()),
                thirtyDays = dao.netPoints(thirtyFrom, today.toString()),
            ),
            trend = dao.pointsTrend(fromDate, today.toString(), period == StatisticsPeriod.ALL)
                .map { TrendPoint(it.bucket, it.points) },
            completion = counts.classificationCompletion(),
            groups = buildList {
                groups.forEach { group ->
                    add(
                        GroupStatistics(
                            groupId = group.groupId,
                            name = group.name,
                            archived = group.archived,
                            createdAtEpochMillis = group.createdAtEpochMillis,
                            points = groupPoints[group.groupId] ?: 0,
                            completion = groupCounts[group.groupId].orEmpty().groupCompletion(),
                        ),
                    )
                }
                if (null in knownGroupIds) {
                    add(
                        GroupStatistics(
                            groupId = null,
                            name = "未分组",
                            archived = false,
                            createdAtEpochMillis = Long.MAX_VALUE,
                            points = groupPoints[null] ?: 0,
                            completion = groupCounts[null].orEmpty().groupCompletion(),
                        ),
                    )
                }
            }.sortedWith(
                compareBy<GroupStatistics> { it.groupId == null }
                    .thenByDescending(GroupStatistics::points)
                    .thenBy(GroupStatistics::createdAtEpochMillis),
            ),
            requirement = listOf(
                ClassificationStatistics("REQUIRED", "必做", counts.filter { it.required }.statusCounts()),
                ClassificationStatistics("OPTIONAL", "选做", counts.filterNot { it.required }.statusCounts()),
            ),
            categories = listOf(
                ClassificationStatistics("DAILY", "每日", counts.filter { it.category == "DAILY" }.statusCounts()),
                ClassificationStatistics("WEEKLY", "每周", counts.filter { it.category == "WEEKLY" }.statusCounts()),
                ClassificationStatistics("TEMPORARY", "临时", counts.filter { it.category == "TEMPORARY" }.statusCounts()),
            ),
        )
    }

    override suspend fun setGroupArchived(groupId: String, archived: Boolean) {
        dao.setGroupArchived(groupId, archived, clock.millis())
    }

    override suspend fun ledger(query: LedgerQuery, page: Int, pageSize: Int): LedgerPage {
        val today = currentTaskDate()
        val fromDate = query.period.fromDate(today)
        val zone = clock.zone
        val fromEpoch = fromDate?.let { taskDayStart(LocalDate.parse(it), zone) }
        val throughEpoch = taskDayStart(today.plusDays(1), zone)
        val reasons = query.types.flatMap(LedgerType::reasons).ifEmpty { listOf("__NONE__") }
        val rawLimit = pageSize + 1
        val rawOffset = page * pageSize
        val rows = dao.ledgerPage(
            query = query.text.trim(),
            groupFilter = when {
                query.ungroupedOnly -> "__UNGROUPED__"
                query.groupId != null -> query.groupId
                else -> "__ALL__"
            },
            reasons = reasons,
            fromEpochMillis = fromEpoch,
            throughEpochMillisExclusive = throughEpoch,
            limit = rawLimit,
            offset = rawOffset,
        )
        val precedingTransfer = if (rawOffset == 0) null else {
            dao.ledgerPage(
                query = query.text.trim(),
                groupFilter = when {
                    query.ungroupedOnly -> "__UNGROUPED__"
                    query.groupId != null -> query.groupId
                    else -> "__ALL__"
                },
                reasons = reasons,
                fromEpochMillis = fromEpoch,
                throughEpochMillisExclusive = throughEpoch,
                limit = 1,
                offset = rawOffset - 1,
            ).singleOrNull()?.transferKey()
        }
        val items = rows.filterNot { it.transferKey() == precedingTransfer && precedingTransfer != null }.toLedgerItems()
        return LedgerPage(items.take(pageSize), rows.size < rawLimit)
    }

    private fun currentTaskDate(): LocalDate =
        com.ds.localtaskmanager.domain.TaskDay.from(LocalDateTime.now(clock))

    private fun taskDayStart(date: LocalDate, zone: ZoneId): Long =
        date.atTime(LocalTime.of(4, 0)).atZone(zone).toInstant().toEpochMilli()
}

private fun StatisticsPeriod.fromDate(today: LocalDate): String? =
    days?.let { today.minusDays(it - 1).toString() }

private fun List<ClassificationCountRow>.classificationCompletion(): CompletionSummary {
    val required = filter { it.required }
    val completed = required.filter { it.status == TaskStatus.COMPLETED.name }.sumOf { it.count }
    val denominator = required.filter {
        it.status == TaskStatus.COMPLETED.name ||
            it.status == TaskStatus.MISSED.name ||
            it.status == TaskStatus.PENDING.name
    }.sumOf { it.count }
    return CompletionSummary(completed, denominator)
}

private fun List<GroupTaskCountRow>.groupCompletion(): CompletionSummary {
    val required = filter { it.required }
    val completed = required.filter { it.status == TaskStatus.COMPLETED.name }.sumOf { it.count }
    val denominator = required.filter {
        it.status == TaskStatus.COMPLETED.name ||
            it.status == TaskStatus.MISSED.name ||
            it.status == TaskStatus.PENDING.name
    }.sumOf { it.count }
    return CompletionSummary(completed, denominator)
}

private fun List<ClassificationCountRow>.statusCounts() = StatusCounts(
    completed = filter { it.status == TaskStatus.COMPLETED.name }.sumOf { it.count },
    missed = filter { it.status == TaskStatus.MISSED.name }.sumOf { it.count },
    pending = filter { it.status == TaskStatus.PENDING.name }.sumOf { it.count },
    cancelled = filter { it.status == TaskStatus.CANCELLED.name }.sumOf { it.count },
)

private val LedgerType.reasons: List<String>
    get() = when (this) {
        LedgerType.EARNED -> listOf("COMPLETED")
        LedgerType.DEDUCTED -> listOf("COMPLETION_UNDONE")
        LedgerType.TRANSFER -> listOf("GROUP_TRANSFER_IN", "GROUP_TRANSFER_OUT")
    }

private fun List<LedgerDisplayRow>.toLedgerItems(): List<LedgerItem> {
    val transferKeys = filter { it.reason.startsWith("GROUP_TRANSFER_") }
        .groupBy { Triple(it.taskId, it.occurrenceKey, it.createdAtEpochMillis) }
    val transfers = transferKeys.map { (key, rows) ->
        val incoming = rows.filter { it.reason == "GROUP_TRANSFER_IN" }
        val outgoing = rows.filter { it.reason == "GROUP_TRANSFER_OUT" }
        LedgerItem.Transfer(
            stableId = rows.joinToString(":", transform = LedgerDisplayRow::ledgerId),
            taskName = rows.first().taskName,
            createdAtEpochMillis = key.third,
            sourceNames = outgoing.map { it.groupName ?: "未分组" }.distinct(),
            targetName = incoming.firstOrNull()?.groupName ?: "未分组",
            points = incoming.sumOf { it.delta }.let { if (it != 0) it else -outgoing.sumOf { row -> row.delta } },
        )
    }
    val changes = filterNot { it.reason.startsWith("GROUP_TRANSFER_") }.map { row ->
        LedgerItem.Change(
            stableId = row.ledgerId,
            taskName = row.taskName,
            createdAtEpochMillis = row.createdAtEpochMillis,
            groupId = row.groupId,
            groupName = row.groupName ?: "未分组",
            delta = row.delta,
            type = if (row.delta >= 0) LedgerType.EARNED else LedgerType.DEDUCTED,
        )
    }
    return (transfers + changes).sortedWith(
        compareByDescending<LedgerItem> { it.createdAtEpochMillis }.thenByDescending(LedgerItem::stableId),
    )
}

private fun LedgerDisplayRow.transferKey(): Triple<String, String, Long>? =
    takeIf { reason.startsWith("GROUP_TRANSFER_") }
        ?.let { Triple(it.taskId, it.occurrenceKey, it.createdAtEpochMillis) }
