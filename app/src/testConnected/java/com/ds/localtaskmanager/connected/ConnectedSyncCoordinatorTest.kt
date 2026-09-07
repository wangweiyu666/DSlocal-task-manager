package com.ds.localtaskmanager.connected

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.Data
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConnectedSyncCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `request builds connected exponential identity-only work and enqueues append-or-replace`() {
        var capturedName: String? = null
        var capturedPolicy: ExistingWorkPolicy? = null
        var capturedRequest: OneTimeWorkRequest? = null
        val scheduler = SyncWorkScheduler { name, policy, request ->
            capturedName = name
            capturedPolicy = policy
            capturedRequest = request
        }
        val coordinator = ConnectedSyncCoordinator(context, scheduler)
        val session = CloudSessionEntity(
            accessToken = "secret-access",
            refreshToken = "secret-refresh",
            csrfToken = "secret-csrf",
            accessExpiresAt = "2099-01-01T00:00:00Z",
            accountId = "account-1",
            membershipId = "member-1",
            spaceId = "space-1",
            sessionGeneration = "generation-1",
        )

        coordinator.request(session, "space-1")

        val request = requireNotNull(capturedRequest)
        assertEquals("connected-sync:account-1:member-1:space-1:generation-1", capturedName)
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, capturedPolicy)
        assertEquals(NetworkType.CONNECTED, workSpec(request).field("constraints").field("requiredNetworkType"))
        assertEquals(BackoffPolicy.EXPONENTIAL, workSpec(request).field("backoffPolicy"))
        assertEquals(30_000L, workSpec(request).field("backoffDelayDuration"))
        val input = workSpec(request).field("input") as Data
        assertEquals(
            setOf("accountId", "membershipId", "spaceId", "sessionGeneration"),
            input.keyValueMap.keys,
        )
        assertEquals("account-1", input.getString("accountId"))
        assertEquals("member-1", input.getString("membershipId"))
        assertEquals("space-1", input.getString("spaceId"))
        assertEquals("generation-1", input.getString("sessionGeneration"))
        assertTrue(input.keyValueMap.values.none { it == "secret-access" || it == "secret-refresh" || it == "secret-csrf" })
        assertTrue(capturedName != ConnectedSyncCoordinator.uniqueWorkName(session.copy(membershipId = "member-2"), "space-1"))
        assertTrue(capturedName != ConnectedSyncCoordinator.uniqueWorkName(session.copy(spaceId = "space-2"), "space-2"))
        assertTrue(capturedName != ConnectedSyncCoordinator.uniqueWorkName(session.copy(sessionGeneration = "generation-2"), "space-1"))
    }

    private fun workSpec(request: OneTimeWorkRequest): Any =
        request.javaClass.superclass.declaredField("workSpec").get(request)

    private fun Any.field(name: String): Any {
        val field = javaClass.declaredField(name)
        field.isAccessible = true
        return field.get(this)
    }

    private fun Class<*>.declaredField(name: String): java.lang.reflect.Field =
        generateSequence(this) { it.superclass }
            .mapNotNull { type -> runCatching { type.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()
            ?.also { it.isAccessible = true }
            ?: error("Missing field $name")
}
