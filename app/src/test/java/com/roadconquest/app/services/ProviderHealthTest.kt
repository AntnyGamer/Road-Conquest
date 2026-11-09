package com.roadconquest.app.services

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ProviderHealthTest {
    @Test fun failedRequestUsesOneClockSnapshotForRetryDateAndExpiry() {
        val start = Instant.parse("2026-11-09T15:00:00Z").toEpochMilli()
        var clockReads = 0
        val health = ProviderHealth { clockReads++; start }
        health.failed("roads", status = 503, retryAfter = "Mon, 09 Nov 2026 15:01:00 GMT")
        assertEquals("Failure should read wall-clock time once", 1, clockReads)
        assertEquals(60_000L, health.remaining("roads"))
    }

    @Test fun numericRetryAfterAndExistingCooldownStillTakePriority() {
        var now = 1_000_000L
        val health = ProviderHealth { now }
        health.failed("primary", status = 429, retryAfter = "120")
        assertEquals(120_000L, health.remaining("primary"))
        now += 30_000L
        health.failed("primary", status = 503)
        assertEquals(90_000L, health.remaining("primary"))
        health.failed("secondary", status = 503, errorCode = "queue_busy", retryAfter = "5")
        assertEquals(5_000L, health.remaining("secondary"))
        health.failed("bad-auth", status = 401)
        assertEquals(86_400_000L, health.remaining("bad-auth"))
    }
}
