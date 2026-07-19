package com.ds.localtaskmanager.domain.execution

sealed interface ExecutionSpec {
    data object Normal : ExecutionSpec

    data class Counter(
        val action: CounterAction,
        val target: Int,
    ) : ExecutionSpec

    data class Timer(val targetSeconds: Int) : ExecutionSpec

    data object Information : ExecutionSpec
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
}

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
    EXECUTION_TARGET_NOT_REACHED,
    COMPLETION_LEDGER_MISSING,
    INSTANCE_NOT_COMPLETED,
}
