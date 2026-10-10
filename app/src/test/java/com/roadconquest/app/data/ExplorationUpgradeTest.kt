package com.roadconquest.app.data

import com.roadconquest.app.map.FogGrid
import org.junit.Assert.assertEquals
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
class ExplorationUpgradeTest {
    @Before fun reset() {
        AppDatabase::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
        RuntimeEnvironment.getApplication().deleteDatabase("roadconquest.db")
    }

    @Test fun upgradeDropsStationaryGhostCellsAndCandidatesButKeepsDrivenPlaces() {
        val helper = AppDatabase.get(RuntimeEnvironment.getApplication())
        val db = helper.writableDatabase
        db.execSQL(
            """INSERT INTO track_points
               (latitude, longitude, accuracy_m, speed_mps, bearing_deg, timestamp_ms)
               VALUES (39.7, -75.1, 4.0, 10.0, 90.0, 2000)"""
        )
        val real = FogGrid.cell(39.7, -75.1)!!
        val ghost = FogGrid.cell(39.9, -75.3)!!
        val walked = FogGrid.cell(40.2, -75.1)!!
        for (tile in listOf(real, ghost, walked)) {
            val center = FogGrid.center(tile)
            db.execSQL(
                "INSERT INTO explored_grid VALUES (?, ?, ?, ?)",
                arrayOf(tile.row, tile.column, center.first, center.second)
            )
        }
        val walkedCenter = FogGrid.center(walked)
        db.execSQL(
            "INSERT INTO explored_places(cell_x, cell_y, latitude, longitude) VALUES (10, 20, ?, ?)",
            arrayOf(walkedCenter.first, walkedCenter.second)
        )
        fun candidate(x: Long, y: Long, lat: Double, lon: Double) {
            db.execSQL(
                """INSERT INTO place_candidates
                   (cell_x, cell_y, latitude, longitude, first_seen_at)
                   VALUES (?, ?, ?, ?, 2000)""",
                arrayOf(x, y, lat, lon)
            )
        }
        candidate(Long.MIN_VALUE, Long.MIN_VALUE, 39.6, -75.0) // Zero-point remains valid.
        candidate(5L, 6L, 39.7, -75.1) // Driven at this instant.
        candidate(7L, 8L, 39.9, -75.3) // Never in accepted driving history.

        helper.onUpgrade(db, 12, 13)

        db.rawQuery("SELECT grid_row, grid_col FROM explored_grid", null).use { cursor ->
            val kept = mutableSetOf<Pair<Int, Int>>()
            while (cursor.moveToNext()) kept += cursor.getInt(0) to cursor.getInt(1)
            assertEquals(
                setOf(real.row to real.column, walked.row to walked.column),
                kept
            )
            assertFalse(ghost.row to ghost.column in kept)
        }
        db.rawQuery("SELECT cell_x FROM place_candidates ORDER BY cell_x", null).use { cursor ->
            val kept = mutableListOf<Long>()
            while (cursor.moveToNext()) kept += cursor.getLong(0)
            assertEquals(listOf(Long.MIN_VALUE, 5L), kept)
        }
        db.rawQuery("SELECT count(*) FROM track_points", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }
}
