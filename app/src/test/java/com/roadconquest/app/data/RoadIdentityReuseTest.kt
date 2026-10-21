package com.roadconquest.app.data

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class RoadIdentityReuseTest {
    private lateinit var repository: TrackingRepository
    private val geometry = "[[-74,40],[-73.9995,40.0005],[-74.001,40]]"

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        TrackingRepository(context).clearHistory()
        repository = TrackingRepository(context)
    }

    private fun save(json: String = geometry) {
        repository.upsertRoads(listOf(MatchedRoad("Loop Road", json, 100, 200, 1.0)))
    }

    private fun roads() = repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0)

    @Test fun exactStoredTextNeedsOnlyTheExistingEndpointIdentityReads() {
        save()
        val points = object : JSONArray(geometry) {
            var coordinateReads = 0
            override fun getJSONArray(index: Int): JSONArray {
                coordinateReads++
                return super.getJSONArray(index)
            }
        }
        val method = TrackingRepository::class.java.getDeclaredMethod("roadSegmentId",
            SQLiteDatabase::class.java, String::class.java, JSONArray::class.java, String::class.java)
            .apply { isAccessible = true }
        assertEquals(roads().single().segmentId,
            method.invoke(repository, repository.readableDatabase(), "Loop Road", points, geometry))
        assertEquals(2, points.coordinateReads)
    }

    @Test fun changedTextAndSameEndpointCollisionsKeepCanonicalFallbackAndDeletedPrimaryRecovery() {
        save()
        val primary = roads().single().segmentId
        save("[ [ -74.000000, 40.0 ], [-73.999500,40.000500], [-74.001000,40.0] ]")
        assertEquals(primary, roads().single().segmentId)
        save("[[-74.001,40],[-73.9995,40.0005],[-74,40]]")
        assertEquals(primary, roads().single().segmentId)

        val changed = "[[-74,40],[-74.0005,39.9995],[-74.001,40]]"
        save(changed)
        val alternate = roads().single { it.geometryJson == changed }.segmentId
        assertNotEquals(primary, alternate)
        repository.readableDatabase().delete("roads", "segment_id = ?", arrayOf(primary))
        save(changed)
        assertEquals(alternate, roads().single().segmentId)
    }

    @Test fun identicalCorruptStoredTextNeverBypassesIncomingCoordinateValidation() {
        save()
        val id = roads().single().segmentId
        val invalid = "[[-74,40],[-74.0005,91],[-74.001,40]]"
        repository.readableDatabase().execSQL(
            "UPDATE roads SET geometry_json = ? WHERE segment_id = ?", arrayOf(invalid, id))
        assertThrows(IllegalArgumentException::class.java) { save(invalid) }
        assertEquals(1, roads().size)
        assertEquals(invalid, roads().single().geometryJson)
    }
}
