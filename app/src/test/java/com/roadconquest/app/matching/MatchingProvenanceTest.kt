package com.roadconquest.app.matching

import android.location.Location
import com.roadconquest.app.data.TrackPoint
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.data.WeeklyChallenges
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class MatchingProvenanceTest {
    private lateinit var repository: TrackingRepository
    private val time = WeeklyChallenges.weekStart(System.currentTimeMillis()) + 1_000L

    @Before fun reset() {
        val context = RuntimeEnvironment.getApplication()
        TrackingRepository(context).clearHistory()
        repository = TrackingRepository(context)
    }

    private fun record(count: Int, restored: Int, matchedContext: Boolean = false): List<Long> {
        val ids = (0 until count).map { index -> repository.insertLocation(Location("gps").apply {
            latitude = 40.0; longitude = -74.0 + index * 0.0001
            accuracy = 5f; speed = 5f; time = this@MatchingProvenanceTest.time + index * 3_000L
        }) }
        // Model the importer-owned provenance; the provider response below has no such field.
        for (id in ids.take(restored)) repository.readableDatabase().execSQL(
            "UPDATE track_points SET weekly_eligible=0 WHERE id=?", arrayOf(id))
        if (matchedContext) repository.markMatched(ids.take(restored))
        return ids
    }

    private fun coordinate(point: TrackPoint) = JSONArray().put(point.longitude).put(point.latitude)

    private fun response(points: List<TrackPoint>, names: List<String>): JSONObject = JSONObject()
        .put("code", "Ok")
        .put("tracepoints", JSONArray().apply { points.forEachIndexed { index, point ->
            put(JSONObject().put("matchings_index", 0).put("waypoint_index", index)
                .put("alternatives_count", 0).put("location", coordinate(point)))
        } })
        .put("matchings", JSONArray().put(JSONObject().put("confidence", 0.99)
            .put("legs", JSONArray().apply { names.forEachIndexed { index, name ->
                put(JSONObject().put("steps", JSONArray().put(JSONObject().put("name", name)
                    .put("distance", 9).put("geometry", JSONObject().put("type", "LineString")
                        .put("coordinates", JSONArray().put(coordinate(points[index]))
                            .put(coordinate(points[index + 1])))))))
            } })))

    private fun complete(names: List<String>): MatchResult {
        val window = repository.loadMatchingWindow(limit = 10)
        val result = requireNotNull(RoadGeometryValidator().parse(response(window.points, names), window.points))
        repository.completeMatch(result.roads,
            result.matchedPointConfidences.keys.filter { it in window.markableIds })
        return result
    }

    private fun savedEligibility(): Map<String, Boolean> = buildMap {
        repository.readableDatabase().rawQuery("SELECT name,weekly_eligible FROM roads", null).use { cursor ->
            while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1) == 1)
        }
    }

    @Test fun mixedBatchKeepsIndependentLiveRoadsEligible() {
        val ids = record(count = 5, restored = 2)
        val window = repository.loadMatchingWindow(limit = 10)
        assertEquals(ids, window.points.map { it.id })
        assertEquals(listOf(false, false, true, true, true), window.points.map { it.weeklyEligible })
        complete(listOf("Restored", "Boundary", "Live one", "Live two"))
        assertEquals(mapOf("Restored" to false, "Boundary" to false,
            "Live one" to true, "Live two" to true), savedEligibility())
        val goals = WeeklyChallenges.status(RuntimeEnvironment.getApplication(), time + 20_000L)
        assertEquals(2L, goals.challenges.first().progress)
        assertTrue(repository.loadMatchingWindow(limit = 10).points.isEmpty())
    }

    @Test fun restoredMatchedContextCannotEarnCreditThroughLivePendingFixes() {
        val ids = record(count = 3, restored = 1, matchedContext = true)
        val window = repository.loadMatchingWindow(limit = 10)
        assertEquals(ids, window.points.map { it.id })
        assertEquals(ids.drop(1).toSet(), window.markableIds)
        complete(listOf("Context road", "Fresh road"))
        assertEquals(mapOf("Context road" to false, "Fresh road" to true), savedEligibility())
        val goals = WeeklyChallenges.status(RuntimeEnvironment.getApplication(), time + 20_000L)
        assertEquals(1L, goals.challenges.first().progress)
    }

    @Test fun mergingLegsKeepsRestoredEvidenceAndLaterDrivesCannotMintCredit() {
        record(count = 3, restored = 1)
        val result = complete(listOf("Same road", "Same road"))
        assertEquals(1, result.roads.size)
        assertFalse(result.roads.single().weeklyEligible)
        repository.upsertRoads(result.roads.map { it.copy(
            firstTimestamp = time + 30_000L, lastTimestamp = time + 36_000L, weeklyEligible = true) })
        assertEquals(mapOf("Same road" to false), savedEligibility())
    }
}
