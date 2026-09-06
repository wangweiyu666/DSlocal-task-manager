package com.ds.localtaskmanager.ui.execution

import com.ds.localtaskmanager.data.ActionLogEntity
import com.ds.localtaskmanager.data.InstanceStepEntity
import com.ds.localtaskmanager.data.PointsLedgerEntity
import com.ds.localtaskmanager.data.TaskExecutionService
import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.data.TaskNoteService
import com.ds.localtaskmanager.data.TaskRepository
import com.ds.localtaskmanager.data.TodayTask
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.execution.CompletionReadiness
import com.ds.localtaskmanager.domain.execution.ExecutionState
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class W22ExecutionViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `note is debounced and flush saves immediately`() = runTest(dispatcher) {
        val notes = FakeNoteService()
        val viewModel = ExecutionViewModel(KEY, FakeExecutionService(), FakeRepository(), notes)
        runCurrent()

        viewModel.updateNoteDraft("first")
        advanceTimeBy(499)
        runCurrent()
        assertEquals(emptyList<String>(), notes.saved)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("first"), notes.saved)
        assertEquals(NoteSaveState.SAVED, viewModel.state.value.noteSaveState)

        var left = false
        viewModel.updateNoteDraft("leaving")
        viewModel.flushNote { left = true }
        runCurrent()
        assertEquals(listOf("first", "leaving"), notes.saved)
        assertTrue(left)
    }

    @Test
    fun `successful completion requests connected synchronization`() = runTest(dispatcher) {
        var synchronizationRequests = 0
        val viewModel = ExecutionViewModel(
            KEY,
            FakeExecutionService(),
            FakeRepository(),
            FakeNoteService(),
            onCompletionCommitted = { synchronizationRequests += 1 },
        )
        runCurrent()

        viewModel.complete()
        runCurrent()

        assertEquals(1, synchronizationRequests)
        viewModel.undoCompletion()
        runCurrent()
        assertEquals(2, synchronizationRequests)
    }

    @Test
    fun `mood text is debounced and latest edit is flushed before completion`() = runTest(dispatcher) {
        val execution = FakeExecutionService(ExecutionState.Mood(null, "", null))
        val viewModel = ExecutionViewModel(KEY, execution, FakeRepository(MOOD_INSTANCE), FakeNoteService())
        runCurrent()

        viewModel.updateMoodRating(4)
        runCurrent()
        assertEquals(listOf(4 to ""), execution.moodSaved)
        viewModel.updateMoodText("first")
        advanceTimeBy(250)
        runCurrent()
        viewModel.updateMoodText("last")
        advanceTimeBy(499)
        runCurrent()
        assertEquals(listOf(4 to ""), execution.moodSaved)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(4 to "", 4 to "last"), execution.moodSaved)

        var flushed = false
        viewModel.updateMoodText("before complete")
        viewModel.flushNote { flushed = true }
        runCurrent()
        assertEquals(true, flushed)
        assertEquals(listOf(4 to "", 4 to "last", 4 to "before complete"), execution.moodSaved)
    }

    @Test
    fun `mood save failure prevents completion and exposes error state`() = runTest(dispatcher) {
        val execution = FakeExecutionService(ExecutionState.Mood(null, "", null), failMoodSave = true)
        val viewModel = ExecutionViewModel(KEY, execution, FakeRepository(MOOD_INSTANCE), FakeNoteService())
        runCurrent()

        viewModel.updateMoodRating(3)
        viewModel.updateMoodText("will fail")
        viewModel.complete()
        runCurrent()

        assertEquals(emptyList<TaskInstanceKey>(), execution.completed)
        assertEquals(NoteSaveState.ERROR, viewModel.state.value.moodSaveState)
    }

    @Test
    fun `step information waits 500ms and confirmation uses the latest draft`() = runTest(dispatcher) {
        val step = InstanceStepEntity(KEY.taskId, KEY.occurrenceKey, 0, "填写", true, false, 1, "step-00000000001", "INFORMATION")
        val execution = FakeExecutionService(ExecutionState.Steps(listOf(com.ds.localtaskmanager.domain.execution.StepState("step-00000000001", 0, "填写", true, false, "INFORMATION"))))
        val service = execution
        val viewModel = ExecutionViewModel(KEY, service, FakeRepository(STEPS_INSTANCE, listOf(step)), FakeNoteService())
        runCurrent()
        viewModel.updateStepInformation("step-00000000001", "最新答案")
        advanceTimeBy(499)
        runCurrent()
        assertEquals(emptyList<String>(), service.stepInformationSaved)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("最新答案"), service.stepInformationSaved)
        viewModel.confirmStep("step-00000000001")
        runCurrent()
        assertEquals(listOf("step-00000000001"), service.confirmedSteps)
    }

    @Test
    fun `counter writes are serialized and latest value is retained`() = runTest(dispatcher) {
        val step = InstanceStepEntity(KEY.taskId, KEY.occurrenceKey, 0, "计数", true, false, 1, "step-counter", "COUNTER", executionTarget = 3)
        val service = FakeExecutionService(ExecutionState.Steps(listOf(com.ds.localtaskmanager.domain.execution.StepState("step-counter", 0, "计数", true, false, "COUNTER"))))
        val viewModel = ExecutionViewModel(KEY, service, FakeRepository(STEPS_INSTANCE, listOf(step)), FakeNoteService())
        runCurrent()
        viewModel.updateStepCounter("step-counter", 1)
        runCurrent()
        viewModel.updateStepCounter("step-counter", 2)
        runCurrent()
        assertEquals(listOf(1, 2), service.stepCounters)
        assertEquals(NoteSaveState.SAVED, viewModel.state.value.stepSaveStates["step-counter"])
    }

    @Test
    fun `timer failure remains pending and pause retries the same absolute draft`() = runTest(dispatcher) {
        val step = InstanceStepEntity(KEY.taskId, KEY.occurrenceKey, 0, "计时", true, false, 1, "step-timer", "TIMER", executionTarget = 60, elapsedMillis = 1_234)
        val service = FakeExecutionService(ExecutionState.Steps(listOf(com.ds.localtaskmanager.domain.execution.StepState("step-timer", 0, "计时", true, false, "TIMER"))), failFirstStepTimer = true)
        var clock = 1_000L
        val viewModel = ExecutionViewModel(KEY, service, FakeRepository(STEPS_INSTANCE, listOf(step)), FakeNoteService(), monotonicNow = { clock })
        runCurrent()
        viewModel.startStepTimer("step-timer")
        runCurrent()
        clock = 1_500L
        viewModel.pauseStepTimer("step-timer")
        runCurrent()
        assertTrue(service.stepTimerAttempts >= 2)
        assertEquals(NoteSaveState.SAVED, viewModel.state.value.stepSaveStates["step-timer"])
        assertTrue(service.stepTimerValues.isNotEmpty())
        assertEquals(listOf(1_734L), service.stepTimerValues.distinct())
    }

    @Test
    fun `flush waits for blocked old write then persists newest revision before confirm`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val step = InstanceStepEntity(KEY.taskId, KEY.occurrenceKey, 0, "填写", true, false, 1, "step-race", "INFORMATION")
        val service = FakeExecutionService(
            ExecutionState.Steps(listOf(com.ds.localtaskmanager.domain.execution.StepState("step-race", 0, "填写", true, false, "INFORMATION"))),
            stepInformationGate = gate,
        )
        val viewModel = ExecutionViewModel(KEY, service, FakeRepository(STEPS_INSTANCE, listOf(step)), FakeNoteService())
        runCurrent()
        viewModel.updateStepInformation("step-race", "旧答案")
        advanceTimeBy(500)
        runCurrent()
        viewModel.updateStepInformation("step-race", "最新答案")
        viewModel.confirmStep("step-race")
        runCurrent()
        assertTrue(service.confirmedSteps.isEmpty())
        viewModel.updateStepInformation("step-race", "确认处理中迟到输入")
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("旧答案", "最新答案"), service.stepInformationSaved)
        assertTrue("确认处理中迟到输入" !in service.stepInformationSaved)
        assertEquals(listOf("step-race"), service.confirmedSteps)
    }

    @Test
    fun `step save failure is retried and blocks confirmation`() = runTest(dispatcher) {
        val step = InstanceStepEntity(KEY.taskId, KEY.occurrenceKey, 0, "填写", true, false, 1, "step-00000000001", "INFORMATION")
        val service = FakeExecutionService(
            ExecutionState.Steps(listOf(com.ds.localtaskmanager.domain.execution.StepState("step-00000000001", 0, "填写", true, false, "INFORMATION"))),
            failStepInformation = true,
        )
        val viewModel = ExecutionViewModel(KEY, service, FakeRepository(STEPS_INSTANCE, listOf(step)), FakeNoteService())
        runCurrent()
        viewModel.updateStepInformation("step-00000000001", "待重试")
        advanceTimeBy(500)
        runCurrent()
        viewModel.confirmStep("step-00000000001")
        runCurrent()
        assertTrue(service.stepInformationAttempts >= 2)
        assertEquals(emptyList<String>(), service.confirmedSteps)
    }

    private class FakeNoteService : TaskNoteService {
        val saved = mutableListOf<String>()
        override suspend fun getNote(key: TaskInstanceKey): String = ""
        override suspend fun saveNote(key: TaskInstanceKey, content: String) {
            saved += content
        }
    }

    private class FakeExecutionService(
        private val executionState: ExecutionState = ExecutionState.Normal,
        private val failMoodSave: Boolean = false,
        private val failStepInformation: Boolean = false,
        private val failFirstStepTimer: Boolean = false,
        private val stepInformationGate: CompletableDeferred<Unit>? = null,
    ) : TaskExecutionService {
        val moodSaved = mutableListOf<Pair<Int?, String>>()
        val stepInformationSaved = mutableListOf<String>()
        val confirmedSteps = mutableListOf<String>()
        val stepCounters = mutableListOf<Int>()
        val stepTimerValues = mutableListOf<Long>()
        var stepTimerAttempts = 0
        val completed = mutableListOf<TaskInstanceKey>()
        override suspend fun getExecutionState(key: TaskInstanceKey) = executionState
        override suspend fun getCompletionReadiness(key: TaskInstanceKey) =
            CompletionReadiness(true, true, true)
        override suspend fun setStep(key: TaskInstanceKey, position: Int, completed: Boolean) = Unit
        override suspend fun confirmStep(key: TaskInstanceKey, stepId: String) { confirmedSteps += stepId }
        override suspend fun saveStepInformation(key: TaskInstanceKey, stepId: String, content: String): com.ds.localtaskmanager.domain.execution.StepState {
            stepInformationAttempts += 1
            if (stepInformationAttempts == 1) stepInformationGate?.await()
            if (failStepInformation) error("step save failed")
            stepInformationSaved += content
            return com.ds.localtaskmanager.domain.execution.StepState(stepId, 0, "填写", true, false, "INFORMATION", informationContent = content)
        }
        var stepInformationAttempts = 0
        override suspend fun setStepCounter(key: TaskInstanceKey, stepId: String, value: Int): com.ds.localtaskmanager.domain.execution.StepState {
            stepCounters += value
            return com.ds.localtaskmanager.domain.execution.StepState(stepId, 0, "计数", true, false, "COUNTER", counterValue = value)
        }
        override suspend fun setStepTimer(key: TaskInstanceKey, stepId: String, elapsedMillis: Long): com.ds.localtaskmanager.domain.execution.StepState {
            stepTimerAttempts += 1
            if (failFirstStepTimer && stepTimerAttempts == 1) error("timer save failed")
            stepTimerValues += elapsedMillis
            return com.ds.localtaskmanager.domain.execution.StepState(stepId, 0, "计时", true, false, "TIMER", elapsedMillis = elapsedMillis)
        }
        override suspend fun setCounter(key: TaskInstanceKey, value: Int): ExecutionState.Counter = error("unused")
        override suspend fun addTimerElapsed(key: TaskInstanceKey, elapsedMillis: Long): ExecutionState.Timer = error("unused")
        override suspend fun saveInformationDraft(key: TaskInstanceKey, content: String): ExecutionState.Information = error("unused")
        override suspend fun saveMoodDraft(key: TaskInstanceKey, rating: Int?, text: String): ExecutionState.Mood {
            if (failMoodSave) error("mood save failed")
            moodSaved += rating to text
            return ExecutionState.Mood(rating, text, null)
        }
        override suspend fun complete(key: TaskInstanceKey) { completed += key }
        override suspend fun undoCompletion(key: TaskInstanceKey) = Unit
        override suspend fun reconcile(key: TaskInstanceKey): TaskInstanceEntity = INSTANCE
    }

    private class FakeRepository(private val instance: TaskInstanceEntity = INSTANCE, private val steps: List<InstanceStepEntity> = emptyList()) : TaskRepository {
        override fun observeTasks(taskDate: String): Flow<List<TaskInstanceEntity>> = flowOf(listOf(instance))
        override fun observeTodayTasks(taskDate: String): Flow<List<TodayTask>> = flowOf(listOf(TodayTask(instance, null, null)))
        override suspend fun getTask(key: TaskInstanceKey): TaskInstanceEntity = instance
        override suspend fun getSteps(key: TaskInstanceKey): List<InstanceStepEntity> = steps
        override suspend fun queryHistory(groupId: String?, status: String?): List<TaskInstanceEntity> = emptyList()
        override suspend fun logs(key: TaskInstanceKey): List<ActionLogEntity> = emptyList()
        override suspend fun ledger(key: TaskInstanceKey): List<PointsLedgerEntity> = emptyList()
    }

    private companion object {
        val KEY = TaskInstanceKey("W22Task000000001")
        val INSTANCE = TaskInstanceEntity(
            taskId = KEY.taskId,
            occurrenceKey = KEY.occurrenceKey,
            name = "Task",
            description = "",
            taskDate = "2026-07-20",
            deadline = null,
            groupId = null,
            required = true,
            points = 1,
            sortOrder = null,
            completionMessage = "Done",
            status = TaskStatus.PENDING.name,
            completedAtEpochMillis = null,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        )
        val MOOD_INSTANCE = INSTANCE.copy(executionKind = "MOOD")
        val STEPS_INSTANCE = INSTANCE.copy(executionKind = "STEPS")
    }
}
