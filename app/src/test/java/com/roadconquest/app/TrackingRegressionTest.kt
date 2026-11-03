package com.roadconquest.app

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import android.os.SystemClock
import com.roadconquest.app.account.VerifiedDriving
import com.roadconquest.app.data.*
import com.roadconquest.app.matching.RoadMatcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.concurrent.*

/** Regression coverage for tracking persistence, reward ownership and snapshot concurrency. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TrackingRegressionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun road(name: String, a: Double, b: Double, at: Long) =
        MatchedRoad(name, "[[$a,40],[$b,40]]", at, at + 1000L, 1.0)

    @Test fun aGenuinelyNewRoadStillEarnsPointsAfterOldGroupsMerge() {
        TrackingRepository(app).clearHistory()
        val tracking = TrackingRepository(app)
        val progression = ProgressionRepository(app)
        tracking.upsertRoads(listOf(road("Main Street", -74.010, -74.009, 1000),
            road("Main Street", -74.006, -74.005, 3000)))
        assertEquals(2L, tracking.getSummary().roadsUnlockedCount)
        progression.syncRoadRewards(2)
        assertEquals(4L, progression.snapshot().balance)
        // Real matcher geometry bridges previously separate fragments of the same street.
        tracking.upsertRoads(listOf(road("Main Street", -74.009, -74.006, 5000)))
        assertEquals(1L, tracking.getSummary().roadsUnlockedCount)
        progression.syncRoadRewards(1)
        val beforeNewRoad = progression.snapshot().balance
        tracking.upsertRoads(listOf(road("Entirely New Avenue", -73.90, -73.899, 7000)))
        assertEquals(2L, tracking.getSummary().roadsUnlockedCount)
        progression.syncRoadRewards(tracking.getSummary().roadsUnlockedCount)
        assertEquals("A distinct new road should earn its advertised two points", 2L,
            progression.snapshot().balance - beforeNewRoad)
    }

    @Test fun repeatingExtendingAndRekeyingAnExistingRoadNeverPaysTwice() {
        val tracking = TrackingRepository(app)
        tracking.clearHistory()
        val current = TrackingRepository(app)
        val progression = ProgressionRepository(app)
        current.upsertRoads(listOf(road("Main Street", -74.010, -74.009, 1000)))
        assertEquals(2L, progression.snapshot().balance)
        current.upsertRoads(listOf(road("Main Street", -74.010, -74.009, 3000)))
        current.upsertRoads(listOf(road("Main Street", -74.009, -74.008, 5000)))
        assertEquals(2L, progression.snapshot().balance)
        assertEquals(1L, progression.snapshot().rewardedRoads)
        // A newly supplied route reference changes the group's canonical key,
        // while retaining its existing road identity and reward ownership.
        current.upsertRoads(listOf(road("Main Street", -74.010, -74.009, 7000).copy(reference = "US 1")))
        assertEquals(2L, progression.snapshot().balance)
        assertEquals(1L, progression.snapshot().rewardedRoads)
    }

    @Test fun aFailedGeometryWriteRollsBackItsRewardAndCanBeRetried() {
        TrackingRepository(app).clearHistory()
        val tracking = TrackingRepository(app)
        val progression = ProgressionRepository(app)
        val db = tracking.readableDatabase()
        db.execSQL("CREATE TRIGGER reject_road BEFORE INSERT ON roads BEGIN SELECT RAISE(ABORT, 'road fixture failure'); END")
        try {
            assertTrue(runCatching { tracking.upsertRoads(listOf(road("New Road", -74.010, -74.009, 1000))) }.isFailure)
            assertEquals(0L, progression.snapshot().balance)
            assertEquals(0L, progression.snapshot().rewardedRoads)
        } finally { db.execSQL("DROP TRIGGER reject_road") }
        tracking.upsertRoads(listOf(road("New Road", -74.010, -74.009, 1000)))
        assertEquals(2L, progression.snapshot().balance)
        assertEquals(1L, progression.snapshot().rewardedRoads)
    }

    @Test fun snapshotBindingsPreserveNullIntegerRealTextAndBlobValues() {
        TrackingRepository(app).clearHistory()
        val repository = TrackingRepository(app)
        val source = repository.readableDatabase()
        source.execSQL("ALTER TABLE track_points ADD COLUMN snapshot_value BLOB")
        val values = listOf(null, Long.MAX_VALUE, 1.23456789, "Straße 🛣️", byteArrayOf(0, 1, -1))
        for ((index, value) in values.withIndex()) {
            source.execSQL("""INSERT INTO track_points
                (latitude,longitude,accuracy_m,speed_mps,bearing_deg,timestamp_ms,distance_m,snapshot_value)
                VALUES (40,-74,3,8,90,?,10,?)""", arrayOf(index + 1000L, value))
        }
        val file = File.createTempFile("snapshot-types-", ".db", app.cacheDir)
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { destination ->
                repository.copyExportSnapshot(destination)
                source.rawQuery("SELECT snapshot_value FROM track_points ORDER BY id", null).use { original ->
                    destination.rawQuery("SELECT snapshot_value FROM track_points ORDER BY id", null).use { copied ->
                        assertEquals(original.count, copied.count)
                        while (original.moveToNext()) {
                            assertTrue(copied.moveToNext())
                            assertEquals(original.getType(0), copied.getType(0))
                            if (original.getType(0) == android.database.Cursor.FIELD_TYPE_BLOB)
                                assertArrayEquals(original.getBlob(0), copied.getBlob(0))
                            else assertEquals(original.getString(0), copied.getString(0))
                        }
                    }
                }
            }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }

    @Test fun optionalCandidateFailureMustNotStopCompressedGpsTracking() {
        TrackingRepository(app).clearHistory()
        val tracking = TrackingRepository(app)
        val db = tracking.readableDatabase()
        db.execSQL("CREATE TRIGGER review_candidate_failure BEFORE INSERT ON place_candidates BEGIN SELECT RAISE(ABORT, 'optional candidate fixture failure'); END")
        val controller = Robolectric.buildService(TrackingService::class.java)
        val service = controller.get()
        fun field(name: String) = TrackingService::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("repository").set(service, tracking)
        field("progressionRepository").set(service, ProgressionRepository(app))
        field("verifiedDriving").set(service, VerifiedDriving(app))
        field("matcher").set(service, object : RoadMatcher {
            override val unavailable = true
            override fun rateLimitRemainingMs() = 300_000L
            override fun match(points: List<TrackPoint>) = null
        })
        field("ready").setBoolean(service, true)
        val storage = field("storageExecutor").get(service) as ExecutorService
        MainActivity.trackingReceiverRegistered = true
        try {
            repeat(2) { i ->
                ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
                service.onLocationChanged(Location("gps").apply {
                    latitude = 40.0 + i * 0.0006; longitude = -74.0
                    accuracy = 5f; speed = 22f; bearing = 0f
                    time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                })
                storage.submit {}.get(5, TimeUnit.SECONDS)
            }
            assertFalse("Retained-fix branch already tolerates optional candidate failure",
                Shadows.shadowOf(service).isStoppedBySelf)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
            service.onLocationChanged(Location("gps").apply {
                latitude = 40.0012; longitude = -74.0
                accuracy = 5f; speed = 22f; bearing = 0f
                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })
            storage.submit {}.get(5, TimeUnit.SECONDS)
            db.rawQuery("SELECT COUNT(*) FROM skipped_driving_fixes", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0))
            }
            assertFalse("The successfully saved compressed fix must survive optional place-candidate failure without stopping tracking",
                Shadows.shadowOf(service).isStoppedBySelf)
        } finally {
            db.execSQL("DROP TRIGGER review_candidate_failure")
            MainActivity.trackingReceiverRegistered = false
            field("ready").setBoolean(service, false)
            controller.destroy()
            storage.awaitTermination(10, TimeUnit.SECONDS)
            (field("matchingExecutor").get(service) as ExecutorService).awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Config(sdk = [31, 37], manifest = Config.NONE, application = Application::class)
    @Test fun snapshotCopyShouldNotReserveTheSourceDatabaseWriter() {
        TrackingRepository(app).clearHistory()
        val repository = TrackingRepository(app)
        val destinationFile = File.createTempFile("review-copy-", ".db", app.cacheDir)
        val destination = SQLiteDatabase.openOrCreateDatabase(destinationFile, null)
        val executor = Executors.newFixedThreadPool(2)
        val copying = CountDownLatch(1)
        var copyThread: Thread? = null
        var writerCompleted = false
        // Delay destination writes at a real SQLite pool boundary, after the source
        // transaction starts. No slow-file-provider timing or large dataset is needed.
        destination.beginTransaction()
        try {
            val copy = executor.submit {
                copyThread = Thread.currentThread(); copying.countDown()
                repository.copyExportSnapshot(destination)
            }
            assertTrue(copying.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline && copyThread!!.stackTrace.none {
                it.className.contains("SQLiteConnectionPool") && it.methodName == "waitForConnection"
            }) Thread.sleep(10)
            assertFalse("Copy must be waiting on the held destination transaction", copy.isDone)
            val writer = executor.submit {
                TrackingRepository(app).insertLocation(Location("gps").apply {
                    latitude = 40.0; longitude = -74.0; accuracy = 5f; time = 1000L
                })
            }
            writerCompleted = try { writer.get(500, TimeUnit.MILLISECONDS); true }
                catch (_: TimeoutException) { false }
        } finally {
            destination.endTransaction()
            executor.shutdown()
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
            destination.close()
            SQLiteDatabase.deleteDatabase(destinationFile)
        }
        assertTrue("GPS writes should complete while a read-only export snapshot is copying", writerCompleted)
    }
}
