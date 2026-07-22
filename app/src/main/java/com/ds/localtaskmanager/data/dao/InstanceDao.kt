package com.ds.localtaskmanager.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.ds.localtaskmanager.data.InstanceStepEntity
import com.ds.localtaskmanager.data.TaskInstanceEntity
import androidx.room.Embedded
import kotlinx.coroutines.flow.Flow

data class HistoryDayRow(
    val taskDate: String,
    val taskCount: Int,
    val completedCount: Int,
    val effectiveCount: Int,
    val requiredCount: Int,
    val requiredMissedCount: Int,
    val requiredPendingCount: Int,
    val netPoints: Int,
)

data class HistoryTaskRow(
    @Embedded val instance: TaskInstanceEntity,
    val note: String?,
    val completedSteps: Int,
    val totalSteps: Int,
    val counterValue: Int?,
    val elapsedMillis: Long?,
    val netPoints: Int,
)

data class GenerationSummary(
    val taskId: String,
    val generatedCount: Int,
    val latestDate: String?,
)

@Dao
interface InstanceDao {
    @Query("SELECT * FROM task_instance WHERE taskId IN (:ids) AND occurrenceKey = 'once'")
    suspend fun getOnceInstances(ids: List<String>): List<TaskInstanceEntity>

    @Query("SELECT * FROM task_instance WHERE taskId IN (:ids)")
    suspend fun getInstancesForTasks(ids: List<String>): List<TaskInstanceEntity>

    @Query(
        """
        SELECT * FROM task_instance
        WHERE taskDate <= :throughDate AND status IN ('NOT_STARTED', 'PENDING')
        ORDER BY taskDate, taskId, occurrenceKey
        """,
    )
    suspend fun getReconcilableInstances(throughDate: String): List<TaskInstanceEntity>

    @Query("SELECT * FROM task_instance WHERE taskId = :taskId AND occurrenceKey = :occurrenceKey")
    suspend fun getInstance(taskId: String, occurrenceKey: String = "once"): TaskInstanceEntity?

    @Query(
        """
        SELECT * FROM task_instance AS instance
        WHERE instance.reminderMinutesJson IS NOT NULL
           OR EXISTS (
               SELECT 1 FROM reminder_record AS reminder
               WHERE reminder.taskId = instance.taskId
                 AND reminder.occurrenceKey = instance.occurrenceKey
           )
        ORDER BY instance.taskDate, instance.taskId, instance.occurrenceKey
        """,
    )
    suspend fun instancesNeedingReminderReconciliation(): List<TaskInstanceEntity>

    @Upsert
    suspend fun upsertInstances(instances: List<TaskInstanceEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInstance(instance: TaskInstanceEntity): Long

    @Query(
        """
        SELECT taskId, COUNT(*) AS generatedCount, MAX(taskDate) AS latestDate
        FROM task_instance
        WHERE taskId IN (:taskIds) AND occurrenceKey != 'once'
        GROUP BY taskId
        """,
    )
    suspend fun generationSummaries(taskIds: List<String>): List<GenerationSummary>

    @Query(
        """
        SELECT occurrenceKey FROM task_instance
        WHERE taskId = :taskId AND occurrenceKey != 'once'
          AND taskDate BETWEEN :fromDate AND :throughDate
        """,
    )
    suspend fun occurrenceKeysInRange(
        taskId: String,
        fromDate: String,
        throughDate: String,
    ): List<String>

    @Query("DELETE FROM instance_step WHERE taskId = :taskId AND occurrenceKey = :occurrenceKey")
    suspend fun deleteInstanceSteps(taskId: String, occurrenceKey: String = "once")

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInstanceSteps(steps: List<InstanceStepEntity>)

    @Query(
        """
        SELECT * FROM instance_step
        WHERE taskId = :taskId AND occurrenceKey = :occurrenceKey
        ORDER BY position
        """,
    )
    suspend fun getInstanceSteps(
        taskId: String,
        occurrenceKey: String = "once",
    ): List<InstanceStepEntity>

    @Query(
        """
        SELECT * FROM task_instance
        WHERE taskDate = :taskDate AND status != 'CANCELLED'
        ORDER BY required DESC,
          CASE WHEN deadline IS NULL THEN 1 ELSE 0 END,
          deadline ASC,
          CASE WHEN sortOrder IS NULL THEN 1 ELSE 0 END,
          sortOrder ASC,
          createdAtEpochMillis ASC
        """,
    )
    fun observeForDate(taskDate: String): Flow<List<TaskInstanceEntity>>

    @Query(
        """
        SELECT * FROM task_instance
        WHERE (:groupId IS NULL OR groupId = :groupId)
          AND (:status IS NULL OR status = :status)
        ORDER BY taskDate DESC, updatedAtEpochMillis DESC
        """,
    )
    suspend fun queryHistory(groupId: String?, status: String?): List<TaskInstanceEntity>

    @Query(
        """
        SELECT i.taskDate AS taskDate,
          (SELECT COUNT(*) FROM task_instance a WHERE a.taskDate = i.taskDate) AS taskCount,
          (SELECT COUNT(*) FROM task_instance a WHERE a.taskDate = i.taskDate AND a.status = 'COMPLETED') AS completedCount,
          (SELECT COUNT(*) FROM task_instance a WHERE a.taskDate = i.taskDate AND a.status NOT IN ('CANCELLED', 'NOT_STARTED')) AS effectiveCount,
          (SELECT COUNT(*) FROM task_instance a WHERE a.taskDate = i.taskDate AND a.required = 1 AND a.status NOT IN ('CANCELLED', 'NOT_STARTED')) AS requiredCount,
          (SELECT COUNT(*) FROM task_instance a WHERE a.taskDate = i.taskDate AND a.required = 1 AND a.status = 'MISSED') AS requiredMissedCount,
          (SELECT COUNT(*) FROM task_instance a WHERE a.taskDate = i.taskDate AND a.required = 1 AND a.status = 'PENDING') AS requiredPendingCount,
          CAST(COALESCE((
            SELECT SUM(l.delta) FROM points_ledger l
            INNER JOIN task_instance p ON p.taskId = l.taskId AND p.occurrenceKey = l.occurrenceKey
            WHERE p.taskDate = i.taskDate
          ), 0) AS INTEGER) AS netPoints
        FROM task_instance i
        LEFT JOIN task_note n ON n.taskId = i.taskId AND n.occurrenceKey = i.occurrenceKey
        WHERE i.taskDate <= :throughDate
          AND (:selectedDate IS NULL OR i.taskDate = :selectedDate)
          AND i.status IN (:statuses)
          AND i.category IN (:categories)
          AND (:requiredFilter IS NULL OR i.required = :requiredFilter)
          AND (:query = '' OR LOWER(i.name) LIKE '%' || LOWER(:query) || '%'
            OR LOWER(COALESCE(i.groupNameSnapshot, '')) LIKE '%' || LOWER(:query) || '%'
            OR LOWER(COALESCE(n.content, '')) LIKE '%' || LOWER(:query) || '%')
        GROUP BY i.taskDate
        ORDER BY i.taskDate DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun historyDays(
        throughDate: String,
        selectedDate: String?,
        query: String,
        statuses: List<String>,
        categories: List<String>,
        requiredFilter: Boolean?,
        limit: Int,
        offset: Int,
    ): List<HistoryDayRow>

    @Query(
        """
        SELECT i.*, n.content AS note,
          (SELECT COUNT(*) FROM instance_step s WHERE s.taskId = i.taskId AND s.occurrenceKey = i.occurrenceKey AND s.completed = 1) AS completedSteps,
          (SELECT COUNT(*) FROM instance_step s WHERE s.taskId = i.taskId AND s.occurrenceKey = i.occurrenceKey) AS totalSteps,
          p.counterValue AS counterValue,
          p.elapsedMillis AS elapsedMillis,
          CAST(COALESCE((SELECT SUM(l.delta) FROM points_ledger l WHERE l.taskId = i.taskId AND l.occurrenceKey = i.occurrenceKey), 0) AS INTEGER) AS netPoints
        FROM task_instance i
        LEFT JOIN task_note n ON n.taskId = i.taskId AND n.occurrenceKey = i.occurrenceKey
        LEFT JOIN execution_progress p ON p.taskId = i.taskId AND p.occurrenceKey = i.occurrenceKey
        WHERE i.taskDate IN (:taskDates)
          AND i.status IN (:statuses)
          AND i.category IN (:categories)
          AND (:requiredFilter IS NULL OR i.required = :requiredFilter)
          AND (:query = '' OR LOWER(i.name) LIKE '%' || LOWER(:query) || '%'
            OR LOWER(COALESCE(i.groupNameSnapshot, '')) LIKE '%' || LOWER(:query) || '%'
            OR LOWER(COALESCE(n.content, '')) LIKE '%' || LOWER(:query) || '%')
        ORDER BY i.taskDate DESC, i.required DESC,
          CASE WHEN i.deadline IS NULL THEN 1 ELSE 0 END, i.deadline, i.updatedAtEpochMillis DESC
        """,
    )
    suspend fun historyTasks(
        taskDates: List<String>,
        query: String,
        statuses: List<String>,
        categories: List<String>,
        requiredFilter: Boolean?,
    ): List<HistoryTaskRow>

    @Query(
        """
        SELECT DISTINCT i.taskDate FROM task_instance i
        LEFT JOIN task_note n ON n.taskId = i.taskId AND n.occurrenceKey = i.occurrenceKey
        WHERE i.taskDate BETWEEN :fromDate AND :throughDate
          AND i.status IN (:statuses)
          AND i.category IN (:categories)
          AND (:requiredFilter IS NULL OR i.required = :requiredFilter)
          AND (:query = '' OR LOWER(i.name) LIKE '%' || LOWER(:query) || '%'
            OR LOWER(COALESCE(i.groupNameSnapshot, '')) LIKE '%' || LOWER(:query) || '%'
            OR LOWER(COALESCE(n.content, '')) LIKE '%' || LOWER(:query) || '%')
        ORDER BY i.taskDate
        """,
    )
    suspend fun historyDatesInRange(
        fromDate: String,
        throughDate: String,
        query: String,
        statuses: List<String>,
        categories: List<String>,
        requiredFilter: Boolean?,
    ): List<String>

    @Query("SELECT MAX(taskDate) FROM task_instance WHERE taskDate < :beforeDate")
    suspend fun previousHistoryDate(beforeDate: String): String?

    @Query(
        """
        SELECT COUNT(*) FROM instance_step
        WHERE taskId = :taskId AND occurrenceKey = :occurrenceKey
          AND required = 1 AND completed = 0
        """,
    )
    suspend fun countIncompleteRequiredSteps(taskId: String, occurrenceKey: String): Int

    @Query(
        """
        UPDATE instance_step SET completed = :completed, updatedAtEpochMillis = :updatedAt
        WHERE taskId = :taskId AND occurrenceKey = :occurrenceKey AND position = :position
        """,
    )
    suspend fun updateStep(
        taskId: String,
        occurrenceKey: String,
        position: Int,
        completed: Boolean,
        updatedAt: Long,
    ): Int
}
