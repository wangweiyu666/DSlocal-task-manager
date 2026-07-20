package com.ds.localtaskmanager.ui.execution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ds.localtaskmanager.data.InstanceStepEntity
import com.ds.localtaskmanager.data.TaskExecutionService
import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.data.TaskNoteService
import com.ds.localtaskmanager.data.TaskOperationException
import com.ds.localtaskmanager.data.TaskRepository
import com.ds.localtaskmanager.domain.execution.ExecutionState
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import com.ds.localtaskmanager.reminder.ReminderReconciler
import com.ds.localtaskmanager.domain.execution.TaskOperationCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class NoteSaveState {
    SAVED,
    SAVING,
    ERROR,
}

data class ExecutionUiState(
    val loading: Boolean = true,
    val working: Boolean = false,
    val instance: TaskInstanceEntity? = null,
    val steps: List<InstanceStepEntity> = emptyList(),
    val execution: ExecutionState? = null,
    val requiredStepsComplete: Boolean = false,
    val executionTargetReached: Boolean = false,
    val canComplete: Boolean = false,
    val informationDraft: String = "",
    val noteDraft: String = "",
    val noteSaveState: NoteSaveState = NoteSaveState.SAVED,
    val timerRunning: Boolean = false,
    val completionFeedback: String? = null,
    val errorCode: TaskOperationCode? = null,
    val errorMessage: String? = null,
)

class ExecutionViewModel(
    private val key: TaskInstanceKey,
    private val service: TaskExecutionService,
    private val repository: TaskRepository,
    private val noteService: TaskNoteService,
    private val timer: TimerSessionController = TimerSessionController(service),
    private val reminderReconciler: ReminderReconciler? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ExecutionUiState())
    val state: StateFlow<ExecutionUiState> = mutableState.asStateFlow()

    private var noteSaveJob: Job? = null
    private var timerTickerJob: Job? = null
    private var savedNote = ""
    private var timerBase: ExecutionState.Timer? = null
    private var informationDirty = false

    init {
        refresh(loadNote = true)
    }

    fun refresh(loadNote: Boolean = false) {
        viewModelScope.launch { refreshNow(loadNote) }
    }

    fun setStep(position: Int, completed: Boolean) = perform {
        service.setStep(key, position, completed)
    }

    fun setCounter(value: Int) = perform {
        service.setCounter(key, value)
    }

    fun updateInformationDraft(value: String) {
        informationDirty = true
        mutableState.value = mutableState.value.copy(informationDraft = value, errorMessage = null)
    }

    fun saveInformationDraft() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(working = true, errorMessage = null)
            runCatching { service.saveInformationDraft(key, mutableState.value.informationDraft) }
                .onSuccess {
                    informationDirty = false
                    refreshNow()
                }
                .onFailure(::showError)
        }
    }

    fun startTimer() {
        if (mutableState.value.timerRunning) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(working = true, errorMessage = null)
            runCatching { timer.start(key) }
                .onSuccess { base ->
                    timerBase = base
                    mutableState.value = mutableState.value.copy(
                        working = false,
                        execution = base,
                        timerRunning = timer.isRunning,
                    )
                    if (timer.isRunning) startTimerTicker()
                }
                .onFailure(::showError)
        }
    }

    fun pauseTimer() {
        timerTickerJob?.cancel()
        timerTickerJob = null
        viewModelScope.launch {
            runCatching { timer.pause() }
                .onSuccess {
                    timerBase = null
                    mutableState.value = mutableState.value.copy(timerRunning = false)
                    refreshNow()
                }
                .onFailure(::showError)
        }
    }

    fun onForegroundLost() {
        if (timer.isRunning) pauseTimer()
    }

    fun complete() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(working = true, errorMessage = null)
            runCatching {
                timerTickerJob?.cancel()
                timer.pause()
                timerBase = null
                service.complete(key)
                reminderReconciler?.reconcileAll()
            }.onSuccess {
                val message = mutableState.value.instance?.completionMessage.orEmpty()
                    .ifBlank { "任务已完成" }
                refreshNow()
                mutableState.value = mutableState.value.copy(completionFeedback = message)
            }.onFailure(::showError)
        }
    }

    fun undoCompletion() = perform {
        service.undoCompletion(key)
        reminderReconciler?.reconcileAll()
    }

    fun clearCompletionFeedback() {
        mutableState.value = mutableState.value.copy(completionFeedback = null)
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(errorCode = null, errorMessage = null)
    }

    fun updateNoteDraft(value: String) {
        mutableState.value = mutableState.value.copy(
            noteDraft = value,
            noteSaveState = if (value == savedNote) NoteSaveState.SAVED else NoteSaveState.SAVING,
        )
        noteSaveJob?.cancel()
        if (value != savedNote) {
            noteSaveJob = viewModelScope.launch {
                delay(NOTE_SAVE_DEBOUNCE_MILLIS)
                saveNoteNow()
            }
        }
    }

    fun flushNote(onSaved: () -> Unit) {
        noteSaveJob?.cancel()
        noteSaveJob = null
        if (mutableState.value.noteDraft == savedNote) {
            onSaved()
            return
        }
        viewModelScope.launch {
            if (saveNoteNow()) onSaved()
        }
    }

    private fun perform(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(working = true, errorMessage = null)
            runCatching { block() }
                .onSuccess { refreshNow() }
                .onFailure(::showError)
        }
    }

    private suspend fun refreshNow(loadNote: Boolean = false) {
        runCatching {
            val readiness = service.getCompletionReadiness(key)
            val execution = service.getExecutionState(key)
            val instance = repository.getTask(key) ?: error("任务不存在")
            val steps = repository.getSteps(key)
            val note = if (loadNote) noteService.getNote(key) else null
            RefreshResult(instance, steps, execution, readiness, note)
        }.onSuccess { result ->
            if (result.note != null) savedNote = result.note
            mutableState.value = mutableState.value.copy(
                loading = false,
                working = false,
                instance = result.instance,
                steps = result.steps,
                execution = result.execution,
                requiredStepsComplete = result.readiness.requiredStepsComplete,
                executionTargetReached = result.readiness.executionTargetReached,
                canComplete = result.readiness.canComplete,
                informationDraft = if (informationDirty) {
                    mutableState.value.informationDraft
                } else {
                    (result.execution as? ExecutionState.Information)?.content
                        ?: mutableState.value.informationDraft
                },
                noteDraft = result.note ?: mutableState.value.noteDraft,
                noteSaveState = if (result.note != null) NoteSaveState.SAVED else mutableState.value.noteSaveState,
                timerRunning = timer.isRunning,
                errorCode = null,
                errorMessage = null,
            )
        }.onFailure(::showError)
    }

    private fun startTimerTicker() {
        timerTickerJob?.cancel()
        timerTickerJob = viewModelScope.launch {
            while (timer.isRunning) {
                val base = timerBase ?: break
                val preview = timer.preview(base)
                mutableState.value = mutableState.value.copy(execution = preview)
                if (preview.elapsedMillis >= preview.targetMillis) {
                    pauseTimer()
                    break
                }
                delay(TIMER_TICK_MILLIS)
            }
        }
    }

    private suspend fun saveNoteNow(): Boolean = runCatching {
        val content = mutableState.value.noteDraft
        mutableState.value = mutableState.value.copy(noteSaveState = NoteSaveState.SAVING)
        noteService.saveNote(key, content)
        savedNote = content
        mutableState.value = mutableState.value.copy(noteSaveState = NoteSaveState.SAVED)
    }.fold(
        onSuccess = { true },
        onFailure = { error ->
            mutableState.value = mutableState.value.copy(
                noteSaveState = NoteSaveState.ERROR,
                errorMessage = error.message ?: "备注保存失败",
            )
            false
        },
    )

    private fun showError(error: Throwable) {
        val operation = error as? TaskOperationException
        mutableState.value = mutableState.value.copy(
            loading = false,
            working = false,
            timerRunning = timer.isRunning,
            errorCode = operation?.code,
            errorMessage = operationMessage(operation?.code) ?: error.message ?: "操作失败",
        )
    }

    override fun onCleared() {
        timerTickerJob?.cancel()
        viewModelScope.launch { timer.pause() }
        super.onCleared()
    }

    private data class RefreshResult(
        val instance: TaskInstanceEntity,
        val steps: List<InstanceStepEntity>,
        val execution: ExecutionState,
        val readiness: com.ds.localtaskmanager.domain.execution.CompletionReadiness,
        val note: String?,
    )

    private companion object {
        const val NOTE_SAVE_DEBOUNCE_MILLIS = 500L
        const val TIMER_TICK_MILLIS = 250L
    }
}

class ExecutionViewModelFactory(
    private val key: TaskInstanceKey,
    private val service: TaskExecutionService,
    private val repository: TaskRepository,
    private val noteService: TaskNoteService,
    private val reminderReconciler: ReminderReconciler? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ExecutionViewModel::class.java))
        return ExecutionViewModel(
            key,
            service,
            repository,
            noteService,
            reminderReconciler = reminderReconciler,
        ) as T
    }
}

private fun operationMessage(code: TaskOperationCode?): String? = when (code) {
    TaskOperationCode.REQUIRED_STEP_INCOMPLETE -> "请先完成所有必需步骤"
    TaskOperationCode.EXECUTION_TARGET_NOT_REACHED -> "请先达成执行目标"
    TaskOperationCode.INFORMATION_EMPTY -> "告知正文不能为空"
    TaskOperationCode.INFORMATION_TOO_LONG -> "告知正文不能超过 2000 个字符"
    TaskOperationCode.INSTANCE_NOT_PENDING -> "当前状态不能修改执行数据"
    TaskOperationCode.INSTANCE_NOT_COMPLETED -> "当前任务尚未完成"
    TaskOperationCode.INSTANCE_NOT_FOUND -> "任务不存在"
    null -> null
    else -> "操作未完成，请重试"
}
