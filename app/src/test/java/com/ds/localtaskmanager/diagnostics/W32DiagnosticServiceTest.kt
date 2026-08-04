package com.ds.localtaskmanager.diagnostics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ds.localtaskmanager.data.AppDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class W32DiagnosticServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    @After
    fun tearDown() {
        database.close()
        context.getSharedPreferences("diagnostic_events", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `event buffer is bounded and report excludes user content`() {
        val clock = Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC)
        val store = DiagnosticEventStore(context, clock)
        repeat(120) { store.record("BACKUP", "ERROR_$it", recovered = it % 2 == 0) }

        val report = DiagnosticService(context, database, store).buildReport()

        assertEquals(100, store.events().size)
        assertTrue(report.contains("DStationery diagnostic format: 1"))
        assertTrue(report.contains("BACKUP ERROR_119"))
        assertFalse(report.contains("taskName"))
        assertFalse(report.contains(context.filesDir.absolutePath))
    }
}
