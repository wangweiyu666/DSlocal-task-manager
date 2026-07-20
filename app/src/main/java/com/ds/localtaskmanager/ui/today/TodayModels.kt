package com.ds.localtaskmanager.ui.today

import com.ds.localtaskmanager.data.TodayTask
import com.ds.localtaskmanager.domain.TaskStatus

enum class TodayCategory(
    val storageValue: String,
    val label: String,
) {
    DAILY("DAILY", "每日任务"),
    WEEKLY("WEEKLY", "每周任务"),
    TEMPORARY("TEMPORARY", "临时任务"),
}

data class TodayGroupUi(
    val key: String,
    val category: TodayCategory,
    val groupId: String?,
    val groupName: String,
    val tasks: List<TodayTask>,
)

data class TodayUiState(
    val loading: Boolean = true,
    val taskDate: String = "",
    val sections: List<TodayGroupUi> = emptyList(),
    val error: String? = null,
)

fun buildTodaySections(tasks: List<TodayTask>): List<TodayGroupUi> =
    TodayCategory.entries.flatMap { category ->
        tasks
            .asSequence()
            .filter { it.instance.status != TaskStatus.CANCELLED.name }
            .filter { it.instance.category == category.storageValue }
            .groupBy { it.instance.groupId }
            .values
            .sortedWith(
                compareBy<List<TodayTask>>(
                    { it.first().instance.groupId == null },
                    { it.first().groupCreatedAtEpochMillis ?: Long.MAX_VALUE },
                    { it.first().groupName.orEmpty() },
                ),
            )
            .map { groupTasks ->
                val first = groupTasks.first()
                TodayGroupUi(
                    key = "${category.storageValue}:${first.instance.groupId ?: "ungrouped"}",
                    category = category,
                    groupId = first.instance.groupId,
                    groupName = first.groupName ?: "未分组",
                    tasks = groupTasks.sortedWith(todayTaskComparator),
                )
            }
    }

private val todayTaskComparator = Comparator<TodayTask> { left, right ->
    compareValues(left.instance.sortOrder == null, right.instance.sortOrder == null)
        .takeIf { it != 0 }
        ?: compareValues(left.instance.sortOrder ?: 0, right.instance.sortOrder ?: 0)
            .takeIf { left.instance.sortOrder != null && right.instance.sortOrder != null && it != 0 }
        ?: compareValues(!left.instance.required, !right.instance.required)
            .takeIf { it != 0 }
        ?: compareValues(left.instance.deadline == null, right.instance.deadline == null)
            .takeIf { it != 0 }
        ?: compareValues(left.instance.deadline.orEmpty(), right.instance.deadline.orEmpty())
            .takeIf { it != 0 }
        ?: compareValues(left.instance.createdAtEpochMillis, right.instance.createdAtEpochMillis)
}
