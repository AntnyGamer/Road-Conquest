package com.roadconquest.app.services

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Shared across service instances; cooldowns never depend on caller-supplied identities. */
class ProviderHealth(private val now: () -> Long = System::currentTimeMillis) {
    private val until = mutableMapOf<String, Long>()
    @Synchronized fun remaining(id: String): Long = ((until[id] ?: 0L) - now()).coerceAtLeast(0L)
    @Synchronized fun failed(id: String, status: Int = 503, retryAfter: String? = null, errorCode: String? = null) {
        val date = retryAfter?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now() }.getOrNull()
        }
        val retry = retryAfter?.toLongOrNull()?.coerceIn(0L, 86_400L)?.times(1000L) ?: date ?: 0L
        val default = if (errorCode in setOf("queue_busy", "provider_cooldown", "budget_conflict")) 1_000L
            else when (status) { 401, 403 -> 86_400_000L; 429 -> 60_000L; else -> 30_000L }
        until[id] = maxOf(until[id] ?: 0L, now() + maxOf(default, retry).coerceAtMost(86_400_000L))
    }
    companion object { val shared = ProviderHealth() }
}
