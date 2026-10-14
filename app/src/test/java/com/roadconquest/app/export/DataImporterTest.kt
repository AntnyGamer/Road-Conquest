package com.roadconquest.app.export

import android.content.Context
import android.location.Location
import com.roadconquest.app.data.*
import com.roadconquest.app.util.Prefs
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class DataImporterTest {
    private lateinit var context: Context
    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        TrackingRepository(context).clearHistory()
        Prefs.setDeviceDataDeletionPending(context, false)
        DataImporter.previousBackup(context).delete()
    }

    private fun fix(time: Long, lon: Double = -74.0) = Location("gps").apply {
        latitude = 40.0; longitude = lon; accuracy = 5f; this.time = time
    }
    private fun export(): ByteArray = ByteArrayOutputStream().also {
        DataExporter.writeZip(context, TrackingRepository(context), it)
    }.toByteArray()
    private fun entries(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes().toString(Charsets.UTF_8)) }
        }
    }
    private fun repack(values: Map<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> values.forEach { (name, value) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
        } }
    }.toByteArray()

    @Test fun restoresEveryTableAndKeepsRecoveryWithoutUploadingOrDuplicatingRewards() {
        val tracker = TrackingRepository(context)
        tracker.insertLocation(fix(1_000_000)); tracker.insertLocation(fix(1_005_000, -74.0001))
        tracker.recordExploredPlace(fix(1_005_000)); tracker.recordExploredGridCell(fix(1_005_000))
        tracker.upsertRoads(listOf(MatchedRoad("'=Main, \"North\"\nRoad", "[[-74,40],[-74.001,40]]", 1_000_000, 1_005_000, 1.0)))
        val progression = ProgressionRepository(context)
        progression.recordPlace(PlaceDiscovery(PlaceKind.TOWN, "us|nj|town", "Town", visitedAt = 1_000_000, latitude = 40.0, longitude = -74.0))
        progression.recordPlaceCandidate(fix(1_005_000))
        progression.recordBatteryPercent(5)
        progression.syncRoadRewards(1)
        progression.awardAchievement("roads_100", 350)
        val original = export()
        val stale = TrackingRepository(context)
        DataImporter.prepare(context, ByteArrayInputStream(original)).use { backup ->
            tracker.insertLocation(fix(2_000_000))
            Prefs.setDriveVerificationEnabled(context, true)
            DataImporter.restore(context, backup)
        }
        val restored = entries(export()).filterKeys { it != "metadata.json" }
        assertEquals(entries(original).filterKeys { it != "metadata.json" }, restored)
        assertEquals(3L, JSONObject(entries(DataImporter.previousBackup(context).readBytes()).getValue("metadata.json")).getLong("track_point_count"))
        assertTrue(Prefs.isManualOnly(context))
        assertFalse(Prefs.isDriveVerificationEnabled(context))
        assertEquals(-1L, stale.insertLocation(fix(3_000_000)))
        assertEquals(2L, TrackingRepository(context).getSummary().trackPointCount)
        assertTrue(com.roadconquest.app.achievements.Achievements.progress(context,
            TrackingRepository(context).getSummary(), com.roadconquest.app.progression.ProgressionManager.metrics(
                ProgressionRepository(context).snapshot())).single { it.id == "roads_100" }.unlocked)
        // Re-importing the same reward ledger does not mint another copy of the points.
        val points = ProgressionRepository(context).snapshot().balance
        DataImporter.prepare(context, ByteArrayInputStream(original)).use { DataImporter.restore(context, it) }
        assertEquals(points, ProgressionRepository(context).snapshot().balance)
    }

    @Test fun rejectsIncompleteUnsafeNonfiniteAndInconsistentBackupsBeforeChangingProgress() {
        TrackingRepository(context).insertLocation(fix(1_000_000))
        val original = export()
        val source = entries(original)
        val invalid = listOf(
            source - "roads.csv",
            source + ("../escape.csv" to "bad"),
            source + ("track_points.csv" to source.getValue("track_points.csv").replace("40.0", "NaN")),
            source + ("metadata.json" to JSONObject(source.getValue("metadata.json")).put("track_point_count", 900).toString()),
            source + ("metadata.json" to JSONObject(source.getValue("metadata.json")).put("schema_version", 100).toString()),
            source + ("road_visits.csv" to "segment_id,started_at_utc,ended_at_utc\nmissing,1970-01-01T00:00:01Z,1970-01-01T00:00:02Z\n")
        )
        invalid.forEach { candidate ->
            assertTrue(runCatching { DataImporter.prepare(context, ByteArrayInputStream(repack(candidate))).use { } }.isFailure)
            assertEquals(1L, TrackingRepository(context).getSummary().trackPointCount)
            assertFalse(DataImporter.previousBackup(context).exists())
        }
        assertTrue(runCatching { DataImporter.prepare(context, ByteArrayInputStream(original.copyOf(original.size / 2))).use { } }.isFailure)
    }

    @Test fun rejectsPreparedBackupAfterDeletionAndCannotResurrectOldProgress() {
        val bytes = export()
        DataImporter.prepare(context, ByteArrayInputStream(bytes)).use { backup ->
            TrackingRepository(context).clearHistory()
            assertTrue(runCatching { DataImporter.restore(context, backup) }.isFailure)
        }
        assertEquals(0L, TrackingRepository(context).getSummary().trackPointCount)
    }

    @Test fun acceptsLegacySchemaNineAndDecodesEscapedNegativeCandidateCoordinates() {
        ProgressionRepository(context).recordPlaceCandidate(fix(1_000_000))
        val values = entries(export()).toMutableMap()
        values["metadata.json"] = JSONObject(values.getValue("metadata.json")).put("schema_version", 9).toString()
        DataImporter.prepare(context, ByteArrayInputStream(repack(values))).use { DataImporter.restore(context, it) }
        assertEquals(values.getValue("place_candidates.csv"), entries(export()).getValue("place_candidates.csv"))
    }

    @Test fun restoreWaitingForAnExportDoesNotDeadlockOrdinaryHistoryReaders() {
        TrackingRepository(context).insertLocation(fix(1_000_000))
        DataImporter.prepare(context, ByteArrayInputStream(export())).use { backup ->
            val reader = TrackingRepository(context)
            val writing = java.util.concurrent.CountDownLatch(1)
            val finishExport = java.util.concurrent.CountDownLatch(1)
            val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val exporter = Thread {
                runCatching {
                    DataExporter.writeZip(context, TrackingRepository(context), object : java.io.OutputStream() {
                        override fun write(value: Int) {
                            writing.countDown()
                            check(finishExport.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        }
                    })
                }.exceptionOrNull()?.let { failures.add(it) }
            }
            exporter.start()
            assertTrue(writing.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val restorer = Thread { runCatching { DataImporter.restore(context, backup) }.exceptionOrNull()?.let { failures.add(it) } }
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                restorer.start()
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                while (restorer.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(5)
                assertEquals(Thread.State.BLOCKED, restorer.state)
                assertEquals(1L, executor.submit<Long> { reader.getSummary().trackPointCount }.get(3, java.util.concurrent.TimeUnit.SECONDS).toLong())
            } finally {
                finishExport.countDown()
                exporter.join(5000); restorer.join(5000)
                executor.shutdownNow()
            }
            assertFalse(exporter.isAlive || restorer.isAlive)
            assertTrue(failures.toString(), failures.isEmpty())
        }
    }
}
