package com.ds.localtaskmanager.connected

import com.ds.localtaskmanager.diagnostics.SyncTrace
import com.ds.localtaskmanager.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class SyncTraceTest {
    @Test fun `diagnostic references remain correlatable without exposing original values`() {
        ShadowLog.clear()
        val sensitive = "test@example.invalid:private-task-text"
        SyncTrace.event("COMMAND_PERSISTED", sensitive, "count=1")
        SyncTrace.event("UPLOAD_ACK", sensitive)
        val messages = ShadowLog.getLogsForTag("DST-SyncTrace").map { it.msg }
        assertEquals(if (BuildConfig.SYNC_DIAGNOSTICS) 2 else 0, messages.size)
        assertTrue(messages.all { "ref=${SyncTrace.id(sensitive)}" in it })
        assertTrue(messages.none { "example.invalid" in it || "private-task-text" in it })
    }

    @Test fun `unsafe diagnostic detail is rejected without failing the operation`() {
        ShadowLog.clear()
        SyncTrace.event("UPLOAD_ERROR", "command", "Bearer secret\nanswer")
        assertTrue(ShadowLog.getLogsForTag("DST-SyncTrace").isEmpty())
    }
}
