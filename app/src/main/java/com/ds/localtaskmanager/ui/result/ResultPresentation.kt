package com.ds.localtaskmanager.ui.result

import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.result.DailyResultSnapshot
import com.ds.localtaskmanager.domain.result.DailyResultStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ResultTaskPresentation(
    val name: String,
    val requirement: String,
    val status: String,
    val points: Int,
)

data class ResultGroupPresentation(
    val name: String,
    val status: String,
    val points: Int,
    val message: String?,
    val tasks: List<ResultTaskPresentation>,
)

data class ResultPresentation(
    val taskDate: String,
    val dateLabel: String,
    val domName: String?,
    val status: String,
    val totalPoints: Int,
    val groups: List<ResultGroupPresentation>,
)

fun DailyResultSnapshot.toPresentation(): ResultPresentation {
    val globalResult = requireNotNull(global)
    return ResultPresentation(
        taskDate = taskDate,
        dateLabel = formatChineseDate(taskDate),
        domName = domName?.takeIf(String::isNotBlank),
        status = resultStatusLabel(globalResult.status),
        totalPoints = globalResult.totalPoints,
        groups = groups.map { group ->
            ResultGroupPresentation(
                name = group.groupName ?: "未分组",
                status = resultStatusLabel(group.status),
                points = group.points,
                message = group.message?.takeIf(String::isNotBlank),
                tasks = tasks.filter { it.groupId == group.groupId }.sortedWith(presentationTaskComparator).map { task ->
                    ResultTaskPresentation(
                        name = task.taskName,
                        requirement = if (task.required) "必做" else "选做",
                        status = taskStatusLabel(task.status),
                        points = task.actualPoints,
                    )
                },
            )
        },
    )
}

fun ResultPresentation.toPlainText(): String = buildString {
    appendLine("今日结果")
    domName?.let { appendLine("来自 $it") }
    appendLine(dateLabel)
    appendLine("$status · 本日总积分 ${formatPoints(totalPoints)}")
    groups.forEach { group ->
        appendLine()
        appendLine("${group.name}｜${group.status} · 净积分 ${formatPoints(group.points)}")
        group.tasks.forEach { task ->
            appendLine("- ${task.name}｜${task.requirement} · ${task.status} · ${formatPoints(task.points)}")
        }
        group.message?.let(::appendLine)
    }
}.trimEnd()

fun formatPoints(points: Int): String = if (points > 0) "+$points" else points.toString()

fun resultStatusLabel(status: DailyResultStatus?): String = when (status) {
    DailyResultStatus.COMPLETED -> "全部完成"
    DailyResultStatus.IN_PROGRESS -> "尚未完成"
    DailyResultStatus.INCOMPLETE -> "有任务未完成"
    DailyResultStatus.OPTIONAL_ONLY -> "仅有选做任务"
    null -> "暂无结果"
}

fun taskStatusLabel(status: String): String = when (status) {
    TaskStatus.PENDING.name -> "待完成"
    TaskStatus.COMPLETED.name -> "已完成"
    TaskStatus.MISSED.name -> "未完成"
    else -> status
}

fun formatChineseDate(taskDate: String): String = runCatching {
    LocalDate.parse(taskDate).format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA))
}.getOrDefault(taskDate)

private val presentationTaskComparator = Comparator<com.ds.localtaskmanager.domain.result.ResultTaskItem> { left, right ->
    compareValues(left.sortOrder == null, right.sortOrder == null).takeIf { it != 0 }
        ?: compareValues(left.sortOrder ?: 0, right.sortOrder ?: 0)
            .takeIf { left.sortOrder != null && right.sortOrder != null && it != 0 }
        ?: compareValues(!left.required, !right.required).takeIf { it != 0 }
        ?: compareValues(left.deadline == null, right.deadline == null).takeIf { it != 0 }
        ?: compareValues(left.deadline.orEmpty(), right.deadline.orEmpty()).takeIf { it != 0 }
        ?: compareValues(left.createdAtEpochMillis, right.createdAtEpochMillis).takeIf { it != 0 }
        ?: compareValues(left.taskId, right.taskId)
}
