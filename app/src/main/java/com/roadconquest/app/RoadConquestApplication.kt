package com.roadconquest.app

import android.app.Application
import com.roadconquest.app.diagnostics.CrashReporting

class RoadConquestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.roadconquest.app.util.MeasurementUnits.configure(
            com.roadconquest.app.util.Prefs.distanceUnits(this))
        CrashReporting.initialize(this)
        com.roadconquest.app.notifications.OptionalNotifications.initialize(this)
        com.roadconquest.app.services.ServiceConfig.refresh(this)
    }
}
