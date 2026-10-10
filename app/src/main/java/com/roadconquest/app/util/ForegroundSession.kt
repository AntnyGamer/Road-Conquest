package com.roadconquest.app.util

/** Counts all app screens so shade dismissals, internal navigation and recreation are not app reopens. */
class ForegroundSession {
    @Volatile private var started = 0
    private var recreating = 0

    fun isForeground(): Boolean = started > 0

    fun onStart(): Boolean {
        val reopened = started == 0 && recreating == 0
        if (recreating > 0) recreating--
        started++
        return reopened
    }

    fun onStop(recreatingActivity: Boolean) {
        started = (started - 1).coerceAtLeast(0)
        if (recreatingActivity) recreating++
    }

    companion object { val app = ForegroundSession() }
}
