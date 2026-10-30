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
    // CSV schema validation is tested independently from the signing trust boundary.
    private fun prepareFixture(context: Context, input: java.io.InputStream) =
        DataImporter.prepare(context, input) { }
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

    @Test fun earnedTownMedalsSurviveFullBackupRestore() {
        val generation = TrackingRepository(context).getSummary().historyGeneration
        val progression = ProgressionRepository(context)
        progression.recordTownCompletion("backup-town", 3, generation)
        progression.awardAchievement("town_gold_1", 750)
        val original = export()
        TrackingRepository(context).clearHistory()
        prepareFixture(context, ByteArrayInputStream(original)).use { DataImporter.restore(context, it) }
        val restored = ProgressionRepository(context).snapshot()
        assertEquals(1L, restored.bronzeTowns)
        assertEquals(1L, restored.silverTowns)
        assertEquals(1L, restored.goldTowns)
        assertEquals(750L, restored.rewardedAchievementPoints["town_gold_1"])
    }

    @Test fun restoredHistoryAndDelayedDiscoveriesCannotEarnWeeklyCreditButNewDrivingCan() {
        val now = System.currentTimeMillis()
        val week = WeeklyChallenges.weekStart(now)
        val time = minOf(week + 1_000L, now)
        val tracker = TrackingRepository(context)
        val first = tracker.insertLocation(fix(time))
        tracker.recordSkippedDrivingFix(fix(now - 500L, -73.95))
        repeat(25) { i -> tracker.upsertRoads(listOf(MatchedRoad("Imported $i",
            "[[-74,40],[${-73.999 + i * 0.001},40]]", time, time, 1.0))) }
        val progression = ProgressionRepository(context)
        progression.recordPlace(PlaceDiscovery(PlaceKind.TOWN, "start", "Start", visitedAt = time,
            latitude = 40.0, longitude = -74.0))
        progression.recordPlaceCandidate(fix(time, -73.9))
        // An unclaimed previous-week completion must not earn rewards through the grace pass.
        repeat(3) { i -> progression.recordPlace(PlaceDiscovery(PlaceKind.TOWN, "old$i", "Old $i",
            visitedAt = week - 1_000, latitude = 40.0, longitude = -74.0)) }
        val original = export()
        prepareFixture(context, ByteArrayInputStream(original)).use { DataImporter.restore(context, it) }
        val restoredTracker = TrackingRepository(context)
        assertTrue(restoredTracker.recoverTrailingSkippedDrivingFix())
        restoredTracker.readableDatabase().rawQuery("SELECT weekly_eligible FROM track_points ORDER BY id DESC LIMIT 1", null).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        val restoredProgression = ProgressionRepository(context)
        val before = restoredProgression.snapshot().balance
        assertEquals(listOf(0L, 0L, 0L, 0L), WeeklyChallenges.status(context, now).challenges.map { it.progress })
        assertEquals(before, restoredProgression.snapshot().balance)
        restoredTracker.completeMatch(listOf(MatchedRoad("Delayed import", "[[-74,40],[-74.002,40]]",
            time, time, 1.0, weeklyEligible = false)), listOf(first))
        val candidate = restoredProgression.pendingPlaceCandidates().single()
        assertFalse(candidate.weeklyEligible)
        restoredProgression.resolveCandidate(candidate, listOf(PlaceDiscovery(PlaceKind.TOWN, "delayed",
            "Delayed town", visitedAt = time, latitude = 40.0, longitude = -73.9)))
        assertEquals(listOf(0L, 0L, 0L, 0L), WeeklyChallenges.status(context, now).challenges.map { it.progress })
        val liveId = restoredTracker.insertLocation(fix(now, -73.8))
        restoredTracker.completeMatch(listOf(MatchedRoad("Fresh road", "[[-73.8,40],[-73.799,40]]",
            now, now, 1.0)), listOf(liveId))
        restoredProgression.recordPlaceCandidate(fix(now, -73.8))
        val live = restoredProgression.pendingPlaceCandidates().single()
        assertTrue(live.weeklyEligible)
        restoredProgression.resolveCandidate(live, listOf(PlaceDiscovery(PlaceKind.TOWN, "fresh",
            "Fresh town", visitedAt = now, latitude = 40.0, longitude = -73.8)))
        val goals = WeeklyChallenges.status(context, now).challenges
        assertEquals(listOf(1L, 1L, 1L, 1L), goals.map { it.progress })
        assertEquals(listOf(false, false, true, false), goals.map { it.rewarded })
        assertFalse(Prefs.isDriveVerificationEnabled(context))
    }

    @Test fun backfillingRestoredRawGpsCannotCreateWeeklyTownCredit() {
        val now = System.currentTimeMillis()
        val tracker = TrackingRepository(context)
        val progression = ProgressionRepository(context)
        assertTrue(progression.recordBaselineCandidate(fix(1_000)))
        val baseline = progression.pendingPlaceCandidates().single()
        progression.resolveCandidate(baseline, PlaceKind.entries.map {
            PlaceDiscovery(it, "start-${it.name}", "Start ${it.name}", visitedAt = 1_000,
                latitude = 40.0, longitude = -74.0)
        })
        tracker.insertLocation(fix(now - 1_000, -73.5))
        prepareFixture(context, ByteArrayInputStream(export())).use { DataImporter.restore(context, it) }
        val restored = ProgressionRepository(context)
        assertEquals(1, restored.backfillPlaceCandidates())
        val candidate = restored.pendingPlaceCandidates().single()
        assertFalse(candidate.weeklyEligible)
        restored.resolveCandidate(candidate, listOf(PlaceDiscovery(PlaceKind.TOWN, "backfill", "Backfilled town",
            visitedAt = candidate.visitedAt, latitude = candidate.latitude, longitude = candidate.longitude)))
        assertEquals(0L, WeeklyChallenges.status(context, now).challenges.last().progress)
    }

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
        prepareFixture(context, ByteArrayInputStream(original)).use { backup ->
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
        prepareFixture(context, ByteArrayInputStream(original)).use { DataImporter.restore(context, it) }
        assertEquals(points, ProgressionRepository(context).snapshot().balance)
    }

    @Test fun manyDistinctTypedRowsAndDerivedBoundsSurviveCsvRestoreExactly() {
        val tracker = TrackingRepository(context)
        tracker.insertLocations((0 until 500).map { i -> fix(1_000_000L + i * 3_000L, -74.0 + i * 0.00001).apply {
            accuracy = 1f + i % 20; speed = (i % 10).toFloat(); bearing = (i % 359).toFloat()
        } })
        tracker.readableDatabase().execSQL("UPDATE track_points SET matched=id%2, " +
            "next_match_attempt_ms=CASE WHEN id%3=0 THEN 4000000 ELSE 0 END")
        tracker.upsertRoads((0 until 4).map { i -> MatchedRoad(
            "CSV $i, \"quoted\"\né", "[[${-74.0 + i * 0.01},40],[${-73.999 + i * 0.01},${40.001 + i * 0.001}]]",
            1_000_000L + i * 10_000L, 1_005_000L + i * 10_000L, 0.99
        ) })
        val bytes = export()
        val original = entries(bytes).filterKeys { it != "metadata.json" }
        prepareFixture(context, ByteArrayInputStream(bytes)).use { DataImporter.restore(context, it) }
        assertEquals(original, entries(export()).filterKeys { it != "metadata.json" })
        assertEquals(500L, TrackingRepository(context).getSummary().trackPointCount)
    }

    @Test fun detailedSavedRoadOverFiftyThousandVerticesRestoresFromItsOwnExport() {
        // A provider can return a valid, >50k-point road. Backups must accept the
        // exact geometry already persisted by normal matching, not only a subset.
        val geometry = buildString {
            append('[')
            repeat(50_001) { index ->
                if (index != 0) append(',')
                append("[-74.")
                append(index.toString().padStart(7, '0'))
                append(",40.00000]")
            }
            append(']')
        }
        assertTrue(geometry.length > 1_000_000) // Also crosses the former CSV/JSON row limits.
        TrackingRepository(context).upsertRoads(
            listOf(MatchedRoad("Detailed Road", geometry, 1_000_000L, 1_005_000L, 0.99))
        )
        val original = export()
        prepareFixture(context, ByteArrayInputStream(original)).use {
            DataImporter.restore(context, it)
        }
        assertEquals(1L, TrackingRepository(context).getSummary().roadSegmentCount)
        assertEquals(entries(original).getValue("roads.csv"), entries(export()).getValue("roads.csv"))
    }

    @Test fun restoringProgressDoesNotClaimItWasAlreadyExported() {
        TrackingRepository(context).insertLocation(fix(1_000_000))
        val backup = export()
        val exportPrefs = context.getSharedPreferences(
            "roadconquest_export_history", Context.MODE_PRIVATE
        )
        exportPrefs.edit().putLong("last_completed_at", 123456789L).commit()
        prepareFixture(context, ByteArrayInputStream(backup)).use {
            DataImporter.restore(context, it)
        }
        assertEquals(0L, exportPrefs.getLong("last_completed_at", 0L))
    }

    @Test fun rejectsIncompleteUnsafeNonfiniteAndInconsistentBackupsBeforeChangingProgress() {
        TrackingRepository(context).insertLocation(fix(1_000_000))
        val original = export()
        val source = entries(original)
        val invalid = listOf(
            source - "roads.csv",
            source + ("../escape.csv" to "bad"),
            source + ("track_points.csv" to source.getValue("track_points.csv").replace("40.0", "NaN")),
            source + ("track_points.csv" to source.getValue("track_points.csv").replaceFirst(Regex("(?m)^[0-9]+,"), "9223372036854775807,")),
            source + ("metadata.json" to JSONObject(source.getValue("metadata.json")).put("track_point_count", 900).toString()),
            source + ("metadata.json" to JSONObject(source.getValue("metadata.json")).put("schema_version", 100).toString()),
            source + ("road_visits.csv" to "segment_id,started_at_utc,ended_at_utc\nmissing,1970-01-01T00:00:01Z,1970-01-01T00:00:02Z\n"),
            source + ("progression_counters.csv" to "counter_key,value\nrewarded_roads,-1\n"),
            source + ("progression_counters.csv" to "counter_key,value\nads_watched,9223372036854775807\n"),
            source + ("progression_counters.csv" to "counter_key,value\nlowest_battery_percent,4294967296\n")
        )
        invalid.forEach { candidate ->
            assertTrue(runCatching { prepareFixture(context, ByteArrayInputStream(repack(candidate))).use { } }.isFailure)
            assertEquals(1L, TrackingRepository(context).getSummary().trackPointCount)
            assertFalse(DataImporter.previousBackup(context).exists())
        }
        assertTrue(runCatching { prepareFixture(context, ByteArrayInputStream(original.copyOf(original.size / 2))).use { } }.isFailure)
    }

    @Test fun rejectsArchivesWithExcessEntriesBeforeMaterializingTheirContents() {
        val original = entries(export())
        val extras = (0 until 256).associate { "unexpected-$it.txt" to "" }
        val oversized = repack(original + extras)
        assertTrue(runCatching {
            prepareFixture(context, ByteArrayInputStream(oversized)).use { }
        }.isFailure)
        assertEquals(0L, TrackingRepository(context).getSummary().trackPointCount)
    }

    @Test fun confirmedRestoreCompletesAfterItsActivityIsDestroyed() {
        val tracker = TrackingRepository(context)
        tracker.insertLocation(fix(1_000_000))
        val original = export()
        tracker.insertLocation(fix(1_005_000, -74.0001))
        assertEquals(2L, tracker.getSummary().trackPointCount)
        val backup = prepareFixture(context, ByteArrayInputStream(original))
        val controller = org.robolectric.Robolectric.buildActivity(
            com.roadconquest.app.RestoreActivity::class.java
        ).create().start().resume()
        val activity = controller.get()
        val worker = com.roadconquest.app.RestoreActivity::class.java
            .getDeclaredField("worker").apply { isAccessible = true }
            .get(activity) as java.util.concurrent.ExecutorService
        try {
            // Force the confirmed job to remain pending while Android destroys
            // its Activity. An interrupt would make DataImporter reject the copy.
            synchronized(DataImporter.operationLock) {
                val restore = com.roadconquest.app.RestoreActivity::class.java
                    .getDeclaredMethod("restore", DataImporter.Prepared::class.java)
                    .apply { isAccessible = true }
                restore.invoke(activity, backup)
                controller.pause().stop().destroy()
            }
            assertTrue("A confirmed restore must finish after Activity destruction",
                worker.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1L, TrackingRepository(context).getSummary().trackPointCount)
            assertTrue("Visible maps must be notified after a committed restore",
                org.robolectric.Shadows.shadowOf(context as android.app.Application).broadcastIntents.any {
                    it.action == DataImporter.ACTION_LOCAL_PROGRESS_RESTORED &&
                        it.`package` == context.packageName
                })
        } finally {
            if (!worker.isShutdown) worker.shutdownNow()
        }
    }

    @Test @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    fun failedRestoreDoesNotKeepLaterUnconfirmedValidationRunningAfterClose() {
        val backup = prepareFixture(context, ByteArrayInputStream(export()))
        TrackingRepository(context).clearHistory() // Make the confirmed preview stale.
        val controller = org.robolectric.Robolectric.buildActivity(
            com.roadconquest.app.RestoreActivity::class.java
        ).create().start().resume()
        val activity = controller.get()
        val worker = com.roadconquest.app.RestoreActivity::class.java
            .getDeclaredField("worker").apply { isAccessible = true }
            .get(activity) as java.util.concurrent.ExecutorService
        val scanning = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var destroyed = false
        try {
            com.roadconquest.app.RestoreActivity::class.java
                .getDeclaredMethod("restore", DataImporter.Prepared::class.java)
                .apply { isAccessible = true }.invoke(activity, backup)
            worker.submit(Runnable {}).get(15, java.util.concurrent.TimeUnit.SECONDS)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

            // Start another preview after the failed restore. It has not been
            // confirmed and must remain cancellable when the screen closes.
            val open: () -> java.io.InputStream = {
                object : java.io.InputStream() {
                    override fun read(): Int {
                        scanning.countDown()
                        try { release.await() }
                        catch (_: InterruptedException) {
                            cancelled.countDown()
                            throw java.io.IOException("Preview cancelled")
                        }
                        return -1
                    }
                }
            }
            com.roadconquest.app.RestoreActivity::class.java
                .getDeclaredMethod("validate", Function0::class.java)
                .apply { isAccessible = true }.invoke(activity, open)
            assertTrue(scanning.await(5, java.util.concurrent.TimeUnit.SECONDS))
            controller.pause().stop().destroy()
            destroyed = true
            assertTrue("Closing an unconfirmed preview must interrupt its scan",
                cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            release.countDown()
            if (!destroyed) controller.pause().stop().destroy()
            worker.shutdownNow()
            worker.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)
            backup.close()
        }
    }

    @Test fun rejectsPreparedBackupAfterDeletionAndCannotResurrectOldProgress() {
        val bytes = export()
        prepareFixture(context, ByteArrayInputStream(bytes)).use { backup ->
            TrackingRepository(context).clearHistory()
            Prefs.setManualOnly(context, false)
            Prefs.setTrackingPaused(context, true)
            Prefs.setDriveVerificationEnabled(context, true)
            try {
                assertTrue(runCatching { DataImporter.restore(context, backup) }.isFailure)
                // A rejected restore must not stop tracking, unpause it, or revoke
                // the user's separate verified-driving consent.
                assertFalse(Prefs.isManualOnly(context))
                assertTrue(Prefs.isTrackingPaused(context))
                assertTrue(Prefs.isDriveVerificationEnabled(context))
            } finally {
                Prefs.setTrackingPaused(context, false)
                Prefs.setDriveVerificationEnabled(context, false)
            }
        }
        assertEquals(0L, TrackingRepository(context).getSummary().trackPointCount)
    }

    @Test fun failedRecoveryReplacementPreservesLiveProgressAndTheExistingTarget() {
        val tracker = TrackingRepository(context)
        tracker.insertLocation(fix(1_000_000))
        val original = export()
        tracker.insertLocation(fix(1_005_000, -74.0001))
        val before = entries(export()).filterKeys { it != "metadata.json" }
        val target = DataImporter.previousBackup(context)
        assertTrue(target.mkdir())
        val marker = java.io.File(target, "existing-recovery")
        marker.writeText("preserve")
        try {
            prepareFixture(context, ByteArrayInputStream(original)).use { backup ->
                assertTrue(runCatching { DataImporter.restore(context, backup) }.isFailure)
            }
            assertEquals(before, entries(export()).filterKeys { it != "metadata.json" })
            assertEquals("preserve", marker.readText())
            assertFalse(java.io.File(context.filesDir, "before-restore.tmp.zip").exists())
        } finally {
            marker.delete()
            target.delete()
        }
    }

    @Test fun restoringRebuildsAnExhaustedGpsIdCounterAndAllowsFutureDriving() {
        val originalId = TrackingRepository(context).insertLocation(fix(1_000_000))
        val bytes = export()
        AppDatabase.get(context).writableDatabase.execSQL(
            "UPDATE sqlite_sequence SET seq = ? WHERE name = 'track_points'", arrayOf(Long.MAX_VALUE)
        )
        prepareFixture(context, ByteArrayInputStream(bytes)).use { DataImporter.restore(context, it) }
        val fresh = TrackingRepository(context)
        assertEquals(originalId + 1, fresh.insertLocation(fix(1_005_000, -74.0001)))
        assertEquals(2L, fresh.getSummary().trackPointCount)
    }

    @Test fun acceptsLegacySchemaNineAndDecodesEscapedNegativeCandidateCoordinates() {
        ProgressionRepository(context).recordPlaceCandidate(fix(1_000_000))
        val values = entries(export()).toMutableMap()
        values.remove("skipped_driving_fixes.csv")
        values["metadata.json"] = JSONObject(values.getValue("metadata.json")).put("schema_version", 9).toString()
        // Legacy schema-9 and schema-10 had four grid columns, before
        // first-unlock times were introduced in format 11.
        values["explored_grid.csv"] = values.getValue("explored_grid.csv")
            .trimEnd('\n').lineSequence().joinToString("\n") { it.substringBeforeLast(',') } + "\n"
        prepareFixture(context, ByteArrayInputStream(repack(values))).use { DataImporter.restore(context, it) }
        assertEquals(values.getValue("place_candidates.csv"), entries(export()).getValue("place_candidates.csv"))
    }

    @Test fun importedFormatTenStillRestoresWithoutTileDiscoveryDates() {
        val tracker = TrackingRepository(context)
        tracker.recordExploredGridCell(fix(1_000_000L))
        val values = entries(export()).toMutableMap()
        values.remove("skipped_driving_fixes.csv")
        values["metadata.json"] = JSONObject(values.getValue("metadata.json"))
            .put("schema_version", 10).toString()
        values["explored_grid.csv"] = values.getValue("explored_grid.csv")
            .trimEnd('\n').lineSequence().joinToString("\n") { it.substringBeforeLast(',') } + "\n"
        prepareFixture(context, ByteArrayInputStream(repack(values))).use {
            DataImporter.restore(context, it)
        }
        val db = AppDatabase.get(context).readableDatabase
        db.rawQuery("SELECT first_unlocked_at FROM explored_grid LIMIT 1", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(0L, it.getLong(0))
        }
    }

    @Test fun currentBackupPreservesRealTileFirstUnlockDates() {
        TrackingRepository(context).recordExploredGridCell(fix(1_000_000L))
        val bytes = export()
        TrackingRepository(context).clearHistory()
        prepareFixture(context, ByteArrayInputStream(bytes)).use {
            DataImporter.restore(context, it)
        }
        val db = AppDatabase.get(context).readableDatabase
        db.rawQuery("SELECT first_unlocked_at FROM explored_grid LIMIT 1", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(1_000_000L, it.getLong(0))
        }
    }

    @Test fun restoreWaitingForAnExportDoesNotDeadlockOrdinaryHistoryReaders() {
        TrackingRepository(context).insertLocation(fix(1_000_000))
        prepareFixture(context, ByteArrayInputStream(export())).use { backup ->
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
