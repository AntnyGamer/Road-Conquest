package com.roadconquest.app

import android.os.Bundle
import android.os.Looper
import android.widget.SeekBar
import android.widget.TextView
import com.roadconquest.app.data.SavedTrip
import com.roadconquest.app.data.AppDatabase
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.data.TripHistoryRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class TripHistoryActivityTest {
    @Before fun resetDatabaseHelper() {
        // Robolectric resets legacy SQLite connections between test methods.
        AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    private fun field(name: String) = TripHistoryActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    private fun trip(): SavedTrip {
        val app = RuntimeEnvironment.getApplication()
        TrackingRepository(app).clearHistory()
        TrackingRepository(app).insertLocations((0..2).map { i -> android.location.Location("gps").apply {
            latitude = 40.0; longitude = -74.0 + i * 0.0001
            accuracy = 3f; speed = 8f; time = 1_000_000L + i * 10_000L
        } })
        return TripHistoryRepository(app).recentTrips().single()
    }

    // Keep native MapLibre rendering in the device suite; exercise the real
    // Activity's state and asynchronous history refresh with ordinary views here.
    @Test fun savingStateKeepsTheSelectedDriveAndReplayPosition() {
        val selected = trip()
        val controller = Robolectric.buildActivity(TripHistoryActivity::class.java)
        val activity = controller.get()
        try {
            field("selectedTrip").set(activity, selected)
            field("replayPosition").setInt(activity, 625)
            val state = Bundle()
            controller.saveInstanceState(state)
            assertEquals(selected.firstId, state.getLong("replayFirstId", -1L))
            assertEquals(selected.startedAt, state.getLong("replayStartedAt", -1L))
            assertEquals(625, state.getInt("replayPosition", -1))
        } finally {
            (field("worker").get(activity) as ExecutorService).shutdownNow()
        }
    }

    @Test fun matchingRefreshesSelectedTripDetailsWithoutNewGpsOrRestartingReplay() {
        val selected = trip()
        val controller = Robolectric.buildActivity(TripHistoryActivity::class.java)
        val activity = controller.get()
        val worker = field("worker").get(activity) as ExecutorService
        val details = TextView(activity)
        val slider = SeekBar(activity).apply { max = 1000; progress = 625 }
        try {
            field("selectedTrip").set(activity, selected)
            field("shareSummaryReady").setBoolean(activity, true)
            field("resumed").setBoolean(activity, true)
            field("listedThroughPointId").setLong(activity, selected.lastId)
            field("insightsText").set(activity, details)
            field("seek").set(activity, slider)
            val repository = TrackingRepository(activity)
            repository.readableDatabase().execSQL("UPDATE track_points SET matched=1")
            repository.upsertRoads(listOf(com.roadconquest.app.data.MatchedRoad(
                "New road", "[[-74,40],[-73.999,40]]", selected.startedAt, selected.endedAt, 1.0)))
            TripHistoryActivity::class.java.getDeclaredMethod("refreshHistoryIfChanged")
                .apply { isAccessible = true }.invoke(activity)
            worker.submit {}.get(5, TimeUnit.SECONDS)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(details.text.toString(), details.text.contains("New roads (est.): 1"))
            assertTrue(details.text.toString(), details.text.contains("0 GPS points awaiting matching"))
            assertEquals(selected, field("selectedTrip").get(activity))
            assertEquals(625, slider.progress)
        } finally {
            worker.shutdownNow()
        }
    }
}
