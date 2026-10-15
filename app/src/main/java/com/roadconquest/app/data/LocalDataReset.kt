package com.roadconquest.app.data

import android.content.Context
import android.content.Intent
import com.roadconquest.app.TrackingService
import com.roadconquest.app.export.DataExporter
import com.roadconquest.app.progression.ProgressionManager
import com.roadconquest.app.util.Prefs

/** One authoritative path for deleting all device-only RoadConquest progress. */
object LocalDataReset {
    fun stopTracking(context: Context) {
        val app = context.applicationContext
        // Persist an explicit deletion marker before service teardown. If Android kills the
        // process before cleanup finishes, reopening RoadConquest must not interpret this as a
        // normal notification "stop until open" and resume tracking.
        Prefs.setDeviceDataDeletionPending(app, true)
        Prefs.setManualOnly(app, true)
        Prefs.setTrackingPaused(app, true)
        // The durable deletion marker above atomically revoked precise-GPS
        // verification consent before service teardown. No second preference
        // write is needed; VerifiedDriving.close() cannot upload a final batch.
        app.stopService(Intent(app, TrackingService::class.java))
    }

    fun clearStoppedData(context: Context) = synchronized(com.roadconquest.app.export.DataImporter.operationLock) {
        val app = context.applicationContext
        var cleared = false
        try {
            TrackingRepository(app).clearHistory()
            ProgressionManager.resetLocalProgression(app)
            com.roadconquest.app.map.PlacePopulationLookup.clearCache(app)
            DataExporter.clearTemporarySnapshots(app)
            listOf(com.roadconquest.app.export.DataImporter.previousBackup(app),
                java.io.File(app.filesDir, "before-restore.tmp.zip")).forEach {
                check(!it.exists() || it.delete()) { "Could not remove restore recovery data" }
            }
            for (action in listOf(
                TrackingService.ACTION_STATS_UPDATED,
                TrackingService.ACTION_ROADS_UPDATED,
                TrackingService.ACTION_EXPLORATION_UPDATED
            )) {
                app.sendBroadcast(Intent(action).setPackage(app.packageName))
            }
            cleared = true
        } finally {
            if (cleared) {
                // Manual-only remains enabled, so clearing the temporary stop flag cannot
                // restart tracking. Clear the deletion marker last so a crash at any earlier
                // point leaves the app safely stopped and eligible for recovery on next launch.
                Prefs.setTrackingPaused(app, false)
                Prefs.setDeviceDataDeletionPending(app, false)
            }
        }
    }
}
