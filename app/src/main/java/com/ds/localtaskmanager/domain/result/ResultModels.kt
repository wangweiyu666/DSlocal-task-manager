package com.ds.localtaskmanager.domain.result

enum class DailyResultStatus {
    COMPLETED,
    INCOMPLETE,
    IN_PROGRESS,
    OPTIONAL_ONLY,
}

data class ResultTaskItem(
    val taskId: String,
    val occurrenceKey: String,
    val taskDate: String,
    val groupId: String?,
    val required: Boolean,
    val status: String,
    val actualPoints: Int,
    val groupCompleteMessage: String?,
    val groupIncompleteMessage: String?,
)

data class GroupDailyResult(
    val groupId: String?,
    val status: DailyResultStatus?,
    val points: Int,
    val requiredCompleted: Int,
    val requiredMissed: Int,
    val requiredPending: Int,
    val optionalCount: Int,
    val message: String?,
    internal val fingerprint: String,
)

data class GlobalDailyResult(
    val taskDate: String,
    val status: DailyResultStatus?,
    val totalPoints: Int,
    val requiredCompleted: Int,
    val requiredMissed: Int,
    val requiredPending: Int,
    val optionalCount: Int,
    internal val fingerprint: String,
)

data class DailyResultSnapshot(
    val taskDate: String,
    val global: GlobalDailyResult?,
    val groups: List<GroupDailyResult>,
    val tasks: List<ResultTaskItem>,
)

data class DailyResultSummary(
    val taskDate: String,
    val status: DailyResultStatus,
    val points: Int,
    val requiredCompleted: Int,
    val requiredMissed: Int,
)
