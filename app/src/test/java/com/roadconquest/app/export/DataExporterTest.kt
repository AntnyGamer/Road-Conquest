package com.roadconquest.app.export

import android.location.Location
import com.roadconquest.app.data.MatchedRoad
import com.roadconquest.app.data.ProgressionRepository
import com.roadconquest.app.data.TrackingRepository
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class DataExporterTest {
    @Test fun zipBudgetCountsExpandedBytesAcrossEntriesIncludingSingleByteWrites() {
        val output = ByteArrayOutputStream()
        DataExporter.BoundedZipOutputStream(output, expandedLimit = 1024).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("first.csv"))
            zip.write(ByteArray(512))
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("second.csv"))
            repeat(512) { zip.write(0) }
            assertThrows(IOException::class.java) { zip.write(0) }
        }
        assertTrue("Small compressed files must still obey the expanded limit", output.size() < 1024)
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            assertNotNull(zip.nextEntry); assertEquals(512, zip.readBytes().size)
            assertNotNull(zip.nextEntry); assertEquals(512, zip.readBytes().size)
        }
    }

    @Test fun exportsReadableZipWithAccurateCountsAndSafeRoadNames() {
        val context = RuntimeEnvironment.getApplication()
        val repository = TrackingRepository(context)
        ProgressionRepository(context).clearProgression()
        val db = repository.readableDatabase()
        db.beginTransaction()
        try {
            for (table in listOf("track_points", "skipped_driving_fixes", "roads", "explored_places", "explored_grid"))
                db.execSQL("DELETE FROM $table")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        repository.recordExploredPlace(Location("gps").apply {
            latitude = 40.01; longitude = -74.0; accuracy = 5f
        })
        repository.recordExploredGridCell(Location("gps").apply {
            latitude = 40.01; longitude = -74.0; accuracy = 5f
        })
        repository.insertLocation(Location("gps").apply {
            latitude = 40.0; longitude = -74.0; accuracy = 5f; time = 1_000_000
        })
        repository.recordSkippedDrivingFix(Location("gps").apply {
            latitude = 40.0001; longitude = -74.0; accuracy = 3f
            time = 1_003_000
        })
        repository.upsertRoads(listOf(MatchedRoad("=1+1", "[[-74,40],[-74.001,40]]", 100, 200, 1.0)))
        val output = ByteArrayOutputStream()
        DataExporter.writeZip(context, repository, output)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(setOf(
            "metadata.json", "track_points.csv", "skipped_driving_fixes.csv", "roads.csv", "road_visits.csv", "explored_places.csv", "explored_grid.csv",
            "visited_places.csv", "place_candidates.csv", "progression_rewards.csv",
            "progression_purchases.csv", "progression_counters.csv"
        ), entries.keys)
        val metadata = JSONObject(entries.getValue("metadata.json"))
        assertEquals(1L, metadata.getLong("track_point_count"))
        assertEquals(1L, metadata.getLong("road_segment_count"))
        assertEquals(12, metadata.getInt("schema_version"))
        assertEquals(1L, metadata.getLong("explored_mile_grid_count"))
        assertEquals(com.roadconquest.app.BuildConfig.VERSION_NAME, metadata.getString("app_version"))
        assertEquals(com.roadconquest.app.BuildConfig.VERSION_CODE, metadata.getInt("app_version_code"))
        assertEquals(1L, metadata.getLong("explored_place_count"))
        assertEquals(1L, metadata.getLong("road_visit_count"))
        assertEquals(0L, metadata.getLong("points_balance"))
        assertEquals(0L, metadata.getLong("towns_visited"))
        assertEquals(1, entries.getValue("visited_places.csv").lineSequence().filter { it.isNotEmpty() }.count())
        assertEquals(2, entries.getValue("explored_places.csv").lineSequence().filter { it.isNotEmpty() }.count())
        assertEquals(2, entries.getValue("explored_grid.csv").lineSequence().filter { it.isNotEmpty() }.count())
        assertTrue(entries.getValue("explored_grid.csv").lineSequence().first().endsWith("first_unlocked_utc"))
        assertEquals(1L, metadata.getLong("roads_unlocked_count"))
        assertTrue(entries.getValue("track_points.csv").lineSequence().first().endsWith(",distance_m"))
        assertEquals(2, entries.getValue("track_points.csv").lineSequence().filter { it.isNotEmpty() }.count())
        assertEquals(2, entries.getValue("skipped_driving_fixes.csv").lineSequence().filter { it.isNotEmpty() }.count())
        // The streaming serializer must preserve the old list/join CSV bytes.
        val originalSkippedRow = repository.readableDatabase().rawQuery(
            "SELECT id,latitude,longitude,accuracy_m,speed_mps,bearing_deg,timestamp_ms FROM skipped_driving_fixes", null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            listOf(
                cursor.getLong(0).toString(), cursor.getDouble(1).toString(),
                cursor.getDouble(2).toString(), cursor.getFloat(3).toString(),
                cursor.getFloat(4).toString(), cursor.getFloat(5).toString(),
                java.time.Instant.ofEpochMilli(cursor.getLong(6)).toString()
            ).joinToString(",")
        }
        assertEquals(
            "id,latitude,longitude,accuracy_m,speed_mps,bearing_deg,timestamp_utc\n$originalSkippedRow\n",
            entries.getValue("skipped_driving_fixes.csv")
        )
        assertTrue(entries.getValue("roads.csv").contains(",'=1+1,"))
        assertTrue(entries.getValue("roads.csv").lineSequence().first().contains("road_group_id"))
        assertTrue(entries.getValue("roads.csv").lineSequence().first().contains("times_driven"))
        assertEquals(2, entries.getValue("road_visits.csv").lineSequence().filter { it.isNotEmpty() }.count())
    }

    @Test fun zeroPointBaselineIsExportedButDoesNotInflateDiscoveryTotals() {
        val context = RuntimeEnvironment.getApplication()
        val progression = ProgressionRepository(context)
        progression.clearProgression()
        val fix = Location("gps").apply {
            latitude = 39.7
            longitude = -75.1
            accuracy = 5f
            time = 1_000L
        }
        assertTrue(progression.recordBaselineCandidate(fix))
        val candidate = progression.pendingPlaceCandidates(nowMillis = 2_000L).single()
        assertEquals(
            0,
            progression.resolveCandidate(
                candidate,
                listOf(
                    com.roadconquest.app.data.PlaceDiscovery(
                        com.roadconquest.app.data.PlaceKind.COUNTRY,
                        "us",
                        "United States",
                        visitedAt = candidate.visitedAt,
                        latitude = candidate.latitude,
                        longitude = candidate.longitude
                    ),
                    com.roadconquest.app.data.PlaceDiscovery(
                        com.roadconquest.app.data.PlaceKind.STATE,
                        "us|new jersey",
                        "New Jersey",
                        countryName = "United States",
                        visitedAt = candidate.visitedAt,
                        latitude = candidate.latitude,
                        longitude = candidate.longitude
                    ),
                    com.roadconquest.app.data.PlaceDiscovery(
                        com.roadconquest.app.data.PlaceKind.TOWN,
                        "us|new jersey|glassboro",
                        "Glassboro",
                        parentName = "New Jersey",
                        countryName = "United States",
                        visitedAt = candidate.visitedAt,
                        latitude = candidate.latitude,
                        longitude = candidate.longitude
                    )
                )
            )
        )

        val output = ByteArrayOutputStream()
        DataExporter.writeZip(context, TrackingRepository(context), output)
        val entries = readEntries(output.toByteArray())
        val metadata = JSONObject(entries.getValue("metadata.json"))
        assertEquals(0L, metadata.getLong("towns_visited"))
        assertEquals(0L, metadata.getLong("states_regions_visited"))
        assertEquals(0L, metadata.getLong("countries_visited"))
        assertEquals(4, entries.getValue("visited_places.csv").lineSequence().count { it.isNotEmpty() })
    }

    @Config(sdk = [31, 35, 37], manifest = Config.NONE)
    @Test fun exportSnapshotStaysConsistentAndReleasesDatabaseBeforeSlowOutput() {
        val context = RuntimeEnvironment.getApplication()
        val repository = TrackingRepository(context)
        repository.readableDatabase().execSQL("DELETE FROM track_points")
        repository.readableDatabase().execSQL("DELETE FROM roads")
        repository.insertLocation(Location("gps").apply {
            latitude = 40.0; longitude = -74.0; accuracy = 5f; time = 1_000_000
        })
        val writer = Executors.newSingleThreadExecutor()
        val bytes = ByteArrayOutputStream()
        var inserted = false
        val output = object : OutputStream() {
            override fun write(value: Int) { onFirstWrite(); bytes.write(value) }
            override fun write(data: ByteArray, offset: Int, length: Int) {
                onFirstWrite(); bytes.write(data, offset, length)
            }
            private fun onFirstWrite() {
                if (inserted) return
                inserted = true
                // A different repository shares the production database pool. This would
                // time out on Android 12 if ZIP output still held a write transaction.
                writer.submit {
                    TrackingRepository(context).apply {
                        insertLocation(Location("gps").apply {
                            latitude = 40.001; longitude = -74.0; accuracy = 5f; time = 1_010_000
                        })
                        upsertRoads(listOf(MatchedRoad("New road", "[[-74,40],[-74,40.001]]", 100, 200, 1.0)))
                    }
                }.get(5, TimeUnit.SECONDS)
            }
        }
        try { DataExporter.writeZip(context, repository, output) } finally { writer.shutdownNow() }
        assertTrue(inserted)
        val entries = readEntries(bytes.toByteArray())
        val metadata = JSONObject(entries.getValue("metadata.json"))
        assertEquals(1L, metadata.getLong("track_point_count"))
        assertEquals(0L, metadata.getLong("road_segment_count"))
        assertEquals(0.0, metadata.getDouble("distance_meters"), 0.0)
        assertEquals(2, entries.getValue("track_points.csv").lineSequence().filter { it.isNotEmpty() }.count())
        assertEquals(1, entries.getValue("roads.csv").lineSequence().filter { it.isNotEmpty() }.count())
        assertEquals(2L, repository.getSummary().trackPointCount)
        assertEquals(1L, repository.getSummary().roadsUnlockedCount)
        assertTrue(repository.getSummary().distanceMeters > 100.0)
        assertTrue(context.cacheDir.listFiles()!!.none { it.name.startsWith("roadconquest-export-") })
    }

    @Test fun failedExportCleansUpSnapshotAndPreservesTrackingData() {
        val context = RuntimeEnvironment.getApplication()
        val repository = TrackingRepository(context)
        val summary = repository.getSummary()
        val output = object : OutputStream() {
            override fun write(value: Int) { throw IOException("simulated destination failure") }
            override fun write(data: ByteArray, offset: Int, length: Int) { throw IOException("simulated destination failure") }
        }
        assertThrows(IOException::class.java) { DataExporter.writeZip(context, repository, output) }
        assertEquals(summary, repository.getSummary())
        assertTrue(context.cacheDir.listFiles()!!.none { it.name.startsWith("roadconquest-export-") })
    }

    @Test fun interruptedExportSnapshotsAndSidecarsArePurged() {
        val context = RuntimeEnvironment.getApplication()
        val database = File(context.cacheDir, "roadconquest-export-interrupted.db")
        val wal = File(context.cacheDir, database.name + "-wal")
        val shm = File(context.cacheDir, database.name + "-shm")
        database.writeText("orphaned private history")
        wal.writeText("orphaned wal")
        shm.writeText("orphaned shm")

        DataExporter.clearTemporarySnapshots(context)

        assertFalse(database.exists())
        assertFalse(wal.exists())
        assertFalse(shm.exists())
        assertTrue(context.cacheDir.listFiles().orEmpty().none {
            it.name.startsWith("roadconquest-export-")
        })
    }


    private fun readEntries(bytes: ByteArray): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return entries
    }
}
