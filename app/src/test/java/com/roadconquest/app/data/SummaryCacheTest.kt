package com.roadconquest.app.data

import android.content.Context
import android.location.Location
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class SummaryCacheTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: TrackingRepository
    private lateinit var database: AppDatabase

    @Before fun reset() {
        AppDatabase::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
        TrackingRepository(context).clearHistory()
        repository = TrackingRepository(context)
        database = AppDatabase.get(context)
    }

    private fun fix(latitude: Double, timestamp: Long) = Location("gps").apply {
        this.latitude = latitude
        longitude = -74.0
        accuracy = 5f
        speed = 10f
        time = timestamp
    }

    @Test fun cachedSummaryStaysExactAcrossTrackAndRoadWrites() {
        val empty = repository.getSummary()
        assertEquals(0L, empty.trackPointCount)
        assertEquals(0L, empty.roadSegmentCount)
        assertNotNull(database.trackSummaryCache)
        assertNotNull(database.roadSummaryCache)
        assertNotNull(database.lastTrackPointCache)
        assertFalse(requireNotNull(database.lastTrackPointCache).present)

        repository.insertLocations(listOf(
            fix(40.0, 1_000L),
            fix(40.001, 11_000L)
        ))
        val afterTrack = repository.getSummary()
        assertEquals(2L, afterTrack.trackPointCount)
        assertTrue(afterTrack.distanceMeters > 100.0)
        assertEquals(1_000L, afterTrack.firstTrackAt)
        assertEquals(11_000L, afterTrack.lastTrackAt)
        val trackCacheAfterInsert = database.trackSummaryCache
        assertNotNull(trackCacheAfterInsert)
        val lastPoint = requireNotNull(database.lastTrackPointCache)
        assertTrue(lastPoint.present)
        assertEquals(40.001, lastPoint.latitude, 0.0)
        assertEquals(-74.0, lastPoint.longitude, 0.0)
        assertEquals(11_000L, lastPoint.timestamp)

        repository.upsertRoads(listOf(
            MatchedRoad(
                "Cache Road",
                "[[-74.0,40.0],[-74.0,40.001]]",
                1_000L,
                11_000L,
                0.99
            )
        ))
        // A road write cannot invalidate the independent track aggregate.
        assertSame(trackCacheAfterInsert, database.trackSummaryCache)
        assertNull(database.roadSummaryCache)

        val afterRoad = repository.getSummary()
        assertEquals(2L, afterRoad.trackPointCount)
        assertEquals(1L, afterRoad.roadSegmentCount)
        assertEquals(1L, afterRoad.roadsUnlockedCount)
        assertEquals(afterTrack.distanceMeters, afterRoad.distanceMeters, 0.0)
        assertNotNull(database.roadSummaryCache)
    }

    @Test fun rawDatabaseEscapeHatchDisablesCacheBeforeExternalMutation() {
        repository.getSummary()
        assertTrue(database.summaryCachingEnabled)

        val raw = repository.readableDatabase()
        assertFalse(database.summaryCachingEnabled)
        assertNull(database.trackSummaryCache)
        assertNull(database.roadSummaryCache)
        assertNull(database.lastTrackPointCache)

        raw.execSQL(
            "INSERT INTO track_points(" +
                "latitude, longitude, accuracy_m, speed_mps, bearing_deg, timestamp_ms" +
                ") VALUES (40.0, -74.0, 5.0, 0.0, 0.0, 1000)"
        )
        assertEquals(1L, repository.getSummary().trackPointCount)

        raw.execSQL("DELETE FROM track_points")
        assertEquals(0L, repository.getSummary().trackPointCount)
    }

    @Test fun repeatedKnownRoadVisitsKeepSharedCountsAndExactTrackSummary() {
        repository.upsertRoads(listOf(MatchedRoad(
            "Saved road", "[[-74,40],[-74,40.001]]", 1_000L, 11_000L, 0.99)))
        val segment = repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0).single().segmentId
        repository.getSummary()
        val roadCache = requireNotNull(database.roadSummaryCache)
        val otherRepository = TrackingRepository(context)

        repeat(20) { index ->
            val timestamp = 2_000_000L + index * 600_000L
            val id = repository.insertLocation(fix(40.0 + index * 0.00001, timestamp))
            val trackCache = requireNotNull(database.trackSummaryCache)
            assertEquals(1, otherRepository.completeKnownRoadVisits(listOf(
                com.roadconquest.app.matching.KnownRoadVisit(segment, listOf(id), timestamp, timestamp))))
            assertSame(roadCache, database.roadSummaryCache)
            assertSame(trackCache, database.trackSummaryCache)
            val summary = repository.getSummary()
            assertEquals(TrackingRepository.summaryOf(database.readableDatabase)
                .copy(historyGeneration = summary.historyGeneration), summary)
            assertEquals(index + 1L, summary.trackPointCount)
            assertEquals(1L, summary.roadSegmentCount)
            assertEquals(1L, summary.roadsUnlockedCount)
            assertEquals(timestamp, repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0)
                .single().lastDrivenAt)
            assertTrue(repository.loadMatchingWindow().points.isEmpty())
        }
        // New road identities still invalidate the shared count cache.
        otherRepository.upsertRoads(listOf(MatchedRoad(
            "Another road", "[[-74,40.01],[-74,40.011]]", 1_000L, 11_000L, 0.99)))
        assertNull(database.roadSummaryCache)
        assertEquals(2L, repository.getSummary().roadSegmentCount)
    }

    @Test fun failedKnownRoadCompletionPreservesCacheAndPendingFixForRetry() {
        repository.upsertRoads(listOf(MatchedRoad(
            "Saved road", "[[-74,40],[-74,40.001]]", 1_000L, 11_000L, 0.99)))
        val segment = repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0).single().segmentId
        val timestamp = 2_000_000L
        val id = repository.insertLocation(fix(40.0, timestamp))
        repository.getSummary()
        val roadCache = requireNotNull(database.roadSummaryCache)
        val visit = com.roadconquest.app.matching.KnownRoadVisit(segment, listOf(id), timestamp, timestamp)
        assertThrows(IllegalStateException::class.java) {
            repository.completeKnownRoadVisits(listOf(visit.copy(pointIds = listOf(id, id + 100_000L))))
        }
        assertSame(roadCache, database.roadSummaryCache)
        assertEquals(listOf(id), repository.loadMatchingWindow().points.map { it.id })
        assertEquals(1, repository.completeKnownRoadVisits(listOf(visit)))
        assertSame(roadCache, database.roadSummaryCache)
        assertTrue(repository.loadMatchingWindow().points.isEmpty())
    }
}
