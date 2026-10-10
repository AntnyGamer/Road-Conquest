package com.roadconquest.app.matching

import android.content.Context
import android.location.Location
import org.robolectric.RuntimeEnvironment
import com.roadconquest.app.data.MatchedRoad
import com.roadconquest.app.data.TrackPoint
import com.roadconquest.app.data.TrackingRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class SavedTurnRepairTest {
    private lateinit var repository: TrackingRepository

    @Before fun prepare() {
        val context: Context = RuntimeEnvironment.getApplication()
        repository = TrackingRepository(context)
        repository.readableDatabase().execSQL("DELETE FROM track_points")
        repository.readableDatabase().execSQL("DELETE FROM road_visits")
        repository.readableDatabase().execSQL("DELETE FROM roads")
    }

    private fun recordAnchoredTurn(): List<Long> {
        val ids = (0..9).map { i ->
            repository.insertLocation(Location("gps").apply {
                latitude = 40.0 + i * 0.00008
                longitude = -74.0
                accuracy = 4f
                speed = 5f
                time = 1_000_000L + i * 2_000L
            })
        }
        assertTrue(ids.all { it > 0 })
        repository.markMatched(listOf(ids.first(), ids.last()))
        return ids
    }

    private fun accepted(points: List<TrackPoint>) = MatchResult(
        listOf(MatchedRoad(
            "Saved Junction", "[[-74.0,40.0],[-74.0,40.00072]]",
            points.first().timestampMillis, points.last().timestampMillis, 0.99
        )),
        points.associate { it.id to 0.99 }
    )

    private fun matchedCount(): Int =
        repository.readableDatabase().rawQuery(
            "SELECT COUNT(*) FROM track_points WHERE matched = 1", null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    @Test fun savedTurnRepairsWithoutRequestingLiveLocation() {
        val ids = recordAnchoredTurn()
        var calls = 0
        val repaired = SavedTurnRepair.repairEligibleGaps(
            repository,
            match = { points ->
                calls++
                assertEquals(ids, points.map { it.id })
                accepted(points)
            }
        )
        assertEquals(1, calls)
        assertEquals(8, repaired)
        assertEquals(10, matchedCount())
        assertEquals(1L, repository.getSummary().roadsUnlockedCount)
    }

    @Test fun missingRoadGeometryNeverResolvesRawGpsOrUnlocksRoads() {
        recordAnchoredTurn()
        val repaired = SavedTurnRepair.repairEligibleGaps(repository, match = { points ->
            MatchResult(emptyList(), points.associate { it.id to 0.99 })
        })
        assertEquals(0, repaired)
        assertEquals(2, matchedCount())
        assertEquals(0L, repository.getSummary().roadsUnlockedCount)
        // Unroutable samples stay stored, but back off until a later retry.
        assertNull(repository.newestEligibleAnchoredGapStartId())
    }

    @Test fun cancelledOrDeletedSessionNeverStartsSavedRepair() {
        recordAnchoredTurn()
        var requests = 0
        val recovered = SavedTurnRepair.repairEligibleGaps(
            repository,
            match = { points ->
                requests++
                accepted(points)
            },
            canContinue = { false }
        )
        assertEquals(0, recovered)
        assertEquals(0, requests)
        assertEquals(2, matchedCount())
        assertEquals(0L, repository.getSummary().roadsUnlockedCount)
    }

    @Test fun noAcceptedGpsConfidenceCannotCreateSavedRoad() {
        recordAnchoredTurn()
        val recovered = SavedTurnRepair.repairEligibleGaps(repository, match = { points ->
            accepted(points).copy(matchedPointConfidences = points.associate { it.id to 0.2 })
        })
        assertEquals(0, recovered)
        assertEquals(2, matchedCount())
        assertEquals(0L, repository.getSummary().roadsUnlockedCount)
    }
}
