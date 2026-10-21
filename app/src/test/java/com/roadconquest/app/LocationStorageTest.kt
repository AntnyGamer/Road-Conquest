package com.roadconquest.app

import android.location.Location
import android.os.SystemClock
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.account.VerifiedDriving
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class LocationStorageTest {
    @Test fun walkingCallbacksSaveExplorationWithoutDrivingHistory() {
        val context = RuntimeEnvironment.getApplication()
        val repo = TrackingRepository(context)
        for (table in listOf("track_points", "roads", "explored_places", "explored_grid")) repo.readableDatabase().execSQL("DELETE FROM $table")
        val controller = Robolectric.buildService(TrackingService::class.java)
        val service = controller.get()
        fun field(name: String) = TrackingService::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("repository").set(service, repo)
        field("verifiedDriving").set(service, VerifiedDriving(context))
        field("ready").setBoolean(service, true)
        try {
            repeat(3) { index ->
                ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
                service.onLocationChanged(Location("gps").apply {
                    latitude = 40.0 + index * 0.0003; longitude = -74.0; accuracy = 5f; speed = 1.1f
                    time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                })
            }
        } finally {
            field("ready").setBoolean(service, false)
            controller.destroy()
        }
        assertTrue((field("storageExecutor").get(service) as ExecutorService).awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(repo.getExploredPlacesInBounds(41.0, -73.0, 39.0, -75.0).size >= 4)
        assertTrue(repo.getExploredGridInBounds(41.0, -73.0, 39.0, -75.0).size >= 2)
        assertEquals(0L, repo.getSummary().trackPointCount)
        assertEquals(0.0, repo.getSummary().distanceMeters, 0.0)
        assertTrue(repo.loadMatchingWindow().points.isEmpty())
    }

    @Test fun stationaryGpsScatterDoesNotUnlockFogOrGenerateDrivingHistory() {
        val app = RuntimeEnvironment.getApplication()
        val repo = TrackingRepository(app)
        for (table in listOf("track_points", "roads", "explored_places", "explored_grid")) {
            repo.readableDatabase().execSQL("DELETE FROM $table")
        }
        val controller = Robolectric.buildService(TrackingService::class.java)
        val service = controller.get()
        fun field(name: String) = TrackingService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
        field("repository").set(service, repo)
        field("verifiedDriving").set(service, VerifiedDriving(app))
        field("ready").setBoolean(service, true)
        try {
            repeat(4) { i ->
                ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
                service.onLocationChanged(Location("gps").apply {
                    latitude = 40.0 + i * 0.000025
                    longitude = -74.0
                    accuracy = 5f
                    speed = 0f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                })
            }
        } finally {
            field("ready").setBoolean(service, false)
            controller.destroy()
        }
        assertTrue((field("storageExecutor").get(service) as ExecutorService)
            .awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(repo.getExploredGridInBounds(41.0, -73.0, 39.0, -75.0).isEmpty())
        assertTrue(repo.getExploredPlacesInBounds(41.0, -73.0, 39.0, -75.0).isEmpty())
        assertEquals(0L, repo.getSummary().trackPointCount)
    }

    @Test fun normalThreeSecondWalkingAndSlowDrivingUnlockExplorationTiles() {
        val app = RuntimeEnvironment.getApplication()
        val repo = TrackingRepository(app)
        for (speedAndAccuracy in listOf(1.4f to 5f, 5f to 10f)) {
            for (table in listOf("track_points", "roads", "explored_places", "explored_grid")) {
                repo.readableDatabase().execSQL("DELETE FROM $table")
            }
            val controller = Robolectric.buildService(TrackingService::class.java)
            val service = controller.get()
            fun field(name: String) = TrackingService::class.java.getDeclaredField(name).apply { isAccessible = true }
            field("repository").set(service, repo)
            field("verifiedDriving").set(service, VerifiedDriving(app))
            field("ready").setBoolean(service, true)
            val (speed, accuracy) = speedAndAccuracy
            try {
                repeat(30) { index ->
                    ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
                    service.onLocationChanged(Location("gps").apply {
                        latitude = 40.0 + index * (speed * 3.0 / 111_195.0)
                        longitude = -74.0
                        this.accuracy = accuracy
                        this.speed = speed
                        time = System.currentTimeMillis()
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    })
                }
            } finally {
                field("ready").setBoolean(service, false)
                controller.destroy()
            }
            assertTrue((field("storageExecutor").get(service) as ExecutorService)
                .awaitTermination(10, TimeUnit.SECONDS))
            assertTrue("No exploration tiles at $speed m/s with $accuracy m accuracy",
                repo.getExploredGridInBounds(41.0, -73.0, 39.0, -75.0).isNotEmpty())
            assertTrue("No fine exploration samples at $speed m/s with $accuracy m accuracy",
                repo.getExploredPlacesInBounds(41.0, -73.0, 39.0, -75.0).isNotEmpty())
            if (speed < 2.2f) assertEquals(0L, repo.getSummary().trackPointCount)
        }
    }

    @Test fun normalThreeSecondStationaryScatterStillUnlocksNothing() {
        val app = RuntimeEnvironment.getApplication()
        val repo = TrackingRepository(app)
        for (table in listOf("track_points", "roads", "explored_places", "explored_grid")) {
            repo.readableDatabase().execSQL("DELETE FROM $table")
        }
        val controller = Robolectric.buildService(TrackingService::class.java)
        val service = controller.get()
        fun field(name: String) = TrackingService::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("repository").set(service, repo)
        field("verifiedDriving").set(service, VerifiedDriving(app))
        field("ready").setBoolean(service, true)
        try {
            repeat(60) { index ->
                ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
                service.onLocationChanged(Location("gps").apply {
                    latitude = 40.0 + (index % 5 - 2) * 0.00001
                    longitude = -74.0
                    accuracy = 5f
                    speed = 0f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                })
            }
        } finally {
            field("ready").setBoolean(service, false)
            controller.destroy()
        }
        assertTrue((field("storageExecutor").get(service) as ExecutorService)
            .awaitTermination(10, TimeUnit.SECONDS))
        assertTrue(repo.getExploredGridInBounds(41.0, -73.0, 39.0, -75.0).isEmpty())
        assertTrue(repo.getExploredPlacesInBounds(41.0, -73.0, 39.0, -75.0).isEmpty())
        assertEquals(0L, repo.getSummary().trackPointCount)
    }

    @Test fun blockedDatabaseDoesNotBlockCallbacksAndAcceptedSamplesDrainAfterStop() {
        val repo = TrackingRepository(RuntimeEnvironment.getApplication())
        repo.readableDatabase().execSQL("DELETE FROM track_points")
        repo.readableDatabase().execSQL("DELETE FROM roads")
        val controller = Robolectric.buildService(TrackingService::class.java)
        val service = controller.get()
        fun field(name: String) = TrackingService::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("repository").set(service, repo)
        field("verifiedDriving").set(service, VerifiedDriving(RuntimeEnvironment.getApplication()))
        field("ready").setBoolean(service, true)
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = Executors.newSingleThreadExecutor()
        val callbacks = Executors.newSingleThreadExecutor()
        var destroyed = false
        blocker.submit { synchronized(repo) { acquired.countDown(); release.await(10, TimeUnit.SECONDS) } }
        try {
            assertTrue(acquired.await(5, TimeUnit.SECONDS))
            ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
            fun fix(latitude: Double) = Location("gps").apply {
                this.latitude = latitude; longitude = -74.0; accuracy = 5f; speed = 5f
                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            callbacks.submit { service.onLocationChanged(fix(40.0)) }.get(2, TimeUnit.SECONDS)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
            callbacks.submit { service.onLocationChanged(fix(40.0002)) }.get(2, TimeUnit.SECONDS)
            field("ready").setBoolean(service, false)
            controller.destroy()
            destroyed = true
            release.countDown()
            val storage = field("storageExecutor").get(service) as ExecutorService
            assertTrue(storage.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(2L, repo.getSummary().trackPointCount)
            service.onLocationChanged(fix(40.0004))
            assertEquals(2L, repo.getSummary().trackPointCount)
        } finally {
            field("ready").setBoolean(service, false)
            release.countDown()
            if (!destroyed) controller.destroy()
            blocker.shutdownNow(); callbacks.shutdownNow()
        }
    }
}
