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

    private class FakeNoteService : TaskNoteService {
        val saved = mutableListOf<String>()
        override suspend fun getNote(key: TaskInstanceKey): String = ""
        override suspend fun saveNote(key: TaskInstanceKey, content: String) {
            saved += content
        }
    }

    private class FakeExecutionService : TaskExecutionService {
        override suspend fun getExecutionState(key: TaskInstanceKey) = ExecutionState.Normal
        override suspend fun getCompletionReadiness(key: TaskInstanceKey) =
            CompletionReadiness(true, true, true)
        override suspend fun setStep(key: TaskInstanceKey, position: Int, completed: Boolean) = Unit
        override suspend fun setCounter(key: TaskInstanceKey, value: Int): ExecutionState.Counter = error("unused")
        override suspend fun addTimerElapsed(key: TaskInstanceKey, elapsedMillis: Long): ExecutionState.Timer = error("unused")
        override suspend fun saveInformationDraft(key: TaskInstanceKey, content: String): ExecutionState.Information = error("unused")
        override suspend fun complete(key: TaskInstanceKey) = Unit
        override suspend fun undoCompletion(key: TaskInstanceKey) = Unit
        override suspend fun reconcile(key: TaskInstanceKey): TaskInstanceEntity = INSTANCE
    }

    private class FakeRepository : TaskRepository {
        override fun observeTasks(taskDate: String): Flow<List<TaskInstanceEntity>> = flowOf(listOf(INSTANCE))
        override fun observeTodayTasks(taskDate: String): Flow<List<TodayTask>> = flowOf(listOf(TodayTask(INSTANCE, null, null)))
        override suspend fun getTask(key: TaskInstanceKey): TaskInstanceEntity = INSTANCE
        override suspend fun getSteps(key: TaskInstanceKey): List<InstanceStepEntity> = emptyList()
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
    }
}
