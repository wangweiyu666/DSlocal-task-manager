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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val moodRating: Int? = null,
    val moodText: String = "",
    val moodSaveState: NoteSaveState = NoteSaveState.SAVED,
    val noteDraft: String = "",
    val noteSaveState: NoteSaveState = NoteSaveState.SAVED,
    val timerRunning: Boolean = false,
    val activeStepTimerId: String? = null,
    val stepSaveStates: Map<String, NoteSaveState> = emptyMap(),
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
    val monotonicNow: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val reminderReconciler: ReminderReconciler? = null,
    private val onCompletionCommitted: () -> Unit = {},
) : ViewModel() {
    private val mutableState = MutableStateFlow(ExecutionUiState())
    val state: StateFlow<ExecutionUiState> = mutableState.asStateFlow()

    private var noteSaveJob: Job? = null
    private var timerTickerJob: Job? = null
    private var savedNote = ""
    private var timerBase: ExecutionState.Timer? = null
    private var informationDirty = false
    private var moodDirty = false
    private var moodRevision = 0L
    private var moodSaveJob: Job? = null
    private val moodSaveMutex = Mutex()
    private var completing = false
    private data class StepDraft(val revision: Long, val save: suspend () -> Any)
    private val stepSaveMutex = Mutex()
    private val stepDraftRevisions = mutableMapOf<String, Long>()
    private val stepDebounceJobs = mutableMapOf<String, Job>()
    private val stepPendingSaves = mutableMapOf<String, StepDraft>()
    private val stepLocalDrafts = mutableMapOf<String, InstanceStepEntity>()
    private var stepWriterJob: Job? = null
    private var stepTimerJob: Job? = null
    private var stepTimerStartedAt: Long? = null
    private var stepTimerBaseMillis: Long = 0L

    init {
        refresh(loadNote = true)
    }

    fun refresh(loadNote: Boolean = false) {
        viewModelScope.launch { refreshNow(loadNote) }
    }

    fun setStep(position: Int, completed: Boolean) = perform {
        service.setStep(key, position, completed)
    }

    fun confirmStep(stepId: String) = perform {
        if (!stopStepTimerAndFlush() || !flushStepDrafts()) error("步骤草稿保存失败，请重试")
        service.confirmStep(key, stepId)
    }

    fun skipStep(position: Int) = perform {
        if (!stopStepTimerAndFlush() || !flushStepDrafts()) error("步骤草稿保存失败，请重试")
        service.skipStep(key, position)
    }

    fun undoStep(stepId: String) = perform {
        if (!stopStepTimerAndFlush() || !flushStepDrafts()) error("步骤草稿保存失败，请重试")
        service.undoStep(key, stepId)
    }

    private fun canEditCurrentStep(stepId: String): Boolean {
        val current = mutableState.value
        val step = current.steps.firstOrNull { it.stepId == stepId } ?: return false
        return current.instance?.status == "PENDING" && current.instance.executionKind == "STEPS" &&
            !current.working && !completing && step.stepStatus == "PENDING" &&
            current.steps.firstOrNull { it.stepStatus == "PENDING" }?.stepId == stepId
    }

    fun updateStepInformation(stepId: String, value: String) {
        if (!canEditCurrentStep(stepId)) return
        updateStepLocal(stepId) { it.copy(informationContent = value) }
        scheduleStepSave(stepId) { service.saveStepInformation(key, stepId, value) }
    }

    fun updateStepCounter(stepId: String, value: Int) {
        if (!canEditCurrentStep(stepId)) return
        updateStepLocal(stepId) { it.copy(counterValue = value) }
        enqueueStepSave(stepId) { service.setStepCounter(key, stepId, value) }
    }

    fun updateStepMood(stepId: String, rating: Int?, text: String) {
        if (rating != null && rating !in 1..5 || text.codePointCount(0, text.length) > 2000 || !canEditCurrentStep(stepId)) return
        val old = mutableState.value.steps.firstOrNull { it.stepId == stepId }
        updateStepLocal(stepId) { it.copy(moodRating = rating, moodText = text) }
        val save: suspend () -> Any = { service.saveStepMood(key, stepId, rating, text) }
        if (old?.moodRating != rating) enqueueStepSave(stepId, save) else scheduleStepSave(stepId, save)
    }

    fun startStepTimer(stepId: String) {
        if (!canEditCurrentStep(stepId)) return
        if (stepTimerJob != null || mutableState.value.working) return
        val step = mutableState.value.steps.firstOrNull { it.stepId == stepId } ?: return
        stepTimerBaseMillis = step.elapsedMillis ?: 0L
        stepTimerStartedAt = monotonicNow()
        mutableState.value = mutableState.value.copy(activeStepTimerId = stepId)
        stepTimerJob = viewModelScope.launch {
            while (true) {
            val elapsed = currentStepElapsed()
                val target = mutableState.value.steps.firstOrNull { it.stepId == stepId }?.executionTarget?.times(1_000L)
                updateStepLocal(stepId) { it.copy(elapsedMillis = target?.let { limit -> minOf(elapsed, limit) } ?: elapsed) }
                enqueueStepSave(stepId) { service.setStepTimer(key, stepId, elapsed) }
                if (step.executionTarget?.let { elapsed >= it * 1_000L } == true) break
                delay(TIMER_TICK_MILLIS)
            }
            stopStepTimerAndFlush(stepId, cancelTicker = false)
        }
    }

    fun pauseStepTimer(stepId: String) {
        viewModelScope.launch { stopStepTimerAndFlush(stepId) }
    }

    fun retryStepSave(stepId: String) {
        if (stepPendingSaves.containsKey(stepId)) {
            mutableState.value = mutableState.value.copy(stepSaveStates = mutableState.value.stepSaveStates + (stepId to NoteSaveState.SAVING))
            ensureStepWriter()
        }
    }

    private fun currentStepElapsed(): Long = stepTimerBaseMillis +
        (monotonicNow() - (stepTimerStartedAt ?: monotonicNow()))

    private suspend fun stopStepTimerAndFlush(expectedStepId: String? = null, cancelTicker: Boolean = true): Boolean {
        val active = mutableState.value.activeStepTimerId
        if (active != null && (expectedStepId == null || expectedStepId == active)) {
            val elapsed = currentStepElapsed()
            if (cancelTicker) stepTimerJob?.cancel()
            stepTimerJob = null
            stepTimerStartedAt = null
            val target = mutableState.value.steps.firstOrNull { it.stepId == active }?.executionTarget?.times(1_000L)
            updateStepLocal(active) { it.copy(elapsedMillis = target?.let { limit -> minOf(elapsed, limit) } ?: elapsed) }
            enqueueStepSave(active) { service.setStepTimer(key, active, elapsed) }
            mutableState.value = mutableState.value.copy(activeStepTimerId = null)
        }
        return flushStepDrafts()
    }

    private fun updateStepLocal(stepId: String, transform: (InstanceStepEntity) -> InstanceStepEntity) {
        val updated = mutableState.value.steps.map { if (it.stepId == stepId) transform(it) else it }
        val local = updated.firstOrNull { it.stepId == stepId }
        if (local != null) stepLocalDrafts[stepId] = local
        mutableState.value = mutableState.value.copy(steps = updated)
    }

    private fun scheduleStepSave(stepId: String, save: suspend () -> Any) {
        val revision = (stepDraftRevisions[stepId] ?: 0L) + 1L
        stepDraftRevisions[stepId] = revision
        stepPendingSaves[stepId] = StepDraft(revision, save)
        mutableState.value = mutableState.value.copy(stepSaveStates = mutableState.value.stepSaveStates + (stepId to NoteSaveState.SAVING))
        stepDebounceJobs[stepId]?.cancel()
        stepDebounceJobs[stepId] = viewModelScope.launch {
            delay(NOTE_SAVE_DEBOUNCE_MILLIS)
            stepDebounceJobs.remove(stepId)
            ensureStepWriter()
        }
    }

    private fun enqueueStepSave(stepId: String, save: suspend () -> Any) {
        val revision = (stepDraftRevisions[stepId] ?: 0L) + 1L
        stepDraftRevisions[stepId] = revision
        stepPendingSaves[stepId] = StepDraft(revision, save)
        mutableState.value = mutableState.value.copy(stepSaveStates = mutableState.value.stepSaveStates + (stepId to NoteSaveState.SAVING))
        ensureStepWriter()
    }

    private fun ensureStepWriter() {
        if (stepWriterJob?.isActive != true) stepWriterJob = viewModelScope.launch { writeStepDrafts() }
    }

    private suspend fun writeStepDrafts() {
        while (true) {
            val entry = stepSaveMutex.withLock { stepPendingSaves.entries.firstOrNull()?.toPair() } ?: return
            val (stepId, draft) = entry
            var attemptedRevision = draft.revision
            try {
                stepSaveMutex.withLock {
                    val latest = stepPendingSaves[stepId] ?: return@withLock
                    attemptedRevision = latest.revision
                    latest.save()
                    if (stepPendingSaves[stepId]?.revision == latest.revision) {
                        stepPendingSaves.remove(stepId)
                        stepLocalDrafts.remove(stepId)
                        mutableState.value = mutableState.value.copy(stepSaveStates = mutableState.value.stepSaveStates + (stepId to NoteSaveState.SAVED))
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val newer = stepSaveMutex.withLock { stepPendingSaves[stepId]?.revision != attemptedRevision }
                if (newer) continue
                mutableState.value = mutableState.value.copy(stepSaveStates = mutableState.value.stepSaveStates + (stepId to NoteSaveState.ERROR), errorMessage = error.message ?: "步骤保存失败，请重试")
                return
            }
        }
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

    fun prepareInformationForShare(onReady: (String) -> Unit) {
        val draft = mutableState.value.informationDraft
        val pending = mutableState.value.instance?.status == com.ds.localtaskmanager.domain.TaskStatus.PENDING.name
        if (!pending) {
            if (draft.trim().isNotEmpty()) onReady(draft.trim())
            return
        }
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(working = true, errorMessage = null)
            runCatching { service.saveInformationDraft(key, draft) }
                .onSuccess { saved ->
                    informationDirty = false
                    refreshNow()
                    onReady(saved.content)
                }
                .onFailure(::showError)
        }
    }

    fun updateMoodRating(rating: Int) {
        if (rating !in 1..5 || !canEditMood()) return
        mutableState.value = mutableState.value.copy(moodRating = rating)
        scheduleMoodSave(0)
    }

    fun updateMoodText(text: String) {
        if (!canEditMood()) return
        mutableState.value = mutableState.value.copy(moodText = text)
        scheduleMoodSave(500)
    }

    private fun canEditMood() = mutableState.value.instance?.executionKind == "MOOD" &&
        mutableState.value.instance?.status == "PENDING" && !mutableState.value.working && !completing

    private fun scheduleMoodSave(delayMillis: Long) {
        moodDirty = true
        moodRevision++
        mutableState.value = mutableState.value.copy(moodSaveState = NoteSaveState.SAVING, errorMessage = null)
        updateMoodReadiness()
        moodSaveJob?.cancel()
        moodSaveJob = viewModelScope.launch {
            delay(delayMillis)
            saveMoodNow()
        }
    }

    private fun updateMoodReadiness() {
        val state = mutableState.value
        if (state.instance?.executionKind != "MOOD") return
        val valid = state.moodRating in 1..5 && state.moodText.codePointCount(0, state.moodText.length) <= 2000
        mutableState.value = state.copy(executionTargetReached = valid,
            canComplete = state.instance.status == "PENDING" && state.requiredStepsComplete && valid)
    }

    private suspend fun saveMoodNow(): Boolean = moodSaveMutex.withLock {
        // Back/background flushes may overlap another edit; only finish once the latest draft is saved.
        while (moodDirty) {
            val revision = moodRevision
            val snapshot = mutableState.value
            mutableState.value = mutableState.value.copy(moodSaveState = NoteSaveState.SAVING)
            try {
                service.saveMoodDraft(key, snapshot.moodRating, snapshot.moodText)
                if (revision == moodRevision) {
                    moodDirty = false
                    mutableState.value = mutableState.value.copy(moodSaveState = NoteSaveState.SAVED)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (revision == moodRevision) mutableState.value = mutableState.value.copy(
                    moodSaveState = NoteSaveState.ERROR, errorMessage = error.message ?: "心情保存失败，请重试",
                )
                return@withLock false
            }
        }
        true
    }

    fun retryMoodSave() {
        moodSaveJob?.cancel()
        moodSaveJob = viewModelScope.launch { saveMoodNow() }
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
        viewModelScope.launch {
            if (timer.isRunning) pauseTimer()
            stopStepTimerAndFlush()
            if (moodDirty) saveMoodNow()
            flushStepDrafts()
        }
    }

    fun complete() {
        if (mutableState.value.working || completing) return
        completing = true
        mutableState.value = mutableState.value.copy(working = true, errorMessage = null)
        moodSaveJob?.cancel()
        viewModelScope.launch {
            try {
                runCatching {
                    timerTickerJob?.cancel()
                    timer.pause()
                    if (!stopStepTimerAndFlush() || !flushStepDrafts()) {
                        showError(IllegalStateException("步骤草稿保存失败，请重试"))
                        return@launch
                    }
                    timerBase = null
                    if (!saveMoodNow()) return@launch
                    service.complete(key)
                    reminderReconciler?.reconcileAll()
                }.onSuccess {
                    val message = mutableState.value.instance?.completionMessage.orEmpty()
                        .ifBlank { "任务已完成" }
                    refreshNow()
                    mutableState.value = mutableState.value.copy(completionFeedback = message)
                    onCompletionCommitted()
                }.onFailure(::showError)
            } finally {
                completing = false
                mutableState.value = mutableState.value.copy(working = false)
            }
        }
    }

    fun undoCompletion() = perform {
        service.undoCompletion(key)
        reminderReconciler?.reconcileAll()
        onCompletionCommitted()
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
        moodSaveJob?.cancel()
        viewModelScope.launch {
            if (saveMoodNow() && stopStepTimerAndFlush() && flushStepDrafts()) {
                if (mutableState.value.noteDraft == savedNote || saveNoteNow()) onSaved()
            }
        }
    }

    private suspend fun flushStepDrafts(): Boolean {
        stepDebounceJobs.values.toList().forEach { it.cancel() }
        stepDebounceJobs.clear()
        ensureStepWriter()
        stepWriterJob?.join()
        if (stepPendingSaves.isNotEmpty()) {
            ensureStepWriter()
            stepWriterJob?.join()
        }
        return stepPendingSaves.isEmpty()
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
        val refreshMoodRevision = moodRevision
        val refreshMoodWasDirty = moodDirty
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
                working = completing,
                instance = result.instance,
                steps = result.steps.map { remote ->
                    if (stepPendingSaves.containsKey(remote.stepId)) stepLocalDrafts[remote.stepId] ?: remote else remote
                },
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
                moodRating = if (moodDirty || refreshMoodWasDirty || refreshMoodRevision != moodRevision) mutableState.value.moodRating else (result.execution as? ExecutionState.Mood)?.rating,
                moodText = if (moodDirty || refreshMoodWasDirty || refreshMoodRevision != moodRevision) mutableState.value.moodText else (result.execution as? ExecutionState.Mood)?.text.orEmpty(),
                noteDraft = result.note ?: mutableState.value.noteDraft,
                noteSaveState = if (result.note != null) NoteSaveState.SAVED else mutableState.value.noteSaveState,
                timerRunning = timer.isRunning,
                errorCode = null,
                errorMessage = null,
            )
            updateMoodReadiness()
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
        stepTimerJob?.cancel()
        stepDebounceJobs.values.forEach { it.cancel() }
        viewModelScope.launch {
            stopStepTimerAndFlush()
            flushStepDrafts()
            timer.pause()
        }
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
    private val onCompletionCommitted: () -> Unit = {},
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
            onCompletionCommitted = onCompletionCommitted,
        ) as T
    }
}

private fun operationMessage(code: TaskOperationCode?): String? = when (code) {
    TaskOperationCode.REQUIRED_STEP_INCOMPLETE -> "请先完成所有必需步骤"
    TaskOperationCode.EXECUTION_TARGET_NOT_REACHED -> "请先达成执行目标"
    TaskOperationCode.INFORMATION_EMPTY -> "告知正文不能为空"
    TaskOperationCode.INFORMATION_TOO_LONG -> "告知正文不能超过 2000 个字符"
    TaskOperationCode.MOOD_OUT_OF_RANGE -> "请选择五档心情之一"
    TaskOperationCode.MOOD_TEXT_TOO_LONG -> "感受不能超过 2000 个字符"
    TaskOperationCode.INSTANCE_NOT_PENDING -> "当前状态不能修改执行数据"
    TaskOperationCode.INSTANCE_NOT_COMPLETED -> "当前任务尚未完成"
    TaskOperationCode.INSTANCE_NOT_FOUND -> "任务不存在"
    null -> null
    else -> "操作未完成，请重试"
}
