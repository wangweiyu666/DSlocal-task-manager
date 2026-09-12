package com.ds.localtaskmanager.domain.execution

sealed interface ExecutionSpec {
    data object Normal : ExecutionSpec

    data class Counter(
        val action: CounterAction,
        val target: Int,
    ) : ExecutionSpec

    data class Timer(val targetSeconds: Int) : ExecutionSpec

    data object Information : ExecutionSpec
    data object Mood : ExecutionSpec
    data class Notice(val text: String) : ExecutionSpec
    data class Choice(val options: List<ChoiceOption>) : ExecutionSpec
    /** Ordered multi-step execution. The step definitions remain on the task/instance snapshot. */
    data object Steps : ExecutionSpec
}

enum class CounterAction(val protocolValue: Int) {
    SLIDER(1),
    CLICK(2),
}

data class TaskInstanceKey(
    val taskId: String,
    val occurrenceKey: String = "once",
)

sealed interface ExecutionState {
    data class Notice(val text: String) : ExecutionState
    data class Choice(val options: List<ChoiceOption>, val selectedOptionId: String?) : ExecutionState
    data class Mood(
        val rating: Int?,
        val text: String,
        val submittedAtEpochMillis: Long?,
    ) : ExecutionState

    data object Normal : ExecutionState

    data class Counter(
        val value: Int,
        val target: Int,
        val action: CounterAction,
    ) : ExecutionState

    data class Timer(
        val elapsedMillis: Long,
        val targetMillis: Long,
    ) : ExecutionState

    data class Information(
        val content: String,
        val submittedAtEpochMillis: Long?,
    ) : ExecutionState

    data class Steps(val items: List<StepState>) : ExecutionState
}

data class StepState(
    val stepId: String?,
    val position: Int,
    val name: String,
    val required: Boolean,
    val completed: Boolean,
    val executionKind: String,
    val executionAction: Int? = null,
    val executionTarget: Int? = null,
    val status: String = "PENDING",
    val counterValue: Int? = null,
    val elapsedMillis: Long? = null,
    val informationContent: String? = null,
    val moodRating: Int? = null,
    val moodText: String? = null,
    val executionConfigJson: String? = null,
    val conditionStepId: String? = null,
    val conditionOptionId: String? = null,
    val selectedOptionId: String? = null,
)

data class ChoiceOption(val id: String, val name: String, val points: Int)
data class StepCondition(val stepId: String, val optionId: String)

data class CompletionReadiness(
    val requiredStepsComplete: Boolean,
    val executionTargetReached: Boolean,
    val canComplete: Boolean,
)

enum class TaskOperationCode {
    INSTANCE_NOT_FOUND,
    INSTANCE_NOT_PENDING,
    STEP_NOT_FOUND,
    REQUIRED_STEP_INCOMPLETE,
    EXECUTION_KIND_MISMATCH,
    COUNTER_OUT_OF_RANGE,
    TIMER_OUT_OF_RANGE,
    INFORMATION_EMPTY,
    INFORMATION_TOO_LONG,
    MOOD_OUT_OF_RANGE,
    MOOD_TEXT_TOO_LONG,
    EXECUTION_TARGET_NOT_REACHED,
    COMPLETION_LEDGER_MISSING,
    INSTANCE_NOT_COMPLETED,
}
