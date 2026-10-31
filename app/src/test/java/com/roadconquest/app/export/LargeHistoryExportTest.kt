package com.roadconquest.app.export

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
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class LargeHistoryExportTest {
    @Test fun exportsEveryRecordAcrossMultipleCursorWindows() {
        val app = RuntimeEnvironment.getApplication()
        val repository = TrackingRepository(app)
        val db = repository.readableDatabase()
        db.execSQL("DELETE FROM roads")
        db.execSQL("DELETE FROM track_points")
        val count = 5_000
        val namePadding = "x".repeat(640)
        db.beginTransaction()
        try {
            db.compileStatement("INSERT INTO roads(segment_id,name,geometry_json,first_unlocked_at,last_driven_at,drive_count,drive_count_exact,road_group_id,min_lat,max_lat,min_lon,max_lon) VALUES (?, ?, '[[-74,40],[-73.999,40]]', 1000, 2000, 1, 1, ?, 40, 40, -74, -73.999)").use { statement ->
                repeat(count) { index ->
                    statement.bindString(1, "road-$index")
                    // Over 3 MB of names alone ensures the road cursor refills its window.
                    statement.bindString(2, "Road $index $namePadding")
                    statement.bindString(3, "road-$index")
                    statement.executeInsert()
                }
            }
            db.compileStatement("INSERT INTO track_points (latitude,longitude,accuracy_m,speed_mps,bearing_deg,timestamp_ms,distance_m) VALUES (40,-74,5,5,0,?,1)").use { statement ->
                repeat(count) { index -> statement.bindLong(1, 1_000L + index * 1_000L); statement.executeInsert() }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val output = ByteArrayOutputStream()
        DataExporter.writeZip(app, repository, output)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        val metadata = JSONObject(entries.getValue("metadata.json"))
        assertEquals(count.toLong(), metadata.getLong("track_point_count"))
        assertEquals(count.toLong(), metadata.getLong("road_segment_count"))
        assertEquals(count.toLong(), metadata.getLong("roads_unlocked_count"))
        assertEquals(count.toDouble(), metadata.getDouble("distance_meters"), 0.0)
        assertEquals(count + 1, entries.getValue("track_points.csv").lineSequence().count { it.isNotEmpty() })
        assertEquals(count + 1, entries.getValue("roads.csv").lineSequence().count { it.isNotEmpty() })
        assertTrue(entries.getValue("roads.csv").contains("road-4999,"))
        assertTrue(app.cacheDir.listFiles()!!.none { it.name.startsWith("roadconquest-export-") })
    }
}
