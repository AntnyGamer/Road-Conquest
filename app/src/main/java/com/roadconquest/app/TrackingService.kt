package com.roadconquest.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.data.ProgressionRepository
import com.roadconquest.app.account.VerifiedDriving
import com.roadconquest.app.matching.RoadGeometryValidator
import com.roadconquest.app.matching.FallbackRoadMatcher
import com.roadconquest.app.matching.RoadMatcher
import com.roadconquest.app.matching.KnownRoadReuse
import com.roadconquest.app.matching.UnchangedNoMatchBackoff
import com.roadconquest.app.matching.completelyCoversPending
import com.roadconquest.app.matching.lastSubmittedPoints
import com.roadconquest.app.map.FogGrid
import com.roadconquest.app.progression.ProgressionManager
import com.roadconquest.app.util.LocationProviders
import com.roadconquest.app.util.Prefs
import com.roadconquest.app.util.RecentCellCache
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean

class TrackingService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private lateinit var repository: TrackingRepository
    private lateinit var progressionRepository: ProgressionRepository
    private lateinit var verifiedDriving: VerifiedDriving
    private val matcher: RoadMatcher = FallbackRoadMatcher.configured()
    private val noMatchBackoff = UnchangedNoMatchBackoff()
    private val matchingExecutor = ScheduledThreadPoolExecutor(1).apply {
        // Stop-time finalization should not keep the process alive for obsolete retry
        // timers scheduled minutes into the future. Already queued immediate work still runs.
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        setRemoveOnCancelPolicy(true)
    }
    private val storageExecutor = Executors.newSingleThreadExecutor()
    private val placeDiscoveryExecutor = ScheduledThreadPoolExecutor(1).apply {
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        setRemoveOnCancelPolicy(true)
    }
    private val placeDiscoveryScheduled = AtomicBoolean(false)
    private val highAccuracyLocationRequest by lazy(LazyThreadSafetyMode.NONE) {
        LocationRequest.Builder(LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_MIN_UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(LOCATION_MIN_DISTANCE_M)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .build()
    }
    private val persistedDrivingFixThisSession = AtomicBoolean(false)
    // Round-robin through recoverable historical corners so an unroutable
    // junction cannot consume the same one priority slot every cycle.
    private var previousPriorityGapStartId: Long? = null
    private val matchingInFlight = AtomicBoolean(false)
    private val matchingRerunRequested = AtomicBoolean(false)
    private val delayedMatchScheduled = AtomicBoolean(false)
    // Accessed only on the matching executor; rebuilt from persistent deadlines after every run.
    private var deferredRetry: ScheduledFuture<*>? = null
    private val backlogContinuationScheduled = AtomicBoolean(false)
    private val locationMonitorHandler = Handler(Looper.getMainLooper())
    private var locationMonitorStarted = false
    private var lastUsableLocationCallbackElapsedMs = 0L
    private var lastLocationRecoveryElapsedMs = 0L
    private val locationMonitor = object : Runnable {
        override fun run() {
            if (!ready) return
            val now = SystemClock.elapsedRealtime()
            // Recover stalled fused/GPS subscriptions while the foreground service is alive.
            // Never repeatedly restart an active receiver or interfere when Location is off.
            if (!Prefs.isTrackingPaused(this@TrackingService) &&
                ::locationManager.isInitialized &&
                runCatching { locationManager.isLocationEnabled }.getOrDefault(false) &&
                shouldRecoverLocationUpdates(now, lastUsableLocationCallbackElapsedMs, lastLocationRecoveryElapsedMs)
            ) {
                lastLocationRecoveryElapsedMs = now
                requestLocations()
            }
            locationMonitorHandler.postDelayed(this, LOCATION_MONITOR_CHECK_MS)
        }
    }
    // Coalesce duplicate provider/mode callbacks while a final Location-off flush is queued.
    private val finalMatchingFlushScheduled = AtomicBoolean(false)
    // Changed only by the serial matching executor; shrinks after long ambiguous traces.
    private var adaptiveMatchPointLimit = MATCH_BATCH_SIZE
    private var successfulShortMatches = 0
    private var lastRemotePointLimit = com.roadconquest.app.matching.GeoapifyMatcher.MAX_MATCH_POINTS
    private var lastObserved: Location? = null
    // Unlike the per-callback driving anchor, this accumulates small genuine movements.
    private var lastExplorationAnchor: Location? = null
    private var lastAccepted: Location? = null
    private var lastConfirmedDriving: Location? = null
    private var lastRemoteNewestPendingId: Long? = null
    // Accepted fixes omitted from network matching, also retained privately on disk.
    private val skippedSinceRetained = ArrayList<Location>(4)

    /** Persist the final compressed fix before a stop or an interrupted drive. */
    private fun flushTrailingSkippedFix() {
        val last = skippedSinceRetained.lastOrNull() ?: return
        skippedSinceRetained.clear()
        lastAccepted = last
        storageExecutor.execute {
            try {
                repository.insertLocation(last)
                persistedDrivingFixThisSession.set(true)
                sendUiBroadcast(ACTION_STATS_UPDATED)
            } catch (error: Exception) {
                Log.e("RoadConquest", "Could not finalize the last compressed driving fix", error)
            }
        }
    }
    private var lastExplored: Location? = null
    // Avoid repeatedly hitting SQLite for the same 50 m fog cell during one service session.
    // The thread-safe cache also prevents duplicate queued writes before storageExecutor catches up.
    private val exploredCellsThisSession = RecentCellCache(MAX_EXPLORED_CELL_CACHE)
    private val exploredMileCellsThisSession = RecentCellCache(MAX_EXPLORED_CELL_CACHE)
    private var lastPlaceCandidate: Location? = null
    @Volatile private var baselineCandidateCaptured = false
    private var baselineRequestElapsedNanos = 0L
    private var baselineRequestWallMillis = 0L
    private var lastBearingDegrees = 0.0
    @Volatile private var lastMatchAttempt = 0L
    @Volatile private var ready = false
    private var providerReceiverRegistered = false
    private var locationUpdatesRegistered = false
    private var lastNotificationLocationEnabled: Boolean? = null
    private val notificationManager by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(NotificationManager::class.java)
    }
    private val openAppPendingIntent by lazy(LazyThreadSafetyMode.NONE) {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
    private val stopTrackingPendingIntent by lazy(LazyThreadSafetyMode.NONE) {
        PendingIntent.getService(
            this,
            1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP_UNTIL_OPEN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
    private val providerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!ready) return
            val locationEnabled = locationManager.isLocationEnabled
            if (locationEnabled) {
                requestLocations()
            } else {
                queueFinalMatchingFlush()
            }
            refreshForegroundNotification(locationEnabled)
            sendUiBroadcast(ACTION_TRACKING_STATE_CHANGED)
        }
    }
    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        if (Prefs.isDeviceDataDeletionPending(this) || Prefs.isTrackingPaused(this) ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !locationManager.isLocationEnabled)
        ) {
            // Android 14+ requires system Location to be enabled before a location-type
            // foreground service can be promoted. Older supported releases may still keep
            // the existing ready/wait service alive while Location is off.
            stopSelf()
            return
        }

        createNotificationChannel()
        val started = runCatching { startAsForeground() }.isSuccess
        if (!started) {
            stopSelf()
            return
        }

        // None of the persistence/verification objects are needed unless the service can
        // actually become a location foreground service. Delaying them avoids unnecessary
        // setup on blocked restarts (paused tracking, missing permission, or Location off).
        repository = TrackingRepository(this)
        // Android may terminate a service without onDestroy; never lose its final GPS fix.
        runCatching { repository.recoverTrailingSkippedDrivingFix() }
            .onFailure { Log.w("RoadConquest", "Trailing GPS recovery will retry next start", it) }
        progressionRepository = ProgressionRepository(this)
        verifiedDriving = VerifiedDriving(this)

        baselineRequestElapsedNanos = SystemClock.elapsedRealtimeNanos()
        baselineRequestWallMillis = System.currentTimeMillis()
        ready = true
        isRunning = true
        ContextCompat.registerReceiver(this, providerReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION).apply {
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        providerReceiverRegistered = true
        Prefs.markEverStarted(this)
        sendUiBroadcast(ACTION_TRACKING_STATE_CHANGED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_UNTIL_OPEN) {
            Prefs.setTrackingPaused(this, true)
            ready = false
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            sendUiBroadcast(ACTION_TRACKING_STATE_CHANGED)
            return START_NOT_STICKY
        }
        if (Prefs.isDeviceDataDeletionPending(this) || Prefs.isTrackingPaused(this)) {
            ready = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!ready) return START_NOT_STICKY
        requestLocations()
        if (!locationMonitorStarted) {
            locationMonitorStarted = true
            val now = SystemClock.elapsedRealtime()
            lastUsableLocationCallbackElapsedMs = now
            lastLocationRecoveryElapsedMs = now - LOCATION_RECOVERY_COOLDOWN_MS
            locationMonitorHandler.postDelayed(locationMonitor, LOCATION_MONITOR_CHECK_MS)
        }
        matchingExecutor.execute {
            if (ready) maybeRunMatching(force = true)
        }
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        if (!ready || Prefs.isTrackingPaused(this) || !isFreshLocation(location)) return
        if (location.hasAccuracy() && location.accuracy <= MAX_ACCURACY_M) {
            lastUsableLocationCallbackElapsedMs = SystemClock.elapsedRealtime()
        }
        // Verification consumes its independent live GPS stream; consent never selects tracking quality.
        verifiedDriving.offer(location)
        val previous = lastObserved
        if (!LocationProviders.isBetterFix(location, previous)) return

        // A fresh/reset profile gets its zero-point place from the first good LIVE fix,
        // never from a later sparse discovery candidate. If Location was off, this naturally
        // waits until the first good fix after the user turns it back on.
        if (!baselineCandidateCaptured && !location.isMock &&
            location.hasAccuracy() && location.accuracy in 0.01f..MAX_BASELINE_ACCURACY_M &&
            LocationProviders.isFixSince(location, baselineRequestElapsedNanos, baselineRequestWallMillis)
        ) {
            baselineCandidateCaptured = true
            val baseline = Location(location)
            storageExecutor.execute {
                try {
                    if (progressionRepository.recordBaselineCandidate(baseline)) {
                        sendUiBroadcast(ACTION_EXPLORATION_UPDATED)
                        scheduleBackgroundPlaceDiscovery()
                    }
                } catch (error: Exception) {
                    Log.e("RoadConquest", "Could not save starting place location", error)
                    baselineCandidateCaptured = false
                }
            }
        }
        val elapsedFromPrevious = if (previous == null) Long.MIN_VALUE else elapsedMillis(previous, location)
        if (elapsedFromPrevious == 0L) {
            // A better simultaneous source may improve the marker/next baseline, never add mileage twice.
            if (location.hasAccuracy() && location.accuracy <= MAX_ACCURACY_M) lastObserved = Location(location)
            if (location.hasAccuracy() && location.accuracy <= MAX_PREVIEW_ACCURACY_M) sendLocationUpdate(location, lastBearingDegrees)
            return
        }
        val distanceFromPrevious = if (previous != null && elapsedFromPrevious in 1..MAX_MOTION_SAMPLE_AGE_MS) {
            previous.distanceTo(location)
        } else {
            Float.NaN
        }
        if (elapsedFromPrevious in 1..MAX_MOTION_SAMPLE_AGE_MS &&
            distanceFromPrevious / (elapsedFromPrevious / 1_000f) > MAX_PLAUSIBLE_SPEED_MPS
        ) return
        val explorationAnchor = lastExplorationAnchor
        val explorationAge = explorationAnchor?.let { elapsedMillis(it, location) } ?: Long.MIN_VALUE
        val explorationDistance = if (explorationAnchor != null &&
            explorationAge in 1..MAX_MOTION_SAMPLE_AGE_MS
        ) explorationAnchor.distanceTo(location) else Float.NaN
        val explorationMotionConfirmed = isReliableExplorationMovement(
            location, explorationAnchor, explorationAge, explorationDistance
        )
        // Short, reliable moves accumulate instead of resetting the evidence every three
        // seconds. An expired/poor-quality fix starts a new window, never bridges a gap.
        if (explorationMotionConfirmed || explorationAnchor == null ||
            explorationAge !in 1..MAX_MOTION_SAMPLE_AGE_MS ||
            location.isMock || !location.hasAccuracy() || location.accuracy !in 0.01f..25f
        ) {
            lastExplorationAnchor = if (!location.isMock && location.hasAccuracy() &&
                location.accuracy in 0.01f..25f
            ) Location(location) else null
        }
        // Fog and 50-m exploration follow corroborated walking OR driving movement.
        // A single stationary GPS fix does not reveal a square or count as a visit.
        if (explorationMotionConfirmed) {
            FogGrid.cell(location.latitude, location.longitude)?.let { cell ->
                if (exploredMileCellsThisSession.add(cell.key)) {
                    val accepted = Location(location)
                    storageExecutor.execute {
                        try {
                            if (repository.recordExploredGridCell(accepted)) {
                                sendUiBroadcast(ACTION_EXPLORATION_UPDATED)
                            }
                        } catch (error: Exception) {
                            exploredMileCellsThisSession.remove(cell.key)
                            Log.e("RoadConquest", "Could not persist explored mile cell", error)
                        }
                    }
                }
            }
        }
        if (explorationMotionConfirmed &&
            (lastExplored?.distanceTo(location) ?: Float.POSITIVE_INFINITY) >= 20f
        ) {
            // Keep the original 20 m acceptance gate exactly: the optimization only suppresses
            // duplicate database work after a location would already have been processed.
            val visited = Location(location)
            lastExplored = visited
            // Fine-grained samples also capture genuine walking, without adding road miles.
            val exploredCell = TrackingRepository.exploredCellKey(visited.latitude, visited.longitude)
            if (exploredCell != null && exploredCellsThisSession.add(exploredCell)) {
                storageExecutor.execute {
                    // Persist confirmed exploration without affecting road matching.
                    try {
                        if (repository.recordExploredPlace(visited, exploredCell)) {
                            sendUiBroadcast(ACTION_EXPLORATION_UPDATED)
                        }
                    } catch (error: Exception) {
                        exploredCellsThisSession.remove(exploredCell)
                        Log.e("RoadConquest", "Could not save explored place", error)
                    }
                }
            }
        }
        // Do not let a coarse, duplicate, or out-of-order fix poison the next driving-speed
        // comparison. It may still update the live car below when current, but only a newer
        // road-quality fix becomes the motion baseline.
        if (location.hasAccuracy() && location.accuracy <= MAX_ACCURACY_M &&
            (previous == null || elapsedFromPrevious > 0L)
        ) {
            lastObserved = Location(location)
        }

        // The live car marker follows a reasonable current fix even when that fix is not
        // accepted as driving history (for example, while stopped at a traffic light).
        if (location.hasBearing() && (!location.hasSpeed() || location.speed >= MIN_BEARING_SPEED_MPS)) {
            lastBearingDegrees = location.bearing.toDouble()
        }
        if (location.hasAccuracy() && location.accuracy <= MAX_PREVIEW_ACCURACY_M) {
            sendLocationUpdate(location, lastBearingDegrees)
        }

        if (lastAccepted?.let { elapsedMillis(it, location) > MAX_MOTION_SAMPLE_AGE_MS } == true) {
            flushTrailingSkippedFix()
            lastAccepted = null
        }
        if (!isUsableDrivingLocation(location, previous, elapsedFromPrevious, distanceFromPrevious)) return

        // Save one recent anchor before the first confirmed driving point so the beginning
        // of a drive is not lost merely because driving speed could only be confirmed later.
        // Normal in-drive samples need only one insert, avoiding a temporary list allocation on
        // every accepted fix while preserving the exact two-point transaction at drive start.
        val startAnchor = if (lastAccepted == null && previous != null &&
            previous.hasAccuracy() && previous.accuracy <= MAX_ACCURACY_M &&
            elapsedFromPrevious in 1..MAX_START_ANCHOR_AGE_MS
        ) previous else null

        val accepted = requireNotNull(lastObserved)
        if (LocationProviders.reliableMeasuredSpeed(accepted) >= 4f) {
            lastConfirmedDriving = accepted
        }
        // Keep the live car, fog and verification stream at the original GPS rate.
        // Only omit redundant high-quality straight-line *stored* fixes; retain every
        // uncertain fix and directional change so junctions can still be matched.
        val anchor = lastAccepted
        val importantSkipped = if (anchor != null && skippedSinceRetained.isNotEmpty()) {
            skippedFixesToRetain(anchor, skippedSinceRetained, accepted)
        } else emptyList()
        // Restore genuine bends before the route can be approximated by a straight chord.
        val skipStraightFix = importantSkipped.isEmpty() &&
            skippedSinceRetained.size < 4 &&
            anchor?.let { !shouldPersistDrivingSample(it, accepted) } == true
        val placeCandidate = if (!accepted.isMock && accepted.hasAccuracy() &&
            accepted.accuracy in 0.01f..25f &&
            (lastPlaceCandidate?.distanceTo(accepted) ?: Float.POSITIVE_INFINITY) >= PLACE_CANDIDATE_MIN_DISTANCE_M
        ) Location(accepted) else null
        if (placeCandidate != null) lastPlaceCandidate = placeCandidate
        if (skipStraightFix) {
            skippedSinceRetained.add(Location(accepted))
            // Save the *original* accepted evidence in a separate local table.
            // It does not enter the matching queue and never costs an API request.
            storageExecutor.execute {
                try {
                    repository.recordSkippedDrivingFix(accepted)
                    if (placeCandidate != null && ::progressionRepository.isInitialized &&
                        progressionRepository.recordPlaceCandidate(placeCandidate)
                    ) {
                        sendUiBroadcast(ACTION_PLACE_CANDIDATE_UPDATED)
                        scheduleBackgroundPlaceDiscovery()
                    }
                } catch (error: Exception) {
                    Log.e("RoadConquest", "Could not preserve a compressed-out driving fix", error)
                    stopSelf()
                }
            }
            return
        }
        skippedSinceRetained.clear()
        lastAccepted = accepted
        storageExecutor.execute {
            try {
                // A normal saved fix needs no temporary list. Preserve the
                // exact batched ordering when an initial anchor or bend exists.
                if (startAnchor == null && importantSkipped.isEmpty()) {
                    repository.insertLocation(accepted)
                } else {
                    repository.insertLocations(buildList {
                        if (startAnchor != null) add(startAnchor)
                        addAll(importantSkipped)
                        add(accepted)
                    })
                }
                if (placeCandidate != null && ::progressionRepository.isInitialized) {
                    try {
                        if (progressionRepository.recordPlaceCandidate(placeCandidate)) {
                            // Resolve actual new crossing candidates while the map is open,
                            // even inside a previously cleared mile-sized fog tile.
                            sendUiBroadcast(ACTION_PLACE_CANDIDATE_UPDATED)
                            scheduleBackgroundPlaceDiscovery()
                        }
                    } catch (error: Exception) {
                        // Optional municipal lookups must never interrupt recorded
                        // driving, distance, road matching, or fog persistence.
                        Log.e("RoadConquest", "Could not queue place discovery", error)
                    }
                }
                persistedDrivingFixThisSession.set(true)
                sendUiBroadcast(ACTION_STATS_UPDATED)
                maybeRunMatching()
            } catch (error: Exception) {
                Log.e("RoadConquest", "Could not save driving locations. Stopping tracking", error)
                stopSelf()
            }
        }
    }

    override fun onProviderEnabled(provider: String) {
        if (ready) {
            requestLocations()
            sendUiBroadcast(ACTION_TRACKING_STATE_CHANGED)
        }
    }

    // Keep the existing registration while Location is off so the provider can resume when
    // the user turns Location back on; re-registering while disabled can lose that callback.
    override fun onProviderDisabled(provider: String) {
        if (!ready) return
        sendUiBroadcast(ACTION_TRACKING_STATE_CHANGED)
        // No more fixes may arrive while Android Location is off. Flush any queued
        // accepted fixes first, then give unresolved corner/end-of-drive intervals a final
        // matcher pass. A single provider handoff does not need this.
        if (locationManager.isLocationEnabled) {
            if (LocationProviders.available(locationManager).isNotEmpty()) requestLocations()
        } else {
            queueFinalMatchingFlush()
        }
    }

    /**
     * Android can deliver the Location-mode/provider callback immediately after a final GPS
     * fix while that fix is still queued on storageExecutor. Serialize through storage first,
     * then through matching, so the final repair pass cannot miss the last approach/exit point
     * or race a matcher that is about to defer the same corner again.
     */
    private fun queueFinalMatchingFlush() {
        if (!ready || !::locationManager.isInitialized || !::repository.isInitialized ||
            locationManager.isLocationEnabled ||
            !finalMatchingFlushScheduled.compareAndSet(false, true)
        ) return

        flushTrailingSkippedFix()
        val storageSubmitted = runCatching {
            storageExecutor.execute {
                try {
                    if (!ready || locationManager.isLocationEnabled) {
                        finalMatchingFlushScheduled.set(false)
                    } else {
                        val matchingSubmitted = runCatching {
                            matchingExecutor.execute {
                                try {
                                    if (ready && !locationManager.isLocationEnabled) {
                                        repository.makePendingMatchingEligibleNow()
                                        maybeRunMatching(force = true)
                                    }
                                } finally {
                                    finalMatchingFlushScheduled.set(false)
                                }
                            }
                        }.isSuccess
                        if (!matchingSubmitted) finalMatchingFlushScheduled.set(false)
                    }
                } catch (error: Exception) {
                    finalMatchingFlushScheduled.set(false)
                    Log.e("RoadConquest", "Could not queue final road matching pass", error)
                }
            }
        }.isSuccess
        if (!storageSubmitted) finalMatchingFlushScheduled.set(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Finish accepted GPS history before draining the storage and matcher queues.
        if (::repository.isInitialized && !Prefs.isDeviceDataDeletionPending(this)) flushTrailingSkippedFix()
        // Flip readiness before shutting down the scheduler so an in-flight matcher cannot
        // enqueue more work while teardown is in progress.
        ready = false
        placeDiscoveryExecutor.shutdownNow()
        locationMonitorHandler.removeCallbacks(locationMonitor)
        locationMonitorStarted = false
        if (providerReceiverRegistered) runCatching { unregisterReceiver(providerReceiver) }
        providerReceiverRegistered = false
        if (locationUpdatesRegistered && ::locationManager.isInitialized) {
            runCatching { locationManager.removeUpdates(this) }
            locationUpdatesRegistered = false
        }
        // Wait for accepted GPS fixes queued on storageExecutor before final matching.
        // A normal stop must not discard the last turn/end-of-drive evidence.
        // Privacy deletion is different: cancel matching and let history-generation guards
        // reject any old in-flight result instead of starting another network request.
        if (::repository.isInitialized && !Prefs.isDeviceDataDeletionPending(this)) {
            val queued = runCatching {
                storageExecutor.execute {
                    try {
                        // Stopping without any new driving must not wake the remote matcher merely to
                        // revisit unrelated historical backlog.
                        if (persistedDrivingFixThisSession.get()) matchingExecutor.execute {
                            try {
                                repository.makePendingMatchingEligibleNow()
                                matchingInFlight.set(true)
                                runMatchingBatches()
                            } catch (error: Exception) {
                                Log.e("RoadConquest", "Final matching failed. Saved GPS remains for next launch", error)
                            }
                        }
                    } finally {
                        // Graceful shutdown still runs work already queued on this executor.
                        matchingExecutor.shutdown()
                    }
                }
            }.isSuccess
            if (!queued) matchingExecutor.shutdown()
        } else {
            matchingExecutor.shutdownNow()
        }
        if (::verifiedDriving.isInitialized) verifiedDriving.close()
        // Drain accepted samples even when the user stops tracking. They were copied before enqueueing.
        storageExecutor.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        isRunning = false
        sendUiBroadcast(ACTION_TRACKING_STATE_CHANGED)
        super.onDestroy()
    }

    private fun requestLocations() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        // A fresh service instance has nothing to unregister. Provider/mode changes still
        // replace every active registration before selecting the currently enabled sources.
        if (locationUpdatesRegistered) {
            runCatching { locationManager.removeUpdates(this) }
            locationUpdatesRegistered = false
        }
        LocationProviders.registerHighAccuracy(locationManager, ::registerProvider)
    }

    private fun registerProvider(provider: String): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            locationManager.requestLocationUpdates(provider, highAccuracyLocationRequest, mainExecutor, this)
            locationUpdatesRegistered = true
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun sendLocationUpdate(location: Location, bearingDegrees: Double) {
        if (!MainActivity.trackingReceiverRegistered) return
        sendBroadcast(
            Intent(ACTION_LOCATION_UPDATE)
                .setPackage(packageName)
                .putExtra(EXTRA_LATITUDE, location.latitude)
                .putExtra(EXTRA_LONGITUDE, location.longitude)
                .putExtra(EXTRA_BEARING, bearingDegrees)
                .putExtra(EXTRA_SPEED_MPS, LocationProviders.measuredSpeedOrNull(location) ?: Float.NaN)
                .putExtra(EXTRA_ACCURACY_M, location.accuracy)
                .putExtra(EXTRA_LOCATION_AGE_MS, if (location.elapsedRealtimeNanos > 0L) {
                    ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L).coerceAtLeast(0L)
                } else (System.currentTimeMillis() - location.time).coerceAtLeast(0L))
        )
    }

    private fun sendUiBroadcast(action: String) {
        if (!MainActivity.trackingReceiverRegistered) return
        sendBroadcast(Intent(action).setPackage(packageName))
    }

    /**
     * Exploration may be earned by genuine movement on foot as well as driving,
     * but never by a single first fix or location drift while standing still.
     * Leave the stricter driving acceptance and road-matching rules unchanged.
     */
    private fun isReliableExplorationMovement(
        location: Location,
        previous: Location?,
        ageMs: Long,
        distanceMeters: Float
    ): Boolean {
        if (previous == null || location.isMock || previous.isMock ||
            !location.hasAccuracy() || !previous.hasAccuracy() ||
            !location.accuracy.isFinite() || !previous.accuracy.isFinite() ||
            location.accuracy !in 0.01f..25f || previous.accuracy !in 0.01f..25f ||
            ageMs !in 1..MAX_MOTION_SAMPLE_AGE_MS || !distanceMeters.isFinite()
        ) return false
        val speedMetersPerSecond = distanceMeters / (ageMs / 1_000f)
        if (speedMetersPerSecond !in 0.5f..MAX_PLAUSIBLE_SPEED_MPS) return false
        // Require displacement greater than the combined horizontal GPS
        // uncertainty. GPS scatter across a mile-cell edge cannot unlock it.
        if (distanceMeters <= maxOf(8f, previous.accuracy + location.accuracy)) return false
        val measuredSpeed = maxOf(
            LocationProviders.reliableMeasuredSpeed(previous),
            LocationProviders.reliableMeasuredSpeed(location)
        )
        return measuredSpeed >= 0.5f || speedMetersPerSecond >= 1.5f
    }

    private fun isUsableDrivingLocation(location: Location, previous: Location?): Boolean {
        if (previous == null) return false
        val ageMs = elapsedMillis(previous, location)
        val distance = if (ageMs in 1..MAX_MOTION_SAMPLE_AGE_MS) previous.distanceTo(location) else Float.NaN
        return isUsableDrivingLocation(location, previous, ageMs, distance)
    }

    private fun isUsableDrivingLocation(
        location: Location,
        previous: Location?,
        ageMs: Long,
        distance: Float
    ): Boolean {
        if (location.isMock || previous?.isMock == true ||
            !location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy !in 0f..MAX_ACCURACY_M ||
            previous == null || !previous.hasAccuracy() || !previous.accuracy.isFinite() || previous.accuracy !in 0f..MAX_ACCURACY_M ||
            ageMs <= 0L || ageMs > MAX_MOTION_SAMPLE_AGE_MS || !distance.isFinite()
        ) return false
        if (ageMs <= 4_000L && distance < 1.5f) return false

        val inferredSpeed = distance / (ageMs / 1_000f)
        if (inferredSpeed > MAX_PLAUSIBLE_SPEED_MPS) return false
        val measuredSpeed = maxOf(
            LocationProviders.reliableMeasuredSpeed(previous),
            LocationProviders.reliableMeasuredSpeed(location)
        )
        val movedBeyondAccuracy = distance > maxOf(5f, previous.accuracy + location.accuracy)
        if (measuredSpeed >= MIN_DRIVING_SPEED_MPS) return true
        // Keep a moving slow turn after confirmed driving. A stop or an old drive
        // cannot authorize fresh GPS drift, and walking alone never opens this window.
        if (lastConfirmedDriving?.let { elapsedMillis(it, location) in 1..45_000L } == true &&
            previous.accuracy <= 12f && location.accuracy <= 12f && measuredSpeed >= 0.8f &&
            inferredSpeed >= 0.5f && distance > maxOf(1.5f, (previous.accuracy + location.accuracy) / 4f)
        ) return true
        // Do not override two explicit low-speed readings with positional jitter.
        // Providers without usable speed metadata retain the displacement fallback.
        if (LocationProviders.measuredSpeedOrNull(previous) != null &&
            LocationProviders.measuredSpeedOrNull(location) != null
        ) return false
        return movedBeyondAccuracy && inferredSpeed >= MIN_DRIVING_SPEED_MPS
    }

    /** Conservative on-device GPS compression: never skip a bend, uncertain fix or gap. */
    internal fun shouldPersistDrivingSample(previousSaved: Location, current: Location): Boolean {
        val ageMs = elapsedMillis(previousSaved, current)
        if (ageMs <= 0L || ageMs >= MIN_STRAIGHT_SAMPLE_INTERVAL_MS ||
            !previousSaved.hasBearing() || !current.hasBearing() ||
            !previousSaved.hasAccuracy() || !current.hasAccuracy() ||
            previousSaved.accuracy !in 0.01f..15f || current.accuracy !in 0.01f..15f ||
            !previousSaved.hasSpeed() || !current.hasSpeed() ||
            previousSaved.speed < MIN_DRIVING_SPEED_MPS || current.speed < MIN_DRIVING_SPEED_MPS
        ) return true
        val delta = bearingChangeDegrees(previousSaved, current)
        return !delta.isFinite() || delta >= MIN_RETAINED_BEND_DEGREES
    }

    private fun bearingChangeDegrees(from: Location, to: Location): Float =
        kotlin.math.abs(((to.bearing - from.bearing + 540f) % 360f) - 180f)

    /** Keep the last real approach fix even when a short turn is within the chord tolerance. */
    internal fun skippedFixesToRetain(anchor: Location, skipped: List<Location>, end: Location): List<Location> {
        val turned = anchor.hasBearing() && end.hasBearing() &&
            bearingChangeDegrees(anchor, end) >= MIN_RETAINED_BEND_DEGREES
        return skipped.filterIndexed { index, fix ->
            (turned && index == skipped.lastIndex) || skippedFixCarriesGeometry(anchor, fix, end)
        }
    }

    /** Reintroduce compressed samples whose path strays from the straight-line chord. */
    internal fun skippedFixCarriesGeometry(anchor: Location, skipped: Location, end: Location): Boolean {
        if (anchor.time >= skipped.time || skipped.time >= end.time) return true
        fun longitudeDelta(from: Double, to: Double) =
            ((to - from + 540.0) % 360.0) - 180.0
        val cosLat = kotlin.math.cos(Math.toRadians((anchor.latitude + end.latitude) / 2.0))
        val sx = Math.toRadians(longitudeDelta(anchor.longitude, skipped.longitude)) * cosLat * 6_371_008.8
        val sy = Math.toRadians(skipped.latitude - anchor.latitude) * 6_371_008.8
        val ex = Math.toRadians(longitudeDelta(anchor.longitude, end.longitude)) * cosLat * 6_371_008.8
        val ey = Math.toRadians(end.latitude - anchor.latitude) * 6_371_008.8
        val lengthSquared = ex * ex + ey * ey
        if (!lengthSquared.isFinite() || lengthSquared < 1.0) return true
        val t = ((sx * ex + sy * ey) / lengthSquared).coerceIn(0.0, 1.0)
        return kotlin.math.hypot(sx - ex * t, sy - ey * t) > 3.0
    }

    private fun isFreshLocation(location: Location): Boolean {
        if (!location.latitude.isFinite() || !location.longitude.isFinite() ||
            location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0 ||
            (location.hasAccuracy() && (!location.accuracy.isFinite() || location.accuracy < 0f))
        ) return false
        val fixElapsedNanos = location.elapsedRealtimeNanos
        if (fixElapsedNanos > 0L) {
            val ageNanos = SystemClock.elapsedRealtimeNanos() - fixElapsedNanos
            return ageNanos in 0..MAX_LOCATION_AGE_NANOS
        }
        val fixTime = location.time
        if (fixTime <= 0L) return false
        val ageMillis = System.currentTimeMillis() - fixTime
        return ageMillis in 0..MAX_LOCATION_AGE_MS
    }

    private fun elapsedMillis(from: Location, to: Location): Long =
        if (from.elapsedRealtimeNanos > 0L && to.elapsedRealtimeNanos > 0L) {
            (to.elapsedRealtimeNanos - from.elapsedRealtimeNanos) / 1_000_000L
        } else {
            to.time - from.time
        }

    /**
     * Drain a bounded page of persisted discoveries each minute while tracking
     * with the map closed. One candidate every five minutes could never catch up
     * with a normal drive and left known visited municipalities permanently gray.
     * This reuses the existing location foreground service and its executor:
     * no extra GPS sampling, wake locks or Android background jobs.
     * The map's foreground resolver still takes priority while visible.
     */
    private fun scheduleBackgroundPlaceDiscovery() {
        if (!ready || MainActivity.trackingReceiverRegistered ||
            !placeDiscoveryScheduled.compareAndSet(false, true)
        ) return
        runCatching {
            placeDiscoveryExecutor.schedule({
                try {
                    if (ready && !MainActivity.trackingReceiverRegistered &&
                        !Prefs.isDeviceDataDeletionPending(this)
                    ) {
                        val due = progressionRepository.nextPlaceCandidateAttemptMillis()
                        if (due != null && due <= System.currentTimeMillis()) {
                            ProgressionManager.resolvePendingPlacesWithDetails(this, 12)
                        }
                    }
                } catch (error: Exception) {
                    Log.w("RoadConquest", "Deferred place discovery will retry", error)
                } finally {
                    placeDiscoveryScheduled.set(false)
                    // Persistent retries survive network/geocoder outages, but
                    // stop when the service stops or MainActivity handles them.
                    if (ready && !MainActivity.trackingReceiverRegistered &&
                        !Prefs.isDeviceDataDeletionPending(this)
                    ) {
                        val next = runCatching {
                            progressionRepository.nextPlaceCandidateAttemptMillis()
                        }.getOrNull()
                        if (next != null) scheduleBackgroundPlaceDiscovery()
                    }
                }
            }, BACKGROUND_PLACE_DISCOVERY_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }.onFailure { placeDiscoveryScheduled.set(false) }
    }

    private fun maybeRunMatching(force: Boolean = false) {
        if (!ready) return
        val now = SystemClock.elapsedRealtime()
        val cooldownRemaining = if (lastMatchAttempt == 0L) {
            0L
        } else {
            (MATCH_INTERVAL_MS - (now - lastMatchAttempt)).coerceAtLeast(0L)
        }
        if (!force && cooldownRemaining > 0L) {
            if (delayedMatchScheduled.compareAndSet(false, true)) {
                runCatching {
                    matchingExecutor.schedule(
                        {
                            delayedMatchScheduled.set(false)
                            if (ready) maybeRunMatching(force = true)
                        },
                        cooldownRemaining,
                        TimeUnit.MILLISECONDS
                    )
                }.onFailure { delayedMatchScheduled.set(false) }
            }
            return
        }
        if (!matchingInFlight.compareAndSet(false, true)) {
            matchingRerunRequested.set(true)
            return
        }

        val submitted = runCatching {
            matchingExecutor.execute { runMatchingBatches() }
        }.isSuccess
        if (!submitted) matchingInFlight.set(false)
    }

    /** Matches already persisted fixes on the serial matcher, including a final stop-time pass. */
    private fun runMatchingBatches() {
        var needsRecovery = false
        try {
            repeat(MAX_MATCH_BATCHES_PER_RUN) { batchIndex ->
                var priorityStartId: Long? = null
                fun repairStart(onlyAnchored: Boolean = false): Long? {
                    priorityStartId = repository.newestEligibleAnchoredGapStartId(
                        beforeId = previousPriorityGapStartId
                    ) ?: repository.newestEligibleAnchoredGapStartId()
                    return priorityStartId ?: if (onlyAnchored) null else repository.oldestEligibleRetryId()
                }
                var retryStartId = when (batchIndex) {
                    0 -> null
                    1 -> repairStart()
                    else -> repository.oldestEligibleRetryId()
                }
                fun repairLimit() = if (priorityStartId != null) minOf(25, matcher.maxPoints)
                    else minOf(10, adaptiveMatchPointLimit, matcher.maxPoints)
                var window = repository.loadMatchingWindow(
                    if (retryStartId == null) minOf(adaptiveMatchPointLimit, matcher.maxPoints) else repairLimit(),
                    retryGapStartId = retryStartId)
                // Every remote batch has a cooldown. Later loop iterations usually cannot
                // dispatch, so they cannot be the only path to historical repairs. Once the
                // newest eligible fixes have already been submitted, use the next permitted
                // request for a gap instead. New driving fixes retain first priority.
                val newestPendingId = window.markableIds.maxOrNull()
                val wallNow = System.currentTimeMillis()
                val staleSlowWindow = lastRemoteNewestPendingId == null && window.points.all {
                    it.id !in window.markableIds ||
                        (wallNow - it.timestampMillis > 120_000L && it.speedMps >= 0f && it.speedMps < 4f)
                }
                if (batchIndex == 0 && newestPendingId != null &&
                    (lastRemoteNewestPendingId?.let { newestPendingId <= it } == true || staleSlowWindow)
                ) {
                    // A restart must not spend its first request on stale walking noise
                    // while a precise, fully anchored driving turn is already available.
                    retryStartId = repairStart(onlyAnchored = staleSlowWindow)
                    if (retryStartId != null) window = repository.loadMatchingWindow(
                        repairLimit(), retryGapStartId = retryStartId)
                }
                val points = window.points
                if (points.isEmpty()) return

                if (points.size < 2) {
                    val onlyId = window.markableIds.singleOrNull() ?: return
                    // For the current drive, keep the lone point eligible and simply wait
                    // for the next fix. If it is blocking an older backlog, defer only that
                    // singleton and continue draining older, matchable history.
                    if (!repository.hasOlderEligiblePending(onlyId)) return
                    repository.deferMatching(
                        window.markableIds,
                        System.currentTimeMillis() + MATCH_SINGLE_POINT_DEFERRAL_MS
                    )
                    continueMatchingBatch(batchIndex)
                    return@repeat
                }

                // Repeated drives on previously verified road segments are resolved
                // entirely on-device. Never use proximity to award a NEW road.
                val locallyCovered = KnownRoadReuse.find(repository, points, window.markableIds)
                if (locallyCovered.isNotEmpty()) {
                    val resolvedLocally = repository.completeKnownRoadVisits(locallyCovered)
                    if (resolvedLocally > 0) {
                        sendUiBroadcast(ACTION_ROADS_UPDATED)
                        sendUiBroadcast(ACTION_STATS_UPDATED)
                        if (batchIndex + 1 == MAX_MATCH_BATCHES_PER_RUN) scheduleBacklogContinuation()
                        return@repeat
                    }
                }
                val cached = matcher.cachedMatch(points, window.markableIds)?.takeIf {
                    it.completelyCoversPending(matcher.lastSubmittedPoints(points), window.markableIds)
                }
                val nowElapsed = SystemClock.elapsedRealtime()
                val wait = remainingRemoteMatchingDelayMs(nowElapsed, lastMatchAttempt, lastRemotePointLimit,
                    if (retryStartId == null) window.markableIds.size else 0)
                if (cached == null && wait > 0L) {
                    // This also covers final flushes and forced backlog retry runs.
                    scheduleBacklogContinuation(wait)
                    return
                }
                val providerCooldown = matcher.rateLimitRemainingMs()
                if (cached == null && providerCooldown > 0L) {
                    // Still accept local visits while the provider is unavailable.
                    // Do not call the online matcher or burn additional requests.
                    scheduleBacklogContinuation(providerCooldown)
                    return
                }
                val result = cached ?: matcher.match(points, window.markableIds)
                if (matcher.lastCallUsedNetwork) {
                    lastMatchAttempt = nowElapsed
                    lastRemotePointLimit = matcher.lastNetworkProviderPointLimit
                }
                // The 25-point backup has its own shorter capacity-based cadence.
                val submittedPointIds = matcher.lastSubmittedPoints(points).map { it.id }
                val submittedPendingIds = window.markableIds intersect submittedPointIds.toSet()
                if (matcher.lastCallUsedNetwork) {
                    // Only actual submitted work advances either cursor. Cache hits,
                    // cooldown checks and unsent backup-window points do not consume a turn.
                    submittedPendingIds.maxOrNull()?.let {
                        lastRemoteNewestPendingId = maxOf(lastRemoteNewestPendingId ?: it, it)
                    }
                    if (priorityStartId != null) previousPriorityGapStartId = priorityStartId
                }
                if (result == null) {
                    noMatchBackoff.clear(submittedPendingIds)
                    val providerCooldown = matcher.rateLimitRemainingMs()
                    if (!matcher.unavailable && providerCooldown == 0L && points.size >= 20) {
                        adaptiveMatchPointLimit = maxOf(10, points.size / 2)
                        successfulShortMatches = 0
                    }
                    repository.deferMatching(
                        // A primary outage can fall back from a full window to
                        // a 25-fix window. A rejected backup window is no evidence
                        // against the remaining fixes; retain their eligibility.
                        submittedPendingIds,
                        System.currentTimeMillis() +
                            maxOf(MATCH_RETRY_AFTER_FAILURE_MS, providerCooldown)
                    )
                    // If the remote server rejected traffic, do not drain other
                    // pending batches into the same cooldown. Saved GPS persists,
                    // and the scheduled deferred retry will resume later.
                    if (providerCooldown > 0L) return
                    // The newest batch may be intrinsically unmatchable, not merely
                    // offline. Since it is now deferred, keep draining older eligible
                    // history so one bad batch cannot permanently starve the queue.
                    continueMatchingBatch(batchIndex)
                    return@repeat
                }

                if (result.roads.isNotEmpty() && adaptiveMatchPointLimit < MATCH_BATCH_SIZE) {
                    successfulShortMatches++
                    if (successfulShortMatches >= 3) {
                        adaptiveMatchPointLimit = minOf(MATCH_BATCH_SIZE, adaptiveMatchPointLimit * 2)
                        successfulShortMatches = 0
                    }
                }
                val acceptedRoads = result.roads.filter { it.confidence >= RoadGeometryValidator.MIN_ACCEPTABLE_CONFIDENCE }
                val matchedIds = result.matchedPointConfidences.mapNotNull { (id, confidence) ->
                    id.takeIf {
                        confidence >= RoadGeometryValidator.MIN_ACCEPTABLE_CONFIDENCE && id in submittedPendingIds
                    }
                }

                val resolvedIds = if (acceptedRoads.isNotEmpty() && matchedIds.isNotEmpty()) {
                    repository.completeMatch(acceptedRoads, matchedIds)
                    sendUiBroadcast(ACTION_ROADS_UPDATED)
                    matchedIds.toSet()
                } else {
                    emptySet()
                }

                // A backup receives just its selected window; never defer unsent fixes.
                val unresolvedIds = submittedPendingIds - resolvedIds
                if (unresolvedIds.isNotEmpty()) {
                    // Repeated *valid* NoMatch responses for identical GPS/context evidence
                    // do not justify the same paid request every seven minutes. The first
                    // two attempts retain the original cadence. Fresh context or any
                    // actual provider evidence immediately restores the normal retry.
                    val usualDelay = if (points.size >= 20) LONG_MATCH_RETRY_AFTER_PARTIAL_MS
                        else MATCH_RETRY_AFTER_PARTIAL_MS
                    val delay = if (result.explicitNoMatch && result.roads.isEmpty() && result.matchedPointConfidences.isEmpty()) {
                        noMatchBackoff.delayAfterNoMatch(submittedPointIds, unresolvedIds, usualDelay)
                    } else {
                        noMatchBackoff.clear(unresolvedIds)
                        usualDelay
                    }
                    repository.deferMatching(unresolvedIds, System.currentTimeMillis() + delay)
                } else {
                    noMatchBackoff.clear(window.markableIds)
                }

                continueMatchingBatch(batchIndex)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            needsRecovery = true
            Log.e("RoadConquest", "Road matching failed. Pending points retained for retry", error)
        } finally {
            matchingInFlight.set(false)
            if (ready) scheduleDeferredRetry(needsRecovery)
            if (matchingRerunRequested.getAndSet(false) && ready) maybeRunMatching()
        }
    }

    // Keep the existing inter-batch pause and final backlog scheduling in one place.
    private fun continueMatchingBatch(batchIndex: Int) {
        if (batchIndex + 1 < MAX_MATCH_BATCHES_PER_RUN) {
            Thread.sleep(MATCH_BATCH_PAUSE_MS)
        } else {
            scheduleBacklogContinuation()
        }
    }

    private fun scheduleBacklogContinuation(delayMs: Long = MATCH_BACKLOG_CONTINUATION_MS) {
        if (!backlogContinuationScheduled.compareAndSet(false, true)) return
        runCatching {
            matchingExecutor.schedule(
                {
                    backlogContinuationScheduled.set(false)
                    if (ready) maybeRunMatching(force = true)
                },
                delayMs.coerceAtLeast(1_000L),
                TimeUnit.MILLISECONDS
            )
        }.onFailure { backlogContinuationScheduled.set(false) }
    }

    private fun scheduleDeferredRetry(needsRecovery: Boolean = false) {
        deferredRetry?.cancel(false)
        deferredRetry = null
        if (!ready) return
        val now = System.currentTimeMillis()
        val next = try {
            repository.nextDeferredMatchAttempt(now)
        } catch (error: Exception) {
            Log.e("RoadConquest", "Could not read retry deadlines", error)
            now + MATCH_RETRY_AFTER_FAILURE_MS
        } ?: if (needsRecovery) now + MATCH_RETRY_AFTER_FAILURE_MS else return
        runCatching {
            deferredRetry = matchingExecutor.schedule(
                {
                    deferredRetry = null
                    if (ready) maybeRunMatching(force = true)
                },
                (next - now).coerceAtLeast(1_000L),
                TimeUnit.MILLISECONDS
            )
        }.onFailure { deferredRetry = null }
    }

    private fun startAsForeground() {
        val locationEnabled = ::locationManager.isInitialized && locationManager.isLocationEnabled
        lastNotificationLocationEnabled = locationEnabled
        startForeground(
            NOTIFICATION_ID,
            buildForegroundNotification(locationEnabled),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
    }

    private fun refreshForegroundNotification(locationEnabled: Boolean) {
        if (!isRunning) return
        // Provider broadcasts can repeat while the visible notification state is unchanged.
        // Avoid rebuilding the same notification and sending another system-service IPC.
        if (lastNotificationLocationEnabled == locationEnabled) return
        lastNotificationLocationEnabled = locationEnabled
        notificationManager.notify(NOTIFICATION_ID, buildForegroundNotification(locationEnabled))
    }

    private fun buildForegroundNotification(locationEnabled: Boolean): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(if (locationEnabled) "Road Conquest is tracking" else "Road Conquest is ready")
            .setContentText(
                if (locationEnabled) "Your driven roads are being saved locally"
                else "Waiting for Android Location to be turned on"
            )
            .setContentIntent(openAppPendingIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.stop_tracking),
                    stopTrackingPendingIntent
                ).build()
            )
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Drive tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Required while Road Conquest records your driving location"
            }
        )
    }

    companion object {
        const val ACTION_LOCATION_UPDATE = "com.roadconquest.app.LOCATION_UPDATE"
        const val ACTION_STATS_UPDATED = "com.roadconquest.app.STATS_UPDATED"
        const val ACTION_ROADS_UPDATED = "com.roadconquest.app.ROADS_UPDATED"
        const val ACTION_EXPLORATION_UPDATED = "com.roadconquest.app.EXPLORATION_UPDATED"
        const val ACTION_PLACE_CANDIDATE_UPDATED = "com.roadconquest.app.PLACE_CANDIDATE_UPDATED"
        const val ACTION_TRACKING_STATE_CHANGED = "com.roadconquest.app.TRACKING_STATE_CHANGED"
        const val ACTION_STOP_UNTIL_OPEN = "com.roadconquest.app.STOP_UNTIL_OPEN"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_BEARING = "bearing"
        const val EXTRA_SPEED_MPS = "speed_mps"
        const val EXTRA_ACCURACY_M = "accuracy_m"
        const val EXTRA_LOCATION_AGE_MS = "location_age_ms"

        private const val CHANNEL_ID = "roadconquest_tracking"
        private const val NOTIFICATION_ID = 4101
        private const val LOCATION_INTERVAL_MS = 3_000L
        private const val LOCATION_MIN_UPDATE_INTERVAL_MS = 1_500L
        // Require time between updates, not movement: stopped cars still need fresh live fixes.
        private const val LOCATION_MIN_DISTANCE_M = 0f
        private const val LOCATION_MONITOR_CHECK_MS = 60_000L
        private const val LOCATION_STALE_AFTER_MS = 2 * 60_000L
        private const val LOCATION_RECOVERY_COOLDOWN_MS = 5 * 60_000L

        internal fun shouldRecoverLocationUpdates(now: Long, lastFix: Long, lastRecovery: Long): Boolean =
            now >= lastFix && now >= lastRecovery &&
                now - lastFix >= LOCATION_STALE_AFTER_MS &&
                now - lastRecovery >= LOCATION_RECOVERY_COOLDOWN_MS
        private const val MAX_ACCURACY_M = 50f
        private const val MAX_PREVIEW_ACCURACY_M = 100f
        private const val MAX_BASELINE_ACCURACY_M = 25f
        private const val MIN_DRIVING_SPEED_MPS = 2.2f
        private const val MAX_PLAUSIBLE_SPEED_MPS = 100f
        private const val MIN_BEARING_SPEED_MPS = 0.5f
        private const val MAX_MOTION_SAMPLE_AGE_MS = 30_000L
        private const val MAX_LOCATION_AGE_MS = 60_000L
        private const val MAX_LOCATION_AGE_NANOS = MAX_LOCATION_AGE_MS * 1_000_000L
        private const val MAX_START_ANCHOR_AGE_MS = 10_000L
        private const val BACKGROUND_PLACE_DISCOVERY_INTERVAL_MS = 60_000L
        // Check locally saved roads twice per minute; remote API requests are
        // independently throttled, so local progress does not consume credits.
        private const val MATCH_INTERVAL_MS = 30_000L
        // Match new roads online no more frequently than the original seven
        // minute batching window, including forced retries and final flushes.
        private const val MIN_REMOTE_MATCH_INTERVAL_MS = 7 * 60_000L

        internal fun remainingRemoteMatchingDelayMs(
            nowElapsed: Long, lastElapsed: Long,
            pointsPerRequest: Int = com.roadconquest.app.matching.GeoapifyMatcher.MAX_MATCH_POINTS,
            pendingPointCount: Int = 0
        ): Long {
            if (lastElapsed <= 0 || nowElapsed < lastElapsed) return 0L
            val intervalPoints = if (pointsPerRequest == com.roadconquest.app.matching.GeoapifyMatcher.MAX_MATCH_POINTS &&
                pendingPointCount >= MATCH_BATCH_SIZE - 3) MATCH_BATCH_SIZE else pointsPerRequest
            val interval = (MIN_REMOTE_MATCH_INTERVAL_MS * intervalPoints /
                com.roadconquest.app.matching.GeoapifyMatcher.MAX_MATCH_POINTS).coerceAtLeast(30_000L)
            return (interval - (nowElapsed - lastElapsed)).coerceAtLeast(0L)
        }
        private const val MIN_STRAIGHT_SAMPLE_INTERVAL_MS = 5_500L
        private const val MIN_RETAINED_BEND_DEGREES = 18f
        private const val PLACE_CANDIDATE_MIN_DISTANCE_M = 55f
        private const val MAX_EXPLORED_CELL_CACHE = 4_096
        // A partial result usually means an intersection needs one or two newer fixes.
        // Retry on the normal matching cadence so turn holes close while the drive is still live.
        private const val MATCH_RETRY_AFTER_PARTIAL_MS = 10_000L
        private const val LONG_MATCH_RETRY_AFTER_PARTIAL_MS = 5 * 60_000L
        private const val MATCH_RETRY_AFTER_FAILURE_MS = 5 * 60 * 1000L
        private const val MATCH_SINGLE_POINT_DEFERRAL_MS = 30_000L
        private const val MATCH_BACKLOG_CONTINUATION_MS = 30_000L
        // 100 TOTAL fixes, including context, fit one Geoapify credit. A full
        // live window may leave sooner; sparse windows keep the seven-minute deadline.
        private const val MATCH_BATCH_SIZE = 100
        private const val MAX_MATCH_BATCHES_PER_RUN = 4
        private const val MATCH_BATCH_PAUSE_MS = 1_100L

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
