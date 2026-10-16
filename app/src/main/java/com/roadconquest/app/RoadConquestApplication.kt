package com.roadconquest.app

import android.app.Application
import com.roadconquest.app.diagnostics.CrashReporting

class RoadConquestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporting.initialize(this)
    }
}
