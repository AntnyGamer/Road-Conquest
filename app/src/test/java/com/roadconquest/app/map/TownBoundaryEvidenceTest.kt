package com.roadconquest.app.map

import android.database.sqlite.SQLiteDatabase
import com.roadconquest.app.data.PlaceDiscovery
import com.roadconquest.app.data.PlaceKind
import com.roadconquest.app.data.TrackingRepository
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class TownBoundaryEvidenceTest {
    private lateinit var db: SQLiteDatabase

    private val town = PlaceDiscovery(
        PlaceKind.TOWN, "us|new jersey|gloucester city", "Gloucester City",
        "New Jersey", "United States", 1_000_000L, 39.886, -75.111
    )
    private val boundary = JSONObject(
        """{"type":"Polygon","coordinates":[[
          [-75.13,39.875],[-75.10,39.875],[-75.10,39.90],
          [-75.13,39.90],[-75.13,39.875]
        ]]}"""
    )

    @Before fun setup() {
        db = TrackingRepository(RuntimeEnvironment.getApplication()).readableDatabase()
        db.execSQL("DELETE FROM track_points")
        db.execSQL("DELETE FROM progression_counters")
    }

    private fun fix(time: Long, latitude: Double, longitude: Double, accuracy: Double = 5.0) {
        db.execSQL(
            """INSERT INTO track_points
                (latitude, longitude, accuracy_m, speed_mps, bearing_deg, timestamp_ms)
               VALUES (?, ?, ?, 10, 90, ?)""",
            arrayOf(latitude, longitude, accuracy, time)
        )
    }

    @Test fun gridCenterInsideTownDoesNotOverruleRecordedDriveOutsideTown() {
        // Old discovery coordinates could be a grid center inside the wrong
        // municipality even though accepted GPS fixes remained outside it.
        fix(1_001_000L, 39.86, -75.10)
        assertFalse(TownBoundaryEvidence.supports(db, town, boundary))
    }

    @Test fun confirmedGpsFixInsideActualPolygonSupportsTown() {
        fix(1_001_000L, 39.86, -75.10)
        fix(1_002_000L, 39.89, -75.11)
        assertTrue(TownBoundaryEvidence.supports(db, town, boundary))
    }

    @Test fun coarseOrUnrelatedFixCannotConfirmTown() {
        fix(1_002_000L, 39.89, -75.11, accuracy = 200.0)
        fix(1_500_000L, 39.89, -75.11)
        assertTrue(TownBoundaryEvidence.supports(db, town, boundary))
        // Neither inaccurate nor out-of-window fixes count as driving proof;
        // original precise zero-point is the conservative fallback.
        assertFalse(TownBoundaryEvidence.supports(
            db,
            town.copy(latitude = 39.86, longitude = -75.10),
            boundary
        ))
    }

    @Test fun laterRealRevisitCanValidateAnEarlierApproximateTownGuess() {
        val wrongFirstCoordinate = town.copy(latitude = 39.86, longitude = -75.10)
        fix(1_001_000L, 39.86, -75.10)
        fix(2_001_000L, 39.89, -75.11)
        db.execSQL(
            "INSERT OR REPLACE INTO progression_counters(counter_key,value) VALUES (?,?)",
            arrayOf("last_visit:town:${town.key}", 2_000_000L)
        )
        assertTrue(TownBoundaryEvidence.supports(db, wrongFirstCoordinate, boundary))
    }
}
