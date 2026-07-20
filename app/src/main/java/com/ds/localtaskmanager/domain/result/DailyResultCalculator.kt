package com.ds.localtaskmanager.domain.result

import com.ds.localtaskmanager.domain.TaskStatus

class DailyResultCalculator {
    fun calculate(taskDate: String, source: List<ResultTaskItem>): DailyResultSnapshot? {
        val tasks = source
            .filter { it.status != TaskStatus.CANCELLED.name && it.status != TaskStatus.NOT_STARTED.name }
            .sortedWith(compareByDescending<ResultTaskItem> { it.required }.thenBy { it.taskId }.thenBy { it.occurrenceKey })
        if (tasks.isEmpty()) return null

        val groups = tasks.groupBy { it.groupId }.map { (groupId, groupTasks) ->
            val status = statusOf(groupTasks)
            GroupDailyResult(
                groupId = groupId,
                status = status,
                points = groupTasks.sumOf { it.actualPoints },
                requiredCompleted = groupTasks.count { it.required && it.status == TaskStatus.COMPLETED.name },
                requiredMissed = groupTasks.count { it.required && it.status == TaskStatus.MISSED.name },
                requiredPending = groupTasks.count { it.required && it.status == TaskStatus.PENDING.name },
                optionalCount = groupTasks.count { !it.required },
                message = when (status) {
                    DailyResultStatus.COMPLETED -> groupTasks.firstNotNullOfOrNull { it.groupCompleteMessage }
                    DailyResultStatus.INCOMPLETE -> groupTasks.firstNotNullOfOrNull { it.groupIncompleteMessage }
                    else -> null
                },
                fingerprint = fingerprint(groupTasks),
            )
        }.sortedWith(compareBy<GroupDailyResult> { it.groupId == null }.thenBy { it.groupId })

        val globalStatus = statusOf(tasks)
        val global = GlobalDailyResult(
            taskDate = taskDate,
            status = globalStatus,
            totalPoints = tasks.sumOf { it.actualPoints },
            requiredCompleted = tasks.count { it.required && it.status == TaskStatus.COMPLETED.name },
            requiredMissed = tasks.count { it.required && it.status == TaskStatus.MISSED.name },
            requiredPending = tasks.count { it.required && it.status == TaskStatus.PENDING.name },
            optionalCount = tasks.count { !it.required },
            fingerprint = fingerprint(tasks),
        )
        return DailyResultSnapshot(taskDate, global, groups, tasks)
    }

    private fun statusOf(tasks: List<ResultTaskItem>): DailyResultStatus = when {
        tasks.any { it.required && it.status == TaskStatus.MISSED.name } -> DailyResultStatus.INCOMPLETE
        tasks.any { it.required && it.status == TaskStatus.PENDING.name } -> DailyResultStatus.IN_PROGRESS
        tasks.any { it.required } -> DailyResultStatus.COMPLETED
        else -> DailyResultStatus.OPTIONAL_ONLY
    }

    private fun fingerprint(tasks: List<ResultTaskItem>): String = tasks
        .sortedWith(compareBy<ResultTaskItem> { it.taskId }.thenBy { it.occurrenceKey })
        .joinToString("|") { "${it.taskId}:${it.occurrenceKey}:${it.groupId}:${it.required}:${it.status}" }
}
