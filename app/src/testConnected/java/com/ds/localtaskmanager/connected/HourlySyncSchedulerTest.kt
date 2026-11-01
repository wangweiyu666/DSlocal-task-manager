package com.ds.localtaskmanager.connected

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HourlySyncSchedulerTest {
    private val session = CloudSessionEntity(accessToken = "secret-access", refreshToken = "secret-refresh",
        csrfToken = "secret-csrf", accessExpiresAt = "2099-01-01T00:00:00Z", accountId = "account",
        membershipId = "member", spaceId = "space", role = "EXECUTOR", sessionGeneration = "generation")

    @Test fun hourlyUniqueIdentityAndConstraints() {
        val calls = mutableListOf<Triple<String, ExistingPeriodicWorkPolicy, PeriodicWorkRequest>>()
        val scheduler = WorkManagerHourlySyncScheduler(ApplicationProvider.getApplicationContext<Context>(),
            enqueue = { n, p, r -> calls += Triple(n, p, r) }, cancel = { error("unexpected cancel") })
        scheduler.update(session)
        scheduler.update(session)
        scheduler.update(session.copy(accountId = "new-account", sessionGeneration = "new-generation"))
        assertEquals(1, calls.map { it.first }.distinct().size)
        calls.forEach { (_, policy, request) ->
            assertEquals(ExistingPeriodicWorkPolicy.UPDATE, policy)
            val spec = request.field("workSpec")
            assertEquals(3_600_000L, spec.field("intervalDuration"))
            assertEquals(3_600_000L, spec.field("initialDelay"))
            assertEquals(NetworkType.CONNECTED, spec.field("constraints").field("requiredNetworkType"))
            assertEquals(BackoffPolicy.EXPONENTIAL, spec.field("backoffPolicy"))
            assertEquals(30_000L, spec.field("backoffDelayDuration"))
            val input = spec.field("input") as Data
            assertEquals(setOf("accountId", "membershipId", "spaceId", "sessionGeneration", "periodic"), input.keyValueMap.keys)
            assertTrue(input.getBoolean("periodic", false))
            assertFalse(input.keyValueMap.values.any { it.toString().startsWith("secret-") })
        }
        val latest = calls.last().third.field("workSpec").field("input") as Data
        assertEquals("new-account", latest.getString("accountId"))
        assertEquals("new-generation", latest.getString("sessionGeneration"))
    }

    @Test fun invalidOrSignedOutSessionsCancelHourlyWork() {
        val cancelled = mutableListOf<String>()
        val scheduler = WorkManagerHourlySyncScheduler(ApplicationProvider.getApplicationContext<Context>(),
            enqueue = { _, _, _ -> error("must not schedule") }, cancel = { cancelled += it })
        listOf(null, session.copy(role = "ADMIN"), session.copy(accountId = null),
            session.copy(membershipId = null), session.copy(spaceId = null),
            session.copy(sessionGeneration = ""), session.copy(blockedReason = "PRIVACY_ACK_REQUIRED"))
            .forEach(scheduler::update)
        assertEquals(List(7) { WorkManagerHourlySyncScheduler.WORK_NAME }, cancelled)
    }

    private fun Any.field(name: String): Any {
        val field = generateSequence(javaClass as Class<*>?) { it.superclass }
            .mapNotNull { type -> runCatching { type.getDeclaredField(name) }.getOrNull() }.first()
        field.isAccessible = true
        return field.get(this)
    }
}
