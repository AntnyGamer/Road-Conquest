package com.roadconquest.app.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.roadconquest.app.TrackingService

/** Counts all app screens so shade dismissals, internal navigation and recreation are not app reopens. */
class ForegroundSession {
    private var started = 0
    private var recreating = 0

    fun onStart(): Boolean {
        val reopened = started == 0 && recreating == 0
        if (recreating > 0) recreating--
        started++
        return reopened
    }

    /** Secondary screens also resume an explicit notification stop on a real app reopen. */
    fun onStart(context: Context): Boolean = onStart().also { reopened ->
        if (reopened && Prefs.shouldResumePausedTracking(context)) {
            Prefs.setTrackingPaused(context, false)
            // Clear the reopen pause even when permissions/Location currently block
            // starting. Returning to the map can then use its normal recovery flow.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= 34 && !context.getSystemService(LocationManager::class.java).isLocationEnabled)
            ) return@also
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
            }.onFailure {
                Prefs.setTrackingPaused(context, true)
                android.util.Log.w("RoadConquest", "Tracking resume will retry on the next app reopen", it)
            }
        }
    }

    fun onStop(recreatingActivity: Boolean) {
        started = (started - 1).coerceAtLeast(0)
        if (recreatingActivity) recreating++
    }

    companion object { val app = ForegroundSession() }
}
