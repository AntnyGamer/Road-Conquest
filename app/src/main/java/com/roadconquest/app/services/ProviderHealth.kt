package com.roadconquest.app.services

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Shared across service instances; cooldowns never depend on caller-supplied identities. */
class ProviderHealth(private val now: () -> Long = System::currentTimeMillis) {
    private val until = mutableMapOf<String, Long>()
    @Synchronized fun remaining(id: String): Long = ((until[id] ?: 0L) - now()).coerceAtLeast(0L)
    @Synchronized fun failed(id: String, status: Int = 503, retryAfter: String? = null, errorCode: String? = null) {
        // Seconds are the common Retry-After form. Only run the RFC date parser
        // for non-numeric headers instead of throwing on every numeric response.
        val seconds = retryAfter?.toLongOrNull()
        val retry = if (seconds != null) seconds.takeIf { it >= 0L }?.coerceAtMost(86_400L)?.times(1000L) else retryAfter?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now() }.getOrNull()
        }?.coerceAtLeast(0L)
        val default = if (errorCode == "queue_busy" || errorCode == "provider_cooldown" ||
            errorCode == "budget_conflict") 1_000L
            // A gateway can recover from a brief upstream authorization failure.
            // Honor its explicit delay, retaining the conservative fallback if absent.
            else when (status) { 401, 403 -> if (retry != null) 1_000L else 86_400_000L; 429 -> 60_000L; else -> 30_000L }
        until[id] = maxOf(until[id] ?: 0L, now() + maxOf(default, retry ?: 0L).coerceAtMost(86_400_000L))
    }
    companion object { val shared = ProviderHealth() }
}
