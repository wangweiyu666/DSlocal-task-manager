package com.ds.localtaskmanager.ui.execution

import com.ds.localtaskmanager.data.*
import com.ds.localtaskmanager.domain.execution.CompletionReadiness
import com.ds.localtaskmanager.domain.execution.ExecutionState
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChoiceAutoSaveTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeRepository()
    private val service = FakeService(repository)
    private val notes = object : TaskNoteService {
        override suspend fun getNote(key: TaskInstanceKey) = ""
        override suspend fun saveNote(key: TaskInstanceKey, content: String) = Unit
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()
    private fun model() = ExecutionViewModel(KEY, service, repository, notes)

    @Test fun `choice has no default and saves immediately before completion`() = runTest(dispatcher) {
        val vm = model(); runCurrent()
        assertNull(vm.state.value.selectedOptionId)
        assertFalse(vm.state.value.canComplete)
        vm.updateChoice(A); vm.complete(); runCurrent()
        assertEquals(listOf(A), service.saved)
        assertEquals(A, service.completedContent)
        assertEquals(1, service.completions)
        val reopened = model(); runCurrent()
        assertEquals(A, reopened.state.value.selectedOptionId)
    }

    @Test fun `blocked write serializes latest choice and duplicate complete`() = runTest(dispatcher) {
        val vm = model(); runCurrent()
        service.writeGate = CompletableDeferred()
        vm.updateChoice(A); runCurrent()
        vm.updateChoice(B); vm.complete(); vm.complete(); runCurrent()
        vm.updateChoice(A)
        assertEquals(B, vm.state.value.selectedOptionId)
        assertEquals(0, service.completions)
        service.writeGate!!.complete(Unit); runCurrent()
        assertEquals(listOf(A, B), service.saved)
        assertEquals(B, service.completedContent)
        assertEquals(1, service.completions)
    }

    @Test fun `failed choice retains draft and blocks completion and navigation until retry`() = runTest(dispatcher) {
        val vm = model(); runCurrent()
        service.failWrites = true
        vm.updateChoice(B); runCurrent()
        vm.complete(); runCurrent()
        var left = false
        vm.flushNote { left = true }; runCurrent()
        assertFalse(left)
        assertEquals(0, service.completions)
        assertEquals(B, vm.state.value.selectedOptionId)
        assertEquals(NoteSaveState.ERROR, vm.state.value.choiceSaveState)
        service.failWrites = false
        vm.retryChoiceSave(); runCurrent()
        vm.flushNote { left = true }; runCurrent()
        assertTrue(left)
        assertEquals(B, service.content)
        assertEquals(NoteSaveState.SAVED, vm.state.value.choiceSaveState)
    }

    @Test fun `refresh cannot replace a newer saved choice with its old snapshot`() = runTest(dispatcher) {
        val vm = model(); runCurrent()
        service.readGate = CompletableDeferred()
        vm.refresh(); runCurrent()
        vm.updateChoice(B); runCurrent()
        service.readGate!!.complete(Unit); runCurrent()
        assertEquals(B, vm.state.value.selectedOptionId)
        assertTrue(vm.state.value.canComplete)
    }

    private class FakeService(val repository: FakeRepository) : TaskExecutionService {
        var content = ""
        val saved = mutableListOf<String>()
        var writeGate: CompletableDeferred<Unit>? = null
        var readGate: CompletableDeferred<Unit>? = null
        var failWrites = false
        var attempts = 0
        var completions = 0
        var completedContent: String? = null
        override suspend fun getExecutionState(key: TaskInstanceKey): ExecutionState.Choice {
            val snapshot = ExecutionState.Choice(OPTIONS, content.takeIf { it.isNotBlank() })
            readGate?.await()
            return snapshot
        }
        override suspend fun getCompletionReadiness(key: TaskInstanceKey) =
            CompletionReadiness(true, content.isNotBlank(), content.isNotBlank())
        override suspend fun saveChoice(key: TaskInstanceKey, optionId: String): ExecutionState.Choice {
            val content = optionId
            attempts++
            writeGate?.await()
            if (failWrites) error("disk write failed")
            this.content = content.trim()
            saved += content
            return ExecutionState.Choice(OPTIONS, this.content)
        }
        override suspend fun complete(key: TaskInstanceKey) {
            check(content.isNotBlank())
            completions++
            completedContent = content
            repository.current = repository.current.copy(status = "COMPLETED")
        }
        override suspend fun setStep(key: TaskInstanceKey, position: Int, completed: Boolean) = Unit
        override suspend fun setCounter(key: TaskInstanceKey, value: Int): ExecutionState.Counter = error("unused")
        override suspend fun addTimerElapsed(key: TaskInstanceKey, elapsedMillis: Long): ExecutionState.Timer = error("unused")
        override suspend fun saveInformationDraft(key: TaskInstanceKey, content: String): ExecutionState.Information = error("unused")
        override suspend fun saveMoodDraft(key: TaskInstanceKey, rating: Int?, text: String): ExecutionState.Mood = error("unused")
        override suspend fun undoCompletion(key: TaskInstanceKey) = Unit
        override suspend fun reconcile(key: TaskInstanceKey) = repository.current
    }

    private class FakeRepository : TaskRepository {
        var current = TaskInstanceEntity(
            taskId = KEY.taskId, occurrenceKey = KEY.occurrenceKey, name = "告知", description = "",
            taskDate = "2026-09-12", deadline = null, groupId = null, required = true, points = 1,
            sortOrder = null, completionMessage = "完成", status = "PENDING",
            completedAtEpochMillis = null, createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
            executionKind = "CHOICE",
        )
        override fun observeTasks(taskDate: String) = flowOf(listOf(current))
        override fun observeTodayTasks(taskDate: String) = flowOf(listOf(TodayTask(current, null, null)))
        override suspend fun getTask(key: TaskInstanceKey) = current
        override suspend fun getSteps(key: TaskInstanceKey) = emptyList<InstanceStepEntity>()
        override suspend fun queryHistory(groupId: String?, status: String?) = emptyList<TaskInstanceEntity>()
        override suspend fun logs(key: TaskInstanceKey) = emptyList<ActionLogEntity>()
        override suspend fun ledger(key: TaskInstanceKey) = emptyList<PointsLedgerEntity>()
    }

    private companion object {
        val KEY = TaskInstanceKey("ChoiceAutoSave001")
        const val A = "Option0000000001"
        const val B = "Option0000000002"
        val OPTIONS = listOf(com.ds.localtaskmanager.domain.execution.ChoiceOption(A, "零分", 0), com.ds.localtaskmanager.domain.execution.ChoiceOption(B, "九分", 9))
    }
}
