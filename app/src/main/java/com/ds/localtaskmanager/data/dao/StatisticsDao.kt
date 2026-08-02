package com.ds.localtaskmanager.data.dao

import androidx.room.Dao
import androidx.room.Query
import com.ds.localtaskmanager.data.TaskGroupEntity

data class PointsBucketRow(
    val bucket: String,
    val points: Int,
)

data class GroupPointsRow(
    val groupId: String?,
    val points: Int,
)

data class GroupTaskCountRow(
    val groupId: String?,
    val required: Boolean,
    val status: String,
    val count: Int,
)

data class ClassificationCountRow(
    val category: String,
    val required: Boolean,
    val status: String,
    val count: Int,
)

data class LedgerDisplayRow(
    val ledgerId: String,
    val taskId: String,
    val occurrenceKey: String,
    val taskName: String,
    val taskDate: String,
    val groupId: String?,
    val groupName: String?,
    val delta: Int,
    val reason: String,
    val createdAtEpochMillis: Long,
)

@Dao
interface StatisticsDao {
    @Query(
        """
        SELECT COALESCE(SUM(l.delta), 0)
        FROM points_ledger l
        JOIN task_instance i ON i.taskId = l.taskId AND i.occurrenceKey = l.occurrenceKey
        WHERE i.taskDate <= :throughDate
          AND (:fromDate IS NULL OR i.taskDate >= :fromDate)
        """,
    )
    suspend fun netPoints(fromDate: String?, throughDate: String): Int

    @Query(
        """
        SELECT CASE WHEN :monthly THEN substr(i.taskDate, 1, 7) ELSE i.taskDate END AS bucket,
               CAST(SUM(l.delta) AS INTEGER) AS points
        FROM points_ledger l
        JOIN task_instance i ON i.taskId = l.taskId AND i.occurrenceKey = l.occurrenceKey
        WHERE i.taskDate <= :throughDate
          AND (:fromDate IS NULL OR i.taskDate >= :fromDate)
        GROUP BY bucket
        ORDER BY bucket
        """,
    )
    suspend fun pointsTrend(fromDate: String?, throughDate: String, monthly: Boolean): List<PointsBucketRow>

    @Query(
        """
        SELECT l.groupId AS groupId, CAST(SUM(l.delta) AS INTEGER) AS points
        FROM points_ledger l
        JOIN task_instance i ON i.taskId = l.taskId AND i.occurrenceKey = l.occurrenceKey
        WHERE i.taskDate <= :throughDate
          AND (:fromDate IS NULL OR i.taskDate >= :fromDate)
        GROUP BY l.groupId
        """,
    )
    suspend fun groupPoints(fromDate: String?, throughDate: String): List<GroupPointsRow>

    @Query(
        """
        SELECT i.groupId AS groupId, i.required AS required, i.status AS status, COUNT(*) AS count
        FROM task_instance i
        WHERE i.taskDate <= :throughDate
          AND (:fromDate IS NULL OR i.taskDate >= :fromDate)
          AND i.status != 'NOT_STARTED'
        GROUP BY i.groupId, i.required, i.status
        """,
    )
    suspend fun groupTaskCounts(fromDate: String?, throughDate: String): List<GroupTaskCountRow>

    @Query(
        """
        SELECT i.category AS category, i.required AS required, i.status AS status, COUNT(*) AS count
        FROM task_instance i
        WHERE i.taskDate <= :throughDate
          AND (:fromDate IS NULL OR i.taskDate >= :fromDate)
          AND i.status != 'NOT_STARTED'
        GROUP BY i.category, i.required, i.status
        """,
    )
    suspend fun classificationCounts(fromDate: String?, throughDate: String): List<ClassificationCountRow>

    @Query("SELECT * FROM task_group ORDER BY createdAtEpochMillis, groupId")
    suspend fun groups(): List<TaskGroupEntity>

    @Query("UPDATE task_group SET archived = :archived, updatedAtEpochMillis = :updatedAt WHERE groupId = :groupId")
    suspend fun setGroupArchived(groupId: String, archived: Boolean, updatedAt: Long)

    @Query("SELECT domName FROM app_profile WHERE id = 1")
    suspend fun domName(): String?

    @Query(
        """
        SELECT l.ledgerId, l.taskId, l.occurrenceKey, i.name AS taskName, i.taskDate,
               l.groupId,
               CASE
                   WHEN l.groupId IS NULL THEN NULL
                   WHEN l.groupId = i.groupId THEN COALESCE(i.groupNameSnapshot, g.name)
                   ELSE g.name
               END AS groupName,
               l.delta, l.reason, l.createdAtEpochMillis
        FROM points_ledger l
        JOIN task_instance i ON i.taskId = l.taskId AND i.occurrenceKey = l.occurrenceKey
        LEFT JOIN task_group g ON g.groupId = l.groupId
        WHERE (:query = '' OR LOWER(i.name) LIKE '%' || LOWER(:query) || '%')
          AND (
              :groupFilter = '__ALL__'
              OR (:groupFilter = '__UNGROUPED__' AND l.groupId IS NULL)
              OR l.groupId = :groupFilter
              OR (
                  l.reason IN ('GROUP_TRANSFER_IN', 'GROUP_TRANSFER_OUT')
                  AND EXISTS (
                      SELECT 1 FROM points_ledger paired
                      WHERE paired.taskId = l.taskId
                        AND paired.occurrenceKey = l.occurrenceKey
                        AND paired.createdAtEpochMillis = l.createdAtEpochMillis
                        AND paired.reason IN ('GROUP_TRANSFER_IN', 'GROUP_TRANSFER_OUT')
                        AND ((:groupFilter = '__UNGROUPED__' AND paired.groupId IS NULL)
                             OR paired.groupId = :groupFilter)
                  )
              )
          )
          AND l.reason IN (:reasons)
          AND (:fromEpochMillis IS NULL OR l.createdAtEpochMillis >= :fromEpochMillis)
          AND l.createdAtEpochMillis < :throughEpochMillisExclusive
        ORDER BY l.createdAtEpochMillis DESC, l.ledgerId DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun ledgerPage(
        query: String,
        groupFilter: String,
        reasons: List<String>,
        fromEpochMillis: Long?,
        throughEpochMillisExclusive: Long,
        limit: Int,
        offset: Int,
    ): List<LedgerDisplayRow>
}
