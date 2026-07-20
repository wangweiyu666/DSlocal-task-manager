package com.ds.localtaskmanager.ui.execution

import android.os.SystemClock
import com.ds.localtaskmanager.data.TaskExecutionService
import com.ds.localtaskmanager.domain.execution.ExecutionState
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey

fun interface MonotonicClock {
    fun elapsedRealtimeMillis(): Long
}

object AndroidMonotonicClock : MonotonicClock {
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}

class TimerSessionController(
    private val service: TaskExecutionService,
    private val monotonicClock: MonotonicClock = AndroidMonotonicClock,
) {
    private var runningKey: TaskInstanceKey? = null
    private var startedAtMillis: Long? = null

    val isRunning: Boolean get() = startedAtMillis != null

    fun preview(base: ExecutionState.Timer): ExecutionState.Timer {
        val startedAt = startedAtMillis ?: return base
        val delta = (monotonicClock.elapsedRealtimeMillis() - startedAt).coerceAtLeast(0)
        return base.copy(elapsedMillis = (base.elapsedMillis + delta).coerceAtMost(base.targetMillis))
    }

    suspend fun start(key: TaskInstanceKey): ExecutionState.Timer {
        val state = service.getExecutionState(key) as ExecutionState.Timer
        if (state.elapsedMillis < state.targetMillis && !isRunning) {
            runningKey = key
            startedAtMillis = monotonicClock.elapsedRealtimeMillis()
        }
        return state
    }

    suspend fun pause(): ExecutionState.Timer? {
        val key = runningKey ?: return null
        val startedAt = startedAtMillis ?: return null
        runningKey = null
        startedAtMillis = null
        val delta = (monotonicClock.elapsedRealtimeMillis() - startedAt).coerceAtLeast(0)
        return if (delta > 0) {
            service.addTimerElapsed(key, delta)
        } else {
            service.getExecutionState(key) as ExecutionState.Timer
        }
    }

    suspend fun onForegroundLost(): ExecutionState.Timer? = pause()
}
