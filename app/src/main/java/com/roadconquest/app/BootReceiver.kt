package com.roadconquest.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.roadconquest.app.util.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        com.roadconquest.app.notifications.WeeklyReminderReceiver.schedule(context)
        if (Prefs.isDeviceDataDeletionPending(context) || Prefs.isTrackingPaused(context) ||
            Prefs.isManualOnly(context) || !Prefs.hasEverStarted(context)
        ) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !context.getSystemService(LocationManager::class.java).isLocationEnabled
        ) return

        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }
    }
}
