package com.ds.localtaskmanager.connected

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudApiRateLimitTest {
    @Test
    fun repeatedLoginAttemptsWaitWithoutBlockingCodeVerification() {
        var now = 0L
        val cooldown = RequestCooldown { now }
        cooldown.beforeRequest("/v1/auth/challenges")
        assertEquals(60, denied { cooldown.beforeRequest("/v1/auth/challenges") }.retryAfterSeconds)
        cooldown.beforeRequest("/v1/auth/verify")
        assertEquals(2, denied { cooldown.beforeRequest("/v1/auth/verify") }.retryAfterSeconds)
        now = 2_000
        cooldown.beforeRequest("/v1/auth/verify")
        cooldown.pause(120)
        now = 60_000
        assertEquals(62, denied { cooldown.beforeRequest("/v1/bootstrap") }.retryAfterSeconds)
        now = 122_000
        cooldown.beforeRequest("/v1/auth/challenges")
    }

    @Test
    fun retryAfterAcceptsHttpDateAndPrefersLongerValidDelay() {
        val now = Instant.parse("2026-09-05T00:00:00Z")
        assertEquals(90, retryAfterSeconds("60", 90, now))
        assertEquals(60, retryAfterSeconds("Sat, 05 Sep 2026 00:01:00 GMT", null, now))
        assertEquals(null, retryAfterSeconds("invalid", -5, now))
    }

    @Test
    fun serverRateLimitBlocksFurtherNetworkRequestsIncludingAuthenticatedCalls() = runTest {
        val opened = AtomicInteger()
        var disconnected = false
        val api = CloudApi("https://example.invalid") { url ->
            opened.incrementAndGet()
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun usingProxy() = false
                override fun disconnect() { disconnected = true }
                override fun getResponseCode() = 429
                override fun getHeaderField(name: String?) = if (name == "Retry-After") "90" else null
                override fun getErrorStream() = ByteArrayInputStream("not JSON".toByteArray())
            }
        }
        try {
            api.bootstrap("token")
            throw AssertionError("Expected rate limit")
        } catch (error: CloudApiException) {
            assertEquals(429, error.status)
            assertEquals(90, error.retryAfterSeconds)
            assertTrue(error.retryable)
        }
        try {
            api.accountStatus("another-token")
            throw AssertionError("Expected local cooldown")
        } catch (error: CloudApiException) {
            assertEquals(429, error.status)
            assertTrue(requireNotNull(error.retryAfterSeconds) > 0)
        }
        assertEquals(1, opened.get())
        assertTrue(disconnected)
    }

    private fun denied(block: () -> Unit): CloudApiException {
        try { block() } catch (error: CloudApiException) { return error }
        throw AssertionError("Expected local cooldown")
    }
}
