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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TemperatureAutoSaveTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `completion waits for in flight temperature write and persists latest value exactly once`() = runTest(dispatcher) {
        val execution = BlockingFirstTemperatureSaveService()
        val repository = FakeRepository()
        execution.repository = repository
        val viewModel = ExecutionViewModel(KEY, execution, repository, FakeNoteService())
        runCurrent()

        viewModel.updateTemperature(365)
        runCurrent()
        execution.firstSaveStarted.await()

        viewModel.updateTemperature(370)
        viewModel.refresh()
        runCurrent()
        viewModel.complete()
        runCurrent()

        assertTrue(viewModel.state.value.working)
        viewModel.updateTemperature(380)
        assertEquals(370, viewModel.state.value.temperatureTenthsC)
        assertTrue(execution.firstSaveStarted.isCompleted)
        assertTrue(execution.completed.isEmpty())
        assertTrue(execution.saved.isEmpty())

        execution.releaseFirstSave.complete(Unit)
        runCurrent()

        assertEquals(listOf(365, 370), execution.saved)
        assertEquals(1, execution.completed.size)
        assertEquals(370, execution.completedAnswer)
        assertEquals(NoteSaveState.SAVED, viewModel.state.value.temperatureSaveState)
        assertEquals(370, viewModel.state.value.temperatureTenthsC)
        assertFalse(viewModel.state.value.working)
    }

    @Test
    fun `flush callback waits until newer temperature value has been persisted`() = runTest(dispatcher) {
        val execution = BlockingFirstTemperatureSaveService(blockingSaveNumber = 2)
        val repository = FakeRepository()
        execution.repository = repository
        val viewModel = ExecutionViewModel(KEY, execution, repository, FakeNoteService())
        runCurrent()

        viewModel.updateTemperature(365)
        runCurrent()
        assertEquals(listOf(365), execution.saved)

        viewModel.updateTemperature(370)
        var callbackCalled = false
        var callbackAnswer: Int? = null
        viewModel.flushNote {
            callbackCalled = true
            callbackAnswer = execution.saved.lastOrNull()
        }
        runCurrent()

        assertFalse(callbackCalled)
        assertEquals(listOf(365), execution.saved)
        viewModel.updateTemperature(375)
        runCurrent()
        assertFalse(callbackCalled)

        execution.releaseFirstSave.complete(Unit)
        runCurrent()

        assertEquals(listOf(365, 370, 375), execution.saved)
        assertTrue(callbackCalled)
        assertEquals(375, callbackAnswer)
    }

    private class BlockingFirstTemperatureSaveService(
        private val blockingSaveNumber: Int = 1,
    ) : TaskExecutionService {
        val firstSaveStarted = CompletableDeferred<Unit>()
        val releaseFirstSave = CompletableDeferred<Unit>()
        val saved = mutableListOf<Int?>()
        val completed = mutableListOf<TaskInstanceKey>()
        var completedAnswer: Int? = null
        lateinit var repository: FakeRepository
        private var temperature = ExecutionState.Temperature(null, null)
        private var saveCount = 0

        override suspend fun saveMoodDraft(key: TaskInstanceKey, rating: Int?, text: String): ExecutionState.Mood = error("unused")
        override suspend fun getExecutionState(key: TaskInstanceKey) = temperature
        override suspend fun getCompletionReadiness(key: TaskInstanceKey) = CompletionReadiness(true, true, true)
        override suspend fun setStep(key: TaskInstanceKey, position: Int, completed: Boolean) = Unit
        override suspend fun setCounter(key: TaskInstanceKey, value: Int): ExecutionState.Counter = error("unused")
        override suspend fun addTimerElapsed(key: TaskInstanceKey, elapsedMillis: Long): ExecutionState.Timer = error("unused")
        override suspend fun saveInformationDraft(key: TaskInstanceKey, content: String): ExecutionState.Information = error("unused")
        override suspend fun saveTemperatureDraft(key: TaskInstanceKey, value: Int?): ExecutionState.Temperature {
            if (++saveCount == blockingSaveNumber) {
                withContext(NonCancellable) {
                    firstSaveStarted.complete(Unit)
                    releaseFirstSave.await()
                }
            }
            saved += value
            temperature = ExecutionState.Temperature(value, null)
            return temperature
        }
        override suspend fun complete(key: TaskInstanceKey) {
            completed += key
            completedAnswer = temperature.temperatureTenthsC
            repository.markCompleted()
        }
        override suspend fun undoCompletion(key: TaskInstanceKey) = Unit
        override suspend fun reconcile(key: TaskInstanceKey): TaskInstanceEntity = repository.current
    }

    private class FakeNoteService : TaskNoteService {
        override suspend fun getNote(key: TaskInstanceKey) = ""
        override suspend fun saveNote(key: TaskInstanceKey, content: String) = Unit
    }

    private class FakeRepository(initial: TaskInstanceEntity = INSTANCE) : TaskRepository {
        var current = initial
        override fun observeTasks(taskDate: String): Flow<List<TaskInstanceEntity>> = flowOf(listOf(current))
        override fun observeTodayTasks(taskDate: String): Flow<List<TodayTask>> = flowOf(listOf(TodayTask(current, null, null)))
        override suspend fun getTask(key: TaskInstanceKey) = current
        override suspend fun getSteps(key: TaskInstanceKey): List<InstanceStepEntity> = emptyList()
        override suspend fun queryHistory(groupId: String?, status: String?) = emptyList<TaskInstanceEntity>()
        override suspend fun logs(key: TaskInstanceKey): List<ActionLogEntity> = emptyList()
        override suspend fun ledger(key: TaskInstanceKey): List<PointsLedgerEntity> = emptyList()
        fun markCompleted() { current = current.copy(status = TaskStatus.COMPLETED.name, completedAtEpochMillis = 2) }
    }

    private companion object {
        val KEY = TaskInstanceKey("TemperatureRace000000001")
        val INSTANCE = TaskInstanceEntity(
            taskId = KEY.taskId, occurrenceKey = KEY.occurrenceKey, name = "体温", description = "",
            taskDate = "2026-09-06", deadline = null, groupId = null, required = true, points = 1,
            sortOrder = null, completionMessage = "完成", status = TaskStatus.PENDING.name,
            completedAtEpochMillis = null, createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
            executionKind = "TEMPERATURE",
        )
    }
}
