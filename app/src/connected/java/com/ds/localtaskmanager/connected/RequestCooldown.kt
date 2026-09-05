package com.ds.localtaskmanager.connected

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max

/** A local courtesy limit; the server remains responsible for enforcing access limits. */
internal class RequestCooldown(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var serverDeadline = 0L
    private val nextAttempt = mutableMapOf<String, Long>()

    @Synchronized
    fun beforeRequest(path: String) {
        val now = nowMillis()
        val remaining = max(serverDeadline, nextAttempt[path] ?: 0L) - now
        if (remaining > 0) {
            val seconds = ((remaining + 999) / 1000).toInt()
            throw CloudApiException(429, "RATE_LIMITED", "请求过于频繁，请在 $seconds 秒后重试", true, seconds)
        }
        val spacing = when (path) {
            "/v1/auth/challenges" -> 60_000L
            "/v1/auth/verify" -> 2_000L
            "/v1/auth/refresh" -> 1_000L
            else -> 0L
        }
        if (spacing > 0) nextAttempt[path] = now + spacing
    }

    @Synchronized
    fun pause(seconds: Int) {
        serverDeadline = max(serverDeadline, nowMillis() + seconds.coerceIn(1, 86_400) * 1000L)
    }
}

internal fun retryAfterSeconds(header: String?, bodySeconds: Int?, now: Instant = Instant.now()): Int? {
    val headerSeconds = header?.trim()?.let { value ->
        value.toLongOrNull() ?: runCatching {
            val date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            max(0L, date.epochSecond - now.epochSecond)
        }.getOrNull()
    }
    return listOfNotNull(headerSeconds, bodySeconds?.toLong()).filter { it >= 0 }
        .maxOrNull()?.coerceIn(1, 86_400)?.toInt()
}
