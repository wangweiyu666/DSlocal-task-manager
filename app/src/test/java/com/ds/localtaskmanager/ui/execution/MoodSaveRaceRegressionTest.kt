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
class MoodSaveRaceRegressionTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `completion waits for in flight mood write and persists latest text exactly once`() = runTest(dispatcher) {
        val execution = BlockingFirstMoodSaveService()
        val repository = FakeRepository()
        execution.repository = repository
        val viewModel = ExecutionViewModel(KEY, execution, repository, FakeNoteService())
        runCurrent()

        viewModel.updateMoodRating(4)
        runCurrent()
        execution.firstSaveStarted.await()

        viewModel.updateMoodText("latest")
        viewModel.refresh()
        runCurrent()
        viewModel.complete()
        runCurrent()

        assertTrue(viewModel.state.value.working)
        viewModel.updateMoodText("must stay blocked")
        assertEquals("latest", viewModel.state.value.moodText)
        assertTrue(execution.firstSaveStarted.isCompleted)
        assertTrue(execution.completed.isEmpty())
        assertTrue(execution.saved.isEmpty())

        execution.releaseFirstSave.complete(Unit)
        runCurrent()

        assertEquals(listOf(4 to "", 4 to "latest"), execution.saved)
        assertEquals(1, execution.completed.size)
        assertEquals(4 to "latest", execution.completedAnswer)
        assertEquals(NoteSaveState.SAVED, viewModel.state.value.moodSaveState)
        assertEquals("latest", viewModel.state.value.moodText)
        assertFalse(viewModel.state.value.working)
    }

    @Test
    fun `flush callback waits until newer mood text has been persisted`() = runTest(dispatcher) {
        val execution = BlockingFirstMoodSaveService(blockingSaveNumber = 2)
        val repository = FakeRepository()
        execution.repository = repository
        val viewModel = ExecutionViewModel(KEY, execution, repository, FakeNoteService())
        runCurrent()

        viewModel.updateMoodRating(4)
        runCurrent()
        assertEquals(listOf(4 to ""), execution.saved)

        viewModel.updateMoodText("before back")
        var callbackCalled = false
        var callbackAnswer: Pair<Int?, String>? = null
        viewModel.flushNote {
            callbackCalled = true
            callbackAnswer = execution.saved.lastOrNull()
        }
        runCurrent()

        assertFalse(callbackCalled)
        assertEquals(listOf(4 to ""), execution.saved)
        viewModel.updateMoodText("after back")
        runCurrent()
        assertFalse(callbackCalled)

        execution.releaseFirstSave.complete(Unit)
        runCurrent()

        assertEquals(listOf(4 to "", 4 to "before back", 4 to "after back"), execution.saved)
        assertTrue(callbackCalled)
        assertEquals(4 to "after back", callbackAnswer)
    }

    private class BlockingFirstMoodSaveService(
        private val blockingSaveNumber: Int = 1,
    ) : TaskExecutionService {
        val firstSaveStarted = CompletableDeferred<Unit>()
        val releaseFirstSave = CompletableDeferred<Unit>()
        val saved = mutableListOf<Pair<Int?, String>>()
        val completed = mutableListOf<TaskInstanceKey>()
        var completedAnswer: Pair<Int?, String>? = null
        lateinit var repository: FakeRepository
        private var mood = ExecutionState.Mood(null, "", null)
        private var saveCount = 0

        override suspend fun getExecutionState(key: TaskInstanceKey) = mood
        override suspend fun getCompletionReadiness(key: TaskInstanceKey) = CompletionReadiness(true, true, true)
        override suspend fun setStep(key: TaskInstanceKey, position: Int, completed: Boolean) = Unit
        override suspend fun setCounter(key: TaskInstanceKey, value: Int): ExecutionState.Counter = error("unused")
        override suspend fun addTimerElapsed(key: TaskInstanceKey, elapsedMillis: Long): ExecutionState.Timer = error("unused")
        override suspend fun saveInformationDraft(key: TaskInstanceKey, content: String): ExecutionState.Information = error("unused")
        override suspend fun saveMoodDraft(key: TaskInstanceKey, rating: Int?, text: String): ExecutionState.Mood {
            if (++saveCount == blockingSaveNumber) {
                withContext(NonCancellable) {
                    firstSaveStarted.complete(Unit)
                    releaseFirstSave.await()
                }
            }
            saved += rating to text
            mood = ExecutionState.Mood(rating, text, null)
            return mood
        }
        override suspend fun complete(key: TaskInstanceKey) {
            completed += key
            completedAnswer = mood.rating to mood.text
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
        val KEY = TaskInstanceKey("MoodRace000000001")
        val INSTANCE = TaskInstanceEntity(
            taskId = KEY.taskId, occurrenceKey = KEY.occurrenceKey, name = "心情", description = "",
            taskDate = "2026-09-06", deadline = null, groupId = null, required = true, points = 1,
            sortOrder = null, completionMessage = "完成", status = TaskStatus.PENDING.name,
            completedAtEpochMillis = null, createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
            executionKind = "MOOD",
        )
    }
}
