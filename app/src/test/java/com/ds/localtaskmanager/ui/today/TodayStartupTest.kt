package com.ds.localtaskmanager.ui.today

import androidx.test.core.app.ApplicationProvider
import com.ds.localtaskmanager.DstApplication
import com.ds.localtaskmanager.data.recurrence.InstanceGenerationService
import com.ds.localtaskmanager.data.recurrence.GenerationResult
import com.ds.localtaskmanager.reminder.ReminderReconciler
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TodayStartupTest {
    @Test
    fun `startup and resume share in flight reconciliation and a later resume retries failure`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<DstApplication>()
        var calls = 0
        var reminders = 0
        val gate = CompletableDeferred<Unit>()
        val permissionGate = CompletableDeferred<Unit>()
        val generator = object : InstanceGenerationService by app.instanceGenerationService {
            override suspend fun reconcileAll(throughDate: LocalDate): GenerationResult {
                calls++
                if (calls == 1) {
                    gate.await()
                    error("temporary failure")
                }
                if (calls == 4) permissionGate.await()
                return GenerationResult(emptyList())
            }
        }
        try {
            val vm = TodayViewModel(app.taskRepository, app.importService, generator, app.resultRepository,
                object : ReminderReconciler {
                    override suspend fun reconcileAll(reason: String?) { reminders++ }
                })
            vm.synchronizeInstances(coalesce = true)
            runCurrent()
            vm.synchronizeInstances(coalesce = true)
            runCurrent()
            assertEquals(1, calls)
            assertEquals(0, reminders)
            gate.complete(Unit)
            runCurrent()
            vm.synchronizeInstances(coalesce = true)
            runCurrent()
            assertEquals(2, calls)
            assertEquals(1, reminders)
            vm.synchronizeInstances(coalesce = true)
            runCurrent()
            assertEquals(3, calls)
            assertEquals(2, reminders)
            vm.synchronizeInstances(coalesce = true)
            runCurrent()
            vm.synchronizeInstances() // Permission changes require a following pass.
            vm.synchronizeInstances(coalesce = true)
            runCurrent()
            assertEquals(4, calls)
            assertEquals(2, reminders)
            permissionGate.complete(Unit)
            runCurrent()
            assertEquals(5, calls)
            assertEquals(4, reminders)
        } finally {
            Dispatchers.resetMain()
            app.database.close()
        }
    }
}
