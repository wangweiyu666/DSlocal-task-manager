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
class InformationAutoSaveTest {
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

    @Test fun `typing debounces for 500ms and reopening restores the saved draft`() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        vm.updateInformationDraft("first")
        advanceTimeBy(400)
        vm.updateInformationDraft("latest 😀")
        advanceTimeBy(499)
        runCurrent()
        assertTrue(service.saved.isEmpty())
        assertEquals(NoteSaveState.SAVING, vm.state.value.informationSaveState)
        assertTrue(vm.state.value.canComplete)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("latest 😀"), service.saved)
        assertEquals(NoteSaveState.SAVED, vm.state.value.informationSaveState)
        val reopened = model()
        runCurrent()
        assertEquals("latest 😀", reopened.state.value.informationDraft)
    }

    @Test fun `completion before debounce saves the current draft before completing`() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        vm.updateInformationDraft("just typed")
        assertTrue(service.saved.isEmpty())
        vm.complete()
        runCurrent()
        assertEquals(listOf("just typed"), service.saved)
        assertEquals("just typed", service.completedContent)
        assertEquals(1, service.completions)
        assertEquals("COMPLETED", vm.state.value.instance?.status)
    }

    @Test fun `completion waits for blocked write and locks the latest answer`() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        service.writeGate = CompletableDeferred()
        vm.updateInformationDraft("old")
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, service.attempts)
        vm.updateInformationDraft("new")
        vm.complete()
        vm.complete()
        runCurrent()
        vm.updateInformationDraft("blocked")
        assertEquals("new", vm.state.value.informationDraft)
        assertTrue(vm.state.value.working)
        assertEquals(0, service.completions)
        service.writeGate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("old", "new"), service.saved)
        assertEquals("new", service.completedContent)
        assertEquals(1, service.completions)
        assertEquals(NoteSaveState.SAVED, vm.state.value.informationSaveState)
        vm.updateInformationDraft("locked")
        assertEquals("new", vm.state.value.informationDraft)
    }

    @Test fun `back waits for edits made during a write and background flushes without debounce`() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        service.writeGate = CompletableDeferred()
        vm.updateInformationDraft("before back")
        var leftWith: String? = null
        vm.flushNote { leftWith = service.content }
        runCurrent()
        assertNull(leftWith)
        vm.updateInformationDraft("after back")
        runCurrent()
        service.writeGate!!.complete(Unit)
        runCurrent()
        assertEquals("after back", leftWith)
        assertEquals(listOf("before back", "after back"), service.saved)
        vm.updateInformationDraft("background")
        vm.onForegroundLost()
        runCurrent()
        assertEquals("background", service.content)
    }

    @Test fun `failure retains draft blocks navigation completion and share then retry saves`() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        service.failWrites = true
        vm.updateInformationDraft("unsaved")
        advanceTimeBy(500)
        runCurrent()
        assertEquals(NoteSaveState.ERROR, vm.state.value.informationSaveState)
        var navigated = false
        var shared = false
        vm.flushNote { navigated = true }
        runCurrent()
        vm.complete()
        runCurrent()
        vm.prepareInformationForShare { shared = true }
        runCurrent()
        assertFalse(navigated)
        assertFalse(shared)
        assertEquals(0, service.completions)
        assertEquals("unsaved", vm.state.value.informationDraft)
        assertFalse(vm.state.value.working)
        service.failWrites = false
        vm.retryInformationSave()
        runCurrent()
        assertEquals("unsaved", service.content)
        assertEquals(NoteSaveState.SAVED, vm.state.value.informationSaveState)
    }

    @Test fun `sharing flushes latest text and clearing persists an incomplete draft`() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        vm.updateInformationDraft("  share me  ")
        var shared: String? = null
        vm.prepareInformationForShare { shared = it; assertEquals(it, service.content) }
        runCurrent()
        assertEquals("share me", shared)
        vm.updateInformationDraft("")
        assertFalse(vm.state.value.canComplete)
        advanceTimeBy(500)
        runCurrent()
        assertEquals("", service.content)
        assertEquals(NoteSaveState.SAVED, vm.state.value.informationSaveState)
        val reopened = model()
        runCurrent()
        assertEquals("", reopened.state.value.informationDraft)
        assertFalse(reopened.state.value.canComplete)
    }

    @Test fun `stale refresh cannot overwrite an edit saved while refresh is blocked`() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        service.readGate = CompletableDeferred()
        vm.refresh()
        runCurrent()
        vm.updateInformationDraft("new during refresh")
        advanceTimeBy(500)
        runCurrent()
        assertEquals(NoteSaveState.SAVED, vm.state.value.informationSaveState)
        service.readGate!!.complete(Unit)
        runCurrent()
        assertEquals("new during refresh", vm.state.value.informationDraft)
        assertTrue(vm.state.value.canComplete)
        assertEquals("new during refresh", service.content)
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
        override suspend fun getExecutionState(key: TaskInstanceKey): ExecutionState.Information {
            val snapshot = ExecutionState.Information(content, null)
            readGate?.await()
            return snapshot
        }
        override suspend fun getCompletionReadiness(key: TaskInstanceKey) =
            CompletionReadiness(true, content.isNotBlank(), content.isNotBlank())
        override suspend fun saveInformationDraft(key: TaskInstanceKey, content: String): ExecutionState.Information {
            attempts++
            writeGate?.await()
            if (failWrites) error("disk write failed")
            this.content = content.trim()
            saved += content
            return ExecutionState.Information(this.content, null)
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
            executionKind = "INFORMATION",
        )
        override fun observeTasks(taskDate: String) = flowOf(listOf(current))
        override fun observeTodayTasks(taskDate: String) = flowOf(listOf(TodayTask(current, null, null)))
        override suspend fun getTask(key: TaskInstanceKey) = current
        override suspend fun getSteps(key: TaskInstanceKey) = emptyList<InstanceStepEntity>()
        override suspend fun queryHistory(groupId: String?, status: String?) = emptyList<TaskInstanceEntity>()
        override suspend fun logs(key: TaskInstanceKey) = emptyList<ActionLogEntity>()
        override suspend fun ledger(key: TaskInstanceKey) = emptyList<PointsLedgerEntity>()
    }

    private companion object { val KEY = TaskInstanceKey("InfoAutoSave00001") }
}
