package com.ds.localtaskmanager.ui.execution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ds.localtaskmanager.data.TaskExecutionService
import com.ds.localtaskmanager.data.TaskOperationException
import com.ds.localtaskmanager.domain.execution.ExecutionState
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import com.ds.localtaskmanager.domain.execution.TaskOperationCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ExecutionUiState(
    val loading: Boolean = true,
    val execution: ExecutionState? = null,
    val requiredStepsComplete: Boolean = false,
    val executionTargetReached: Boolean = false,
    val canComplete: Boolean = false,
    val errorCode: TaskOperationCode? = null,
)

class ExecutionViewModel(
    private val key: TaskInstanceKey,
    private val service: TaskExecutionService,
    private val timer: TimerSessionController = TimerSessionController(service),
) : ViewModel() {
    private val mutableState = MutableStateFlow(ExecutionUiState())
    val state: StateFlow<ExecutionUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = perform { service.getExecutionState(key) }

    fun setCounter(value: Int) = perform { service.setCounter(key, value) }

    fun saveInformationDraft(content: String) = perform {
        service.saveInformationDraft(key, content)
    }

    fun startTimer() = perform { timer.start(key) }

    fun pauseTimer() = perform { timer.pause() ?: service.getExecutionState(key) }

    fun onForegroundLost() = pauseTimer()

    fun complete() {
        viewModelScope.launch {
            runCatching {
                timer.pause()
                service.complete(key)
                service.getExecutionState(key)
            }.fold({ showExecution(it) }, ::showError)
        }
    }

    private fun perform(block: suspend () -> ExecutionState) {
        viewModelScope.launch {
            runCatching { block() }.fold({ showExecution(it) }, ::showError)
        }
    }

    private suspend fun showExecution(execution: ExecutionState) {
        val readiness = service.getCompletionReadiness(key)
        mutableState.value = ExecutionUiState(
            loading = false,
            execution = execution,
            requiredStepsComplete = readiness.requiredStepsComplete,
            executionTargetReached = readiness.executionTargetReached,
            canComplete = readiness.canComplete,
        )
    }

    private fun showError(error: Throwable) {
        mutableState.value = mutableState.value.copy(
            loading = false,
            errorCode = (error as? TaskOperationException)?.code,
        )
    }

    override fun onCleared() {
        viewModelScope.launch { timer.pause() }
        super.onCleared()
    }
}
