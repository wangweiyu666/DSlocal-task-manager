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
    val taskName: String = taskId,
    val sortOrder: Int? = null,
    val deadline: String? = null,
    val createdAtEpochMillis: Long = 0,
    val groupName: String? = null,
    val groupCreatedAtEpochMillis: Long? = null,
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
    val groupName: String? = null,
    val groupCreatedAtEpochMillis: Long? = null,
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
    val domName: String? = null,
)

data class DailyResultSummary(
    val taskDate: String,
    val status: DailyResultStatus,
    val points: Int,
    val requiredCompleted: Int,
    val requiredMissed: Int,
)
