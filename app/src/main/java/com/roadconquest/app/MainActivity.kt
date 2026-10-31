package com.roadconquest.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.data.RoadRecord
import com.roadconquest.app.data.ProgressionRepository
import com.roadconquest.app.data.LocalDataReset
import com.roadconquest.app.account.AccountOnboarding
import com.roadconquest.app.onboarding.MapTour
import com.roadconquest.app.onboarding.ExitConfirmation
import com.roadconquest.app.achievements.Achievements
import com.roadconquest.app.export.DataExporter
import com.roadconquest.app.export.DataImporter
import com.roadconquest.app.map.MapRenderer
import com.roadconquest.app.map.FogBitmapRenderer
import com.roadconquest.app.map.PlaceOverlayInfo
import com.roadconquest.app.map.MapMode
import com.roadconquest.app.map.PlaceOverlayMode
import com.roadconquest.app.map.PlacePopulationLookup
import com.roadconquest.app.progression.ProgressionManager
import com.roadconquest.app.util.LocationProviders
import com.roadconquest.app.util.Prefs
import com.roadconquest.app.util.Appearance
import com.roadconquest.app.util.MeasurementUnits
import com.roadconquest.app.util.UiTheme
import com.roadconquest.app.util.SystemSettingsNavigator
import com.roadconquest.app.util.StatsText
import com.roadconquest.app.util.ForegroundSession
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import android.util.Log
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private var appliedTheme = UiTheme.SYSTEM
    private var appliedGoldUi = false
    private var safeLeft = 0
    private var safeRight = 0
    private var mapWasCentered = false
    private var startingFallbackApplied = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Appearance.wrap(newBase))
    }

    private lateinit var mapView: MapView
    private var startupFrameListener: MapView.OnDidFinishRenderingFrameListener? = null
    private var startupCoverTimeout: Runnable? = null
    private var startupWaitingForStartingLocation = false
    private lateinit var enableButton: Button
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var pointsText: TextView
    private lateinit var repository: TrackingRepository
    private lateinit var locationManager: LocationManager
    private var renderer: MapRenderer? = null
    private var activeMap: MapLibreMap? = null
    private var previewBearingDegrees = 0.0
    private var startAfterPermissionGrant = false
    private var started = false
    private var resumed = false
    private var refreshRoadsAfterStop = false
    private var enteredForeground = false
    private var recreatingForAppearance = false
    private var accountPrompt: AlertDialog? = null
    private var locationDisclosure: AlertDialog? = null
    private var mapTour: MapTour? = null
    private var pendingTourStep: Int? = null
    private var pendingTourPreview = false
    private var pendingResumeStoppedTracking = false
    private var exitPrompt: Dialog? = null
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private val summaryExecutor = Executors.newSingleThreadExecutor()
    private val populationExecutor = Executors.newSingleThreadExecutor()
    private val discoveryExecutor = Executors.newSingleThreadExecutor()
    private val placeResolutionInFlight = AtomicBoolean(false)
    private var lastPlaceBackfillElapsed = -1L
    private val queuedPlaceResolution = Runnable { if (resumed) resolvePendingPlaces() }
    @Volatile private var summaryGeneration = 0
    @Volatile private var roadDetailsGeneration = 0
    private val statsHandler = Handler(Looper.getMainLooper())
    private var statsRefreshScheduled = false
    private val statsRefresh = Runnable {
        statsRefreshScheduled = false
        if (resumed && !isDestroyed) {
            refreshControls()
            renderer?.refreshTracking()
        }
    }
    /** Coalesce high-frequency road/fog notifications into one lightweight UI refresh. */
    private fun scheduleStatsRefresh() {
        if (resumed && !statsRefreshScheduled) {
            statsRefreshScheduled = true
            statsHandler.postDelayed(statsRefresh, 5_000L)
        }
    }

    private var lastPreviewLocation: Location? = null
    // TrackingService location broadcasts arrive every few seconds while the map is open.
    // Remember whether this listener is actually registered so each broadcast does not perform
    // another unnecessary LocationManager.removeUpdates() IPC after preview already stopped.
    private var previewLocationRegistered = false
    private var baselinePreviewCapturedForRegistration = false
    private var baselinePreviewRequestElapsedNanos = 0L
    private var baselinePreviewRequestWallMillis = 0L
    private val previewLocationRequest by lazy(LazyThreadSafetyMode.NONE) {
        LocationRequest.Builder(PREVIEW_INTERVAL_MS)
            .setMinUpdateIntervalMillis(PREVIEW_MIN_UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(PREVIEW_MIN_DISTANCE_M)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .build()
    }
    private val previewLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (Prefs.isTrackingPaused(this@MainActivity) || TrackingService.isRunning || !isFreshLocation(location) ||
                !location.hasAccuracy() || location.accuracy > MAX_PREVIEW_ACCURACY_M
            ) return
            if (!LocationProviders.isBetterFix(location, lastPreviewLocation)) return
            lastPreviewLocation = Location(location)
            if (!baselinePreviewCapturedForRegistration && !location.isMock &&
                location.accuracy in 0.01f..MAX_BASELINE_ACCURACY_M &&
                LocationProviders.isFixSince(
                    location,
                    baselinePreviewRequestElapsedNanos,
                    baselinePreviewRequestWallMillis
                )
            ) {
                baselinePreviewCapturedForRegistration = true
                captureStartingPlace(Location(location))
            }
            if (location.hasBearing() && (!location.hasSpeed() || location.speed >= 0.5f)) {
                previewBearingDegrees = location.bearing.toDouble()
            }
            renderer?.updateCar(location.latitude, location.longitude, previewBearingDegrees, locationAgeMillis(location),
                LocationProviders.measuredSpeedOrNull(location) ?: Float.NaN, location.accuracy)
        }

        override fun onProviderEnabled(provider: String) {
            if (resumed) { refreshControls(); startPreviewLocation() }
        }
        override fun onProviderDisabled(provider: String) {
            if (!resumed) return
            refreshControls()
            if (locationManager.isLocationEnabled) startPreviewLocation()
        }
    }

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TrackingService.ACTION_LOCATION_UPDATE -> {
                    if (Prefs.isTrackingPaused(this@MainActivity)) return
                    stopPreviewLocation()
                    renderer?.updateCar(
                        intent.getDoubleExtra(TrackingService.EXTRA_LATITUDE, 0.0),
                        intent.getDoubleExtra(TrackingService.EXTRA_LONGITUDE, 0.0),
                        intent.getDoubleExtra(TrackingService.EXTRA_BEARING, 0.0),
                        intent.getLongExtra(TrackingService.EXTRA_LOCATION_AGE_MS, 0L),
                        intent.getFloatExtra(TrackingService.EXTRA_SPEED_MPS, Float.NaN),
                        intent.getFloatExtra(TrackingService.EXTRA_ACCURACY_M, Float.POSITIVE_INFINITY)
                    )
                }
                TrackingService.ACTION_STATS_UPDATED -> scheduleStatsRefresh()
                DataImporter.ACTION_LOCAL_PROGRESS_RESTORED -> {
                    // A confirmed restore can finish while the map stays foregrounded.
                    // Recreate only an obsolete repository; resumed screens already
                    // handle any missed notification through the generation check.
                    if (!repository.isHistoryCurrent()) {
                        recreatingForAppearance = true
                        recreate()
                    }
                }
                LocationManager.PROVIDERS_CHANGED_ACTION, LocationManager.MODE_CHANGED_ACTION -> {
                    refreshControls()
                    if (!TrackingService.isRunning && !Prefs.isManualOnly(this@MainActivity) &&
                        !Prefs.isTrackingPaused(this@MainActivity) && locationManager.isLocationEnabled
                    ) {
                        startTrackingIfPossible(requestIfMissing = false)
                    }
                    if (!TrackingService.isRunning) startPreviewLocation()
                }
                TrackingService.ACTION_ROADS_UPDATED -> {
                    renderer?.refreshRoads()
                    refreshControls()
                }
                TrackingService.ACTION_EXPLORATION_UPDATED -> {
                    renderer?.refreshExploration()
                    // Walking can unlock tiles without producing road-driving statistics.
                    // Keep the visible wallet/milestones up to date with the same bounded
                    // UI refresh as accepted driving fixes, with no added GPS work.
                    scheduleStatsRefresh()
                    if (resumed) resolvePendingPlaces()
                }
                TrackingService.ACTION_PLACE_CANDIDATE_UPDATED -> {
                    if (resumed) resolvePendingPlaces()
                }
                TrackingService.ACTION_TRACKING_STATE_CHANGED -> {
                    refreshControls()
                    if (TrackingService.isRunning || Prefs.isTrackingPaused(this@MainActivity)) stopPreviewLocation() else startPreviewLocation()
                }
                else -> return
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Appearance.themeRes(this))
        super.onCreate(savedInstanceState)
        appliedTheme = Prefs.uiTheme(this)
        appliedGoldUi = Prefs.isGoldUiEnabled(this)
        mapWasCentered = savedInstanceState?.getBoolean(STATE_MAP_CENTERED) ?: false
        WindowCompat.enableEdgeToEdge(window)
        updateSystemBarContrast()
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)
        pendingTourStep = savedInstanceState?.getInt(STATE_TOUR_STEP, -1)?.takeIf { it >= 0 }
            ?: if (intent.getBooleanExtra(EXTRA_SHOW_TOUR, false)) 0 else null
        pendingTourPreview = savedInstanceState?.getBoolean(STATE_TOUR_PREVIEW) ?: false
        pendingResumeStoppedTracking = savedInstanceState?.getBoolean(STATE_PENDING_RESUME) ?: false
        intent.removeExtra(EXTRA_SHOW_TOUR)
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = android.window.OnBackInvokedCallback { handleMapBack() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            }
        }

        repository = TrackingRepository(this)
        // Recover a privacy deletion interrupted by process death before doing ordinary
        // startup cleanup. The persisted pending marker also keeps tracking from auto-resuming.
        summaryExecutor.execute {
            val cleanup = runCatching {
                if (Prefs.isDeviceDataDeletionPending(applicationContext)) {
                    LocalDataReset.clearStoppedData(applicationContext)
                } else {
                    DataExporter.clearTemporarySnapshots(applicationContext)
                    com.roadconquest.app.export.DataImporter.clearTemporaryFiles(applicationContext)
                }
            }
            cleanup.exceptionOrNull()?.let {
                Log.w("RoadConquest", "Could not finish startup data cleanup", it)
            }
        }
        startAfterPermissionGrant = savedInstanceState?.getBoolean(STATE_START_AFTER_PERMISSION) ?: false
        locationManager = getSystemService(LocationManager::class.java)
        mapView = findViewById(R.id.mapView)
        enableButton = findViewById(R.id.enableButton)
        statusText = findViewById(R.id.statusText)
        findViewById<TextView>(R.id.locationIqCredit).apply {
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            visibility = if (BuildConfig.MAP_GATEWAY_URL.isBlank()) View.GONE else View.VISIBLE
        }
        statsText = findViewById(R.id.statsText)
        pointsText = findViewById(R.id.pointsText)
        pointsText.setOnClickListener { startActivity(Intent(this, ShopActivity::class.java)) }
        mapView.onCreate(savedInstanceState)
        // A one-time cover prevents a freshly installed/upgraded build from
        // exposing the low-detail overview while preferred vector tiles arrive.
        // Never block the map indefinitely if offline or a tile provider stalls.
        if (getSharedPreferences("roadconquest_map_startup", MODE_PRIVATE)
                .getInt("shown_version", -1) != BuildConfig.VERSION_CODE) {
            findViewById<View>(R.id.mapStartupCover).visibility = View.VISIBLE
            startupCoverTimeout = Runnable { revealStartupMap() }.also {
                mapView.postDelayed(it, 3_000L)
            }
        }

        val settingsButton = findViewById<ImageButton>(R.id.settingsButton)
        settingsButton.imageTintList = ColorStateList.valueOf(Appearance.mapSettingsIconColor(this))
        val centerButton = findViewById<ImageButton>(R.id.centerCarButton)
        val overlayButton = findViewById<ImageButton>(R.id.overlayButton)
        val controlPanel = findViewById<View>(R.id.controlPanel)
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        centerButton.setOnClickListener {
            if (renderer?.centerOnCar() != true) {
                showFreshCachedLocation()
                if (renderer?.centerOnCar() != true) {
                    Toast.makeText(this, R.string.waiting_for_location, Toast.LENGTH_SHORT).show()
                }
            }
        }
        overlayButton.setOnClickListener { showOverlayPicker() }
        // Quick destinations remain available from the map without changing driving state.
        findViewById<Button>(R.id.tripsNavButton).setOnClickListener {
            startActivity(Intent(this, TripHistoryActivity::class.java))
        }
        findViewById<Button>(R.id.badgesNavButton).setOnClickListener {
            startActivity(Intent(this, AchievementsActivity::class.java))
        }
        findViewById<Button>(R.id.weeklyGoalsNavButton).setOnClickListener {
            startActivity(Intent(this, ChallengesActivity::class.java))
        }
        findViewById<Button>(R.id.forumNavButton).setOnClickListener {
            startActivity(Intent(this, ForumActivity::class.java))
        }
        applySafeAreaInsets(settingsButton, centerButton, overlayButton, controlPanel, statsText, pointsText)
        enableButton.setOnClickListener { handleEnableButton() }

        mapView.getMapAsync { map ->
            if (isDestroyed) return@getMapAsync
            activeMap = map
            configureMapChrome(map, controlPanel)
            map.addOnMapClickListener { point ->
                val overlayInfo = renderer?.overlayInfoAt(map.projection.toScreenLocation(point))
                if (overlayInfo != null) {
                    showOverlayInfo(overlayInfo)
                    true
                } else {
                    if (map.cameraPosition.zoom >= FogBitmapRenderer.MIN_ROAD_ZOOM) {
                        val radiusMeters = (
                            map.projection.getMetersPerPixelAtLatitude(point.latitude) * 16.0
                        ).coerceIn(12.0, 100.0)
                        loadRoadDetails(point.latitude, point.longitude, radiusMeters)
                    }
                    false
                }
            }
            renderer = MapRenderer(this, map, repository, mapView, mapWasCentered).also { renderer ->
                renderer.initialize {
                    showFreshCachedOrStartingLocation()
                    if (findViewById<View>(R.id.mapStartupCover).visibility == View.VISIBLE) {
                        val listener = MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
                            if (fully && !startupWaitingForStartingLocation) revealStartupMap()
                        }
                        startupFrameListener = listener
                        mapView.addOnDidFinishRenderingFrameListener(listener)
                        map.triggerRepaint()
                    }
                }
                if (!started) renderer.pauseViewport()
            }
        }
    }

    private fun revealStartupMap() {
        startupFrameListener?.let(mapView::removeOnDidFinishRenderingFrameListener)
        startupFrameListener = null
        startupCoverTimeout?.let(mapView::removeCallbacks)
        startupCoverTimeout = null
        val cover = findViewById<View>(R.id.mapStartupCover)
        if (cover.visibility != View.VISIBLE) return
        getSharedPreferences("roadconquest_map_startup", MODE_PRIVATE)
            .edit().putInt("shown_version", BuildConfig.VERSION_CODE).apply()
        cover.animate().alpha(0f).setDuration(150L).withEndAction {
            cover.visibility = View.GONE
        }.start()
    }

    override fun onStart() {
        super.onStart()
        started = true
        enteredForeground = ForegroundSession.app.onStart()
        val filter = IntentFilter(TrackingService.ACTION_LOCATION_UPDATE).apply {
            addAction(TrackingService.ACTION_STATS_UPDATED)
            addAction(DataImporter.ACTION_LOCAL_PROGRESS_RESTORED)
            addAction(TrackingService.ACTION_ROADS_UPDATED)
            addAction(TrackingService.ACTION_EXPLORATION_UPDATED)
            addAction(TrackingService.ACTION_PLACE_CANDIDATE_UPDATED)
            addAction(TrackingService.ACTION_TRACKING_STATE_CHANGED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            this,
            locationReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        trackingReceiverRegistered = true
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        updateSystemBarContrast()
        com.roadconquest.app.notifications.OptionalNotifications.initialize(this)
        if (!repository.isHistoryCurrent()) {
            recreatingForAppearance = true
            recreate()
            return
        }
        // Closing the notification shade only resumes an already-started activity.
        val resumeStoppedTracking = enteredForeground && Prefs.shouldResumePausedTracking(this)
        enteredForeground = false
        if (appliedTheme != Prefs.uiTheme(this) || appliedGoldUi != Prefs.isGoldUiEnabled(this)) {
            if (resumeStoppedTracking) {
                Prefs.setTrackingPaused(this, false)
                startTrackingIfPossible(requestIfMissing = false)
            }
            recreatingForAppearance = true
            recreate()
            return
        }
        resumed = true
        if (Prefs.keepMapScreenAwake(this)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        mapView.onResume()
        val overlayMode = Prefs.placeOverlayMode(this)
        renderer?.setTownMedalsEnabled(Prefs.showTownMedals(this))
        renderer?.setAutoFollowEnabled(Prefs.isAutoFollowCar(this))
        renderer?.setFogEnabled(Prefs.isFogEnabled(this))
        renderer?.setMapMode(Prefs.mapMode(this))
        val overlayModeChanged = renderer?.placeOverlayMode() != overlayMode
        renderer?.setPlaceOverlayMode(overlayMode)
        // setPlaceOverlayMode already refreshes after an actual mode change. Only reload here
        // when the mode stayed the same so returning to the app can still pick up new cache data.
        if (!overlayModeChanged && overlayMode != PlaceOverlayMode.NONE) {
            renderer?.refreshPlaceOverlays()
        }
        renderer?.refreshCosmetics()
        // Road updates can finish while this activity is stopped and its receiver is
        // unregistered. A mere pause/resume keeps the receiver registered and can reuse cache.
        renderer?.resumeViewport(refreshRoadsAfterStop)
        refreshRoadsAfterStop = false
        refreshControls()
        pendingResumeStoppedTracking = pendingResumeStoppedTracking || resumeStoppedTracking
        if (mapTour != null) return
        if (pendingTourStep != null || !Prefs.isTourHandled(this)) {
            mapTour = MapTour(findViewById(R.id.root), pendingTourStep ?: 0, pendingTourPreview) {
                mapTour = null
                if (resumed && !isDestroyed) continueAfterTour()
            }
            pendingTourStep = null
            pendingTourPreview = false
            return
        }
        continueAfterTour()
    }

    private fun updateSystemBarContrast() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            // The top bar is over the map; the bottom bar is over the themed
            // control panel. Fog-off daytime maps need dark top icons too.
            isAppearanceLightStatusBars = !Prefs.isFogEnabled(this@MainActivity) &&
                Prefs.mapMode(this@MainActivity) in listOf(MapMode.STREETS, MapMode.MINIMAL)
            isAppearanceLightNavigationBars = !Appearance.isDark(this@MainActivity)
        }
    }

    private fun continueAfterTour() {
        if (accountPrompt?.isShowing == true) return
        accountPrompt = AccountOnboarding.showIfNeeded(this) {
            if (resumed && !isDestroyed) completePendingForegroundStart()
        }
        if (accountPrompt != null) return
        completePendingForegroundStart()
    }

    private fun completePendingForegroundStart() {
        val resume = pendingResumeStoppedTracking
        pendingResumeStoppedTracking = false
        completeForegroundStart(resume)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_TOUR, false)) {
            pendingTourStep = 0
            pendingTourPreview = false
            intent.removeExtra(EXTRA_SHOW_TOUR)
        }
    }

    @Deprecated("Legacy Back dispatch for Android 12. Newer versions use OnBackInvokedCallback")
    @SuppressLint("GestureBackNavigation") // Android 12 fallback; API 33+ registers OnBackInvokedCallback in onCreate.
    override fun onBackPressed() { handleMapBack() }

    private fun handleMapBack() {
        mapTour?.let {
            if (!it.back()) moveTaskToBack(true)
            return
        }
        if (exitPrompt?.isShowing == true) return
        if (!Prefs.isExitConfirmationEnabled(this)) {
            moveTaskToBack(true)
            return
        }
        exitPrompt = ExitConfirmation.show(this, TrackingService.isRunning) { moveTaskToBack(true) }
    }

    private fun completeForegroundStart(resumeStoppedTracking: Boolean) {
        if (resumeStoppedTracking) Prefs.setTrackingPaused(this, false)
        ensureBasicPermissions()
        if (!Prefs.isManualOnly(this) || resumeStoppedTracking) {
            startTrackingIfPossible(requestIfMissing = false)
            maybeExplainBackgroundLocation()
        }
        showFreshCachedOrStartingLocation()
        if (!TrackingService.isRunning) startPreviewLocation()
        resolvePendingPlaces()
    }

    private fun captureStartingPlace(location: Location) {
        discoveryExecutor.execute {
            val result = runCatching {
                ProgressionRepository(applicationContext).recordBaselineCandidate(location)
            }
            if (result.isFailure) {
                Log.e("RoadConquest", "Could not save starting place location", result.exceptionOrNull())
                runOnUiThread {
                    if (!isDestroyed) baselinePreviewCapturedForRegistration = false
                }
                return@execute
            }
            if (!result.getOrThrow()) return@execute

            val added = runCatching {
                ProgressionManager.resolvePendingPlaces(applicationContext, 6)
            }.getOrDefault(0)
            if (!isDestroyed) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    renderer?.refreshExploration()
                    if (Prefs.placeOverlayMode(this) != PlaceOverlayMode.NONE) {
                        renderer?.refreshPlaceOverlays()
                    }
                    if (added > 0) refreshControls()
                    // The initial baseline may have resolved on this separate worker
                    // after the resume-time scan found no visited places. Kick off the
                    // regular bounded history/retry scheduler even while stationary.
                    if (resumed) resolvePendingPlaces()
                }
            }
        }
    }

    private fun resolvePendingPlaces() {
        if (!placeResolutionInFlight.compareAndSet(false, true)) return
        discoveryExecutor.execute {
            val resolved = runCatching {
                val now = SystemClock.elapsedRealtime()
                if (lastPlaceBackfillElapsed < 0L ||
                    now - lastPlaceBackfillElapsed >= 20_000L
                ) {
                    // Recover actual saved GPS crossings of short municipalities.
                    // Only points after the current progression baseline are eligible.
                    ProgressionRepository(applicationContext).backfillPlaceCandidates(3_000)
                    lastPlaceBackfillElapsed = now
                }
                ProgressionManager.resolvePendingPlacesWithDetails(this, 12)
            }.getOrNull()
            val pending = runCatching {
                val repository = ProgressionRepository(applicationContext)
                repository.nextPlaceCandidateAttemptMillis() to repository.hasUnscannedPlaceHistory()
            }.getOrNull()
            placeResolutionInFlight.set(false)
            if (!isDestroyed) {
                runOnUiThread {
                    if (!isDestroyed) {
                        statsHandler.removeCallbacks(queuedPlaceResolution)
                        if (resumed) {
                            // An exhausted candidate page can still leave unscanned GPS history;
                            // deferred geocoder lookups also need a wakeup without movement.
                            val retryDelay = pending?.first?.let {
                                if (resolved == null) 20_000L
                                else (it - System.currentTimeMillis()).coerceAtLeast(500L)
                            }
                            val backfillDelay = if (pending?.second == true) {
                                (lastPlaceBackfillElapsed + 20_000L -
                                    SystemClock.elapsedRealtime()).coerceAtLeast(500L)
                            } else null
                            val delay = listOfNotNull(retryDelay, backfillDelay).minOrNull()
                            if (delay != null) statsHandler.postDelayed(queuedPlaceResolution, delay)
                        }
                        // Baseline places are zero-point progress, but resolving them can still
                        // make a selected place overlay renderable.
                        // Do not cancel in-flight Census downloads for every
                        // GPS batch that merely revisits known places.
                        if (resolved?.placesUpdated == true) {
                            if (Prefs.placeOverlayMode(this) != PlaceOverlayMode.NONE) {
                                renderer?.refreshPlaceOverlays()
                            }
                            refreshControls()
                        }
                        if (resolved != null && resolved.added > 0) {
                            // Late geocoding of a prior drive does not mean the
                            // user has entered a new town on app launch.
                            val ageMs = System.currentTimeMillis() - resolved.newestVisitAt
                            if (ageMs in 0..120_000L) {
                                Toast.makeText(
                                    this,
                                    if (resolved.added == 1) "Place visited" else "${resolved.added} places visited",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        resumed = false
        statsHandler.removeCallbacks(statsRefresh)
        statsHandler.removeCallbacks(queuedPlaceResolution)
        statsRefreshScheduled = false
        stopPreviewLocation()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        started = false
        ForegroundSession.app.onStop(isChangingConfigurations || recreatingForAppearance)
        renderer?.pauseViewport()
        refreshRoadsAfterStop = true
        renderer?.cancelPlaceOverlayLoads()
        trackingReceiverRegistered = false
        runCatching { unregisterReceiver(locationReceiver) }
        mapView.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        // onStop owns receiver registration state. Do not clear the process-wide flag here:
        // during a configuration handoff a replacement MainActivity may already be started.
        accountPrompt?.dismiss()
        locationDisclosure?.dismiss()
        exitPrompt?.dismiss()
        mapTour?.dismiss()
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        summaryGeneration++
        roadDetailsGeneration++
        statsHandler.removeCallbacksAndMessages(null)
        summaryExecutor.shutdownNow()
        populationExecutor.shutdownNow()
        discoveryExecutor.shutdownNow()
        renderer?.destroy()
        startupFrameListener?.let(mapView::removeOnDidFinishRenderingFrameListener)
        startupFrameListener = null
        startupCoverTimeout?.let(mapView::removeCallbacks)
        startupCoverTimeout = null
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_START_AFTER_PERMISSION, startAfterPermissionGrant)
        outState.putBoolean(STATE_MAP_CENTERED, renderer?.hasCentered ?: mapWasCentered)
        outState.putInt(STATE_TOUR_STEP, mapTour?.step ?: pendingTourStep ?: -1)
        outState.putBoolean(STATE_TOUR_PREVIEW, mapTour?.previewRevealed ?: pendingTourPreview)
        outState.putBoolean(STATE_PENDING_RESUME, pendingResumeStoppedTracking)
        mapView.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        com.roadconquest.app.notifications.OptionalNotifications.initialize(this)
        when (requestCode) {
            REQUEST_BASIC_PERMISSIONS -> {
                if (hasLocationPermission()) {
                    if (!Prefs.isManualOnly(this) || startAfterPermissionGrant) {
                        startTrackingIfPossible(requestIfMissing = startAfterPermissionGrant)
                    }
                    startAfterPermissionGrant = false
                    showFreshCachedLocation()
                    if (!TrackingService.isRunning) startPreviewLocation()
                    maybeExplainBackgroundLocation()
                } else if (hasApproximateLocationPermission()) {
                    startAfterPermissionGrant = false
                    showPreciseLocationDialog()
                } else {
                    startAfterPermissionGrant = false
                }
            }
        }
    }

    private fun handleEnableButton() {
        if (Prefs.isDeviceDataDeletionPending(this)) {
            Toast.makeText(this, "Finishing device data deletion…", Toast.LENGTH_SHORT).show()
            return
        }
        if (Prefs.isTrackingPaused(this)) {
            Prefs.setTrackingPaused(this, false)
            startTrackingIfPossible(requestIfMissing = true)
            return
        }
        if (!Prefs.isManualOnly(this)) return
        if (TrackingService.isRunning) {
            stopService(Intent(this, TrackingService::class.java))
            refreshControls()
            enableButton.post { startPreviewLocation() }
        } else {
            startTrackingIfPossible(requestIfMissing = true)
        }
    }

    private fun startTrackingIfPossible(requestIfMissing: Boolean) {
        if (Prefs.isDeviceDataDeletionPending(this) || Prefs.isTrackingPaused(this)) return
        if (!hasLocationPermission()) {
            if (requestIfMissing) {
                startAfterPermissionGrant = true
                ensureBasicPermissions()
            }
            return
        }
        if (!locationManager.isLocationEnabled) {
            if (requestIfMissing) {
                showLocationOffDialog()
                return
            }
            // Android 14+ rejects promotion to a location foreground service while system
            // Location is off. The receiver below starts tracking as soon as Location returns.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        }
        if (!TrackingService.isRunning) {
            stopPreviewLocation()
            runCatching {
                ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
            }.onFailure {
                if (requestIfMissing) {
                    AlertDialog.Builder(this)
                        .setTitle("Could not start tracking")
                        .setMessage("Android blocked the tracking service. Open Road Conquest again and verify location permissions and battery settings.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
        refreshControls()
    }

    private fun ensureBasicPermissions() {
        if (!hasLocationPermission() && !Prefs.isLocationDisclosureShown(this)) {
            showLocationDisclosure()
            return
        }
        val needed = mutableListOf<String>()
        if (!hasLocationPermission()) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQUEST_BASIC_PERMISSIONS)
    }

    private fun showLocationDisclosure() {
        if (locationDisclosure?.isShowing == true) return
        locationDisclosure = AlertDialog.Builder(this)
            .setTitle("Location use")
            .setMessage(
                "Road Conquest collects precise location data to record driven roads and reveal visited places, including in the background when automatic tracking is enabled and the app is closed or not in use. " +
                    "It also uses location to show your position. Road matching sends GPS batches, timestamps and accuracy through Road Conquest's map service to Geoapify or fallback LocationIQ. Foreground U.S. town discovery sends individual saved GPS coordinates to Census TIGERweb. Verified scoring sends live GPS and app-integrity evidence only if you explicitly enable it."
            )
            .setPositiveButton("Continue") { _, _ ->
                Prefs.setLocationDisclosureShown(this, true)
                ensureBasicPermissions()
            }
            .setNegativeButton("Not now", null)
            .create()
        locationDisclosure?.setOnDismissListener { locationDisclosure = null }
        locationDisclosure?.show()
    }

    private fun maybeExplainBackgroundLocation() {
        if (!hasLocationPermission() || hasBackgroundLocation() || Prefs.isBackgroundPromptShown(this)) return
        Prefs.setBackgroundPromptShown(this, true)

        AlertDialog.Builder(this)
            .setTitle("Allow all-the-time location")
            .setMessage(
                "For Road Conquest to restart tracking automatically after a reboot or service restart when Android Location is on, " +
                    "set Location permission to ‘Allow all the time’ in Android settings. A tracking service that you start while Road Conquest is open can continue after you leave the app."
            )
            .setPositiveButton("Open settings") { _, _ -> openAppSettings() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun showPreciseLocationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Precise location required")
            .setMessage("Road Conquest needs Precise location to distinguish nearby roads. Enable Precise location for Road Conquest in Android settings.")
            .setPositiveButton("Open settings") { _, _ -> openAppSettings() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun showLocationOffDialog() {
        AlertDialog.Builder(this)
            .setTitle("Location is off")
            .setMessage("Turn on Android Location to record driven roads.")
            .setPositiveButton("Location settings") { _, _ ->
                SystemSettingsNavigator.open(this, SystemSettingsNavigator.Destination.DEVICE_LOCATION)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAppSettings() {
        SystemSettingsNavigator.open(this, SystemSettingsNavigator.Destination.LOCATION_PERMISSION)
    }

    /** Unchanged labels need no new layout or accessibility invalidation. */
    private fun TextView.setTextIfDifferent(next: String) {
        if (text.toString() != next) text = next
    }

    private fun refreshControls() {
        exitPrompt?.takeIf { it.isShowing }?.let { ExitConfirmation.updateTracking(it, TrackingService.isRunning) }
        statsHandler.removeCallbacks(statsRefresh)
        statsRefreshScheduled = false
        // Snapshot state once for this UI refresh. Besides avoiding repeated preference/permission
        // lookups, this prevents one refresh from mixing values if Android changes state midway.
        val deletionPending = Prefs.isDeviceDataDeletionPending(this)
        val trackingPaused = Prefs.isTrackingPaused(this)
        val hasPreciseLocation = hasLocationPermission()
        val hasApproximateLocation = if (hasPreciseLocation) false else hasApproximateLocationPermission()
        val locationEnabled = locationManager.isLocationEnabled
        val manualOnly = Prefs.isManualOnly(this)
        val active = TrackingService.isRunning

        if (deletionPending || trackingPaused || !hasPreciseLocation || !locationEnabled) {
            renderer?.clearCurrentLocation()
        }
        val statusLabel = when {
            deletionPending -> "Deleting saved data…"
            trackingPaused -> getString(R.string.tracking_paused)
            !hasPreciseLocation && hasApproximateLocation -> "Precise location needed"
            !hasPreciseLocation -> "Location permission needed"
            !locationEnabled -> if (active) "Paused • Location off" else "Location off"
            active -> "Tracking"
            else -> "Not tracking"
        }
        // Avoid repeating the same live-region announcement on each data refresh.
        statusText.setTextIfDifferent(statusLabel)

        val generation = ++summaryGeneration
        summaryExecutor.execute {
            if (generation != summaryGeneration) return@execute
            val result = runCatching {
                val summary = repository.getSummary()
                val deleting = Prefs.isDeviceDataDeletionPending(this)
                val progression = if (deleting) {
                    ProgressionRepository(this).snapshot()
                } else {
                    ProgressionManager.sync(this, summary)
                }
                val unlocked = if (deleting) {
                    emptyList()
                } else {
                    Achievements.newlyUnlocked(
                        this,
                        summary,
                        ProgressionManager.metrics(progression)
                    )
                }
                Triple(summary, progression, unlocked)
            }
            runOnUiThread {
                if (isDestroyed || generation != summaryGeneration) return@runOnUiThread
                result.fold(
                    onSuccess = { (value, progression, unlocked) ->
                        statsText.setTextIfDifferent(StatsText.mapOverview(this, value, progression.tilesUnlocked))
                        val points = String.format(Locale.getDefault(), "%,d", progression.balance)
                        pointsText.setTextIfDifferent("Garage · ⚔ $points")
                        val pointsDescription = "Garage and shop. $points points. Double tap to open."
                        if (pointsText.contentDescription?.toString() != pointsDescription)
                            pointsText.contentDescription = pointsDescription
                        if (unlocked.isNotEmpty()) {
                            val reward = unlocked.sumOf { it.rewardPoints }
                            val message = if (unlocked.size == 1) {
                                "Achievement unlocked: " + unlocked.first().title +
                                    String.format(Locale.getDefault(), " (+%,d points)", reward)
                            } else {
                                String.format(
                                    Locale.getDefault(),
                                    "%d achievements unlocked (+%,d points)",
                                    unlocked.size,
                                    reward
                                )
                            }
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        }
                    },
                    onFailure = { error ->
                        Log.e("RoadConquest", "Could not load data summary", error)
                        statsText.setTextIfDifferent(getString(R.string.saved_data_unavailable))
                        pointsText.setTextIfDifferent("Garage · Points unavailable")
                        val pointsDescription = "Open Garage and shop. Points are temporarily unavailable."
                        if (pointsText.contentDescription?.toString() != pointsDescription)
                            pointsText.contentDescription = pointsDescription
                    }
                )
            }
        }

        if (deletionPending) {
            enableButton.setTextIfDifferent("Deleting device data…")
            enableButton.isEnabled = false
        } else if (trackingPaused) {
            enableButton.setTextIfDifferent(getString(R.string.resume_tracking))
            enableButton.isEnabled = true
        } else if (!manualOnly) {
            enableButton.setTextIfDifferent("Enabled automatically")
            enableButton.isEnabled = false
        } else {
            enableButton.isEnabled = true
            enableButton.setTextIfDifferent(if (active) getString(R.string.disable) else getString(R.string.enable))
        }
    }

    private fun showOverlayPicker() {
        val modes = arrayOf(
            PlaceOverlayMode.NONE,
            PlaceOverlayMode.COUNTRY,
            PlaceOverlayMode.STATE,
            PlaceOverlayMode.TOWN
        )
        val choices = arrayOf("No overlay", "Countries: blue", "States and regions: purple", "Towns: green")
        val current = renderer?.placeOverlayMode() ?: Prefs.placeOverlayMode(this)
        AlertDialog.Builder(this).setTitle("Map overlay")
            .setSingleChoiceItems(choices, modes.indexOf(current).coerceAtLeast(0)) { dialog, index ->
                val next = modes[index]
                if (next != current) {
                    renderer?.setPlaceOverlayMode(next) ?: Prefs.setPlaceOverlayMode(this, next)
                    if (next != PlaceOverlayMode.NONE) {
                        showFreshCachedLocation()
                        lastPreviewLocation?.takeIf(::isFreshLocation)?.let {
                            ProgressionRepository(this).recordPlaceCandidate(it)
                        }
                        resolvePendingPlaces()
                    }
                }
                dialog.dismiss()
            }
            .setNeutralButton(if (renderer?.isNearbyExplorationVisible() == true)
                "Hide nearby exploration" else "Explore nearby") { _, _ -> showNearbyExploration() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** A numbered, georeferenced 5×5 mile-cell overlay, computed entirely on device. */
    private fun showNearbyExploration() {
        val active = renderer ?: return
        if (active.isNearbyExplorationVisible()) {
            active.hideNearbyExploration()
            return
        }
        val center = activeMap?.cameraPosition?.target ?: return
        active.showNearbyExploration(center.latitude, center.longitude,
            onShown = { explored, total ->
                Toast.makeText(this,
                    "$explored of $total tiles explored. Green is visited, gold is unvisited. Use Layers to hide.",
                    Toast.LENGTH_LONG).show()
            },
            onError = {
                Toast.makeText(this, "Could not load nearby exploration.", Toast.LENGTH_SHORT).show()
            })
    }

    private fun showOverlayInfo(info: PlaceOverlayInfo) {
        val type = when (info.kind) {
            com.roadconquest.app.data.PlaceKind.COUNTRY -> "Country"
            com.roadconquest.app.data.PlaceKind.STATE -> "State or region"
            com.roadconquest.app.data.PlaceKind.TOWN -> "Town"
        }
        var population = info.population?.let {
            String.format(Locale.getDefault(), "%,d", it)
        } ?: if (PlacePopulationLookup.canLookup(info)) "Loading…" else "Not available"
        var populationSource: String? = null
        var firstVisited = "Loading…"
        var lastVisited = "Loading…"
        val area = MeasurementUnits.current().area(info.areaSquareKilometers)
        val completion = info.completionPercent?.let {
            "\n\nExplored: $it% of town area\nBadge: ${info.completionMedal}\n" +
                "Bronze 25% · Silver 50% · Gold 100%\nBased on unlocked map tiles inside the town boundary."
        }.orEmpty()
        val dialog = AlertDialog.Builder(this)
            .setTitle(info.name)
            .setMessage("")
            .setPositiveButton("Close", null)
            .show()
        // Both workers update UI-owned strings on the main thread so neither
        // asynchronous result can erase the other or block ordinary map stats.
        fun updateDialog() {
            val heading = if (populationSource == "2020 Census") "Population (2020 Census)"
                else "Population"
            dialog.setMessage("$type\n$heading: $population\nArea: $area\nFirst visited: $firstVisited\nLast visited: $lastVisited$completion")
        }
        updateDialog()
        summaryExecutor.execute {
            val visits = runCatching {
                ProgressionRepository(applicationContext).placeVisitTimes(info.kind, info.key)
            }.getOrNull()
            runOnUiThread {
                if (isDestroyed || !dialog.isShowing) return@runOnUiThread
                val formatted = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                firstVisited = visits?.let { formatted.format(Date(it.firstVisitedAt)) } ?: "Not available"
                lastVisited = visits?.let { formatted.format(Date(it.lastVisitedAt)) } ?: "Not available"
                updateDialog()
            }
        }
        if (info.population == null && PlacePopulationLookup.canLookup(info)) {
            populationExecutor.execute {
                val result = runCatching { PlacePopulationLookup.lookup(applicationContext, info) }.getOrNull()
                runOnUiThread {
                    if (isDestroyed || !dialog.isShowing) return@runOnUiThread
                    population = result?.let { String.format(Locale.getDefault(), "%,d", it.count) }
                        ?: "Not available"
                    populationSource = result?.source
                    updateDialog()
                }
            }
        }
    }

    private fun loadRoadDetails(latitude: Double, longitude: Double, radiusMeters: Double) {
        val generation = ++roadDetailsGeneration
        summaryExecutor.execute {
            val result = runCatching {
                repository.findRoadNear(latitude, longitude, radiusMeters)?.let { road ->
                    // Normalize legacy visit windows only for the tapped road, off the UI
                    // thread. No visit records are deleted or changed by this estimate.
                    val estimated = repository.estimatedWholeRoadVisitCount(road)
                    road.copy(timesDriven = estimated, timesDrivenExact = false) to
                        repository.roadLengthMeters(road)
                }
            }
            runOnUiThread {
                if (isDestroyed || generation != roadDetailsGeneration) return@runOnUiThread
                result.getOrNull()?.let { (road, lengthMeters) ->
                    showRoadDetails(road, lengthMeters)
                }
            }
        }
    }

    private fun showRoadDetails(road: RoadRecord, lengthMeters: Double) {
        val dateTime = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val length = MeasurementUnits.current().distance(lengthMeters, decimals = 2)
        // Count road-wide journeys from all connected matched segments.
        // This is based on recorded GPS evidence, not a promise that every
        // journey was tracked through a complete GPS outage.
        val driven = "${road.timesDriven} (estimated)"
        AlertDialog.Builder(this)
            .setTitle(road.name)
            .setMessage(
                "First unlocked: " + dateTime.format(Date(road.firstUnlockedAt)) + "\n" +
                    "Last driven: " + dateTime.format(Date(road.lastDrivenAt)) + "\n" +
                    "Times driven on road: " + driven + "\n" +
                    "Section length: " + length
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun configureMapChrome(map: MapLibreMap, controlPanel: View) {
        // Keep MapLibre's required logo/attribution visible above RoadConquest's bottom panel.
        // The default compass occupies the same top-right area as the Settings gear, so disable it.
        map.uiSettings.setCompassEnabled(false)
        map.uiSettings.setLogoGravity(Gravity.BOTTOM or Gravity.START)
        map.uiSettings.setAttributionGravity(Gravity.BOTTOM or Gravity.END)
        val sideMargin = dpToPx(12f)

        fun updateMargins() {
            val fallbackBottom = dpToPx(150f)
            val occupiedBottom = if (mapView.height > 0 && controlPanel.top > 0) {
                (mapView.height - controlPanel.top + dpToPx(8f)).coerceAtLeast(dpToPx(24f))
            } else {
                fallbackBottom
            }
            val left = sideMargin + safeLeft
            val right = sideMargin + safeRight
            val ui = map.uiSettings
            if (ui.logoMarginLeft != left || ui.logoMarginTop != sideMargin ||
                ui.logoMarginRight != right || ui.logoMarginBottom != occupiedBottom) {
                ui.setLogoMargins(left, sideMargin, right, occupiedBottom)
            }
            if (ui.attributionMarginLeft != left || ui.attributionMarginTop != sideMargin ||
                ui.attributionMarginRight != right || ui.attributionMarginBottom != occupiedBottom) {
                ui.setAttributionMargins(left, sideMargin, right, occupiedBottom)
            }
        }

        controlPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateMargins() }
        mapView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateMargins() }
        controlPanel.post { updateMargins() }
    }

    private fun dpToPx(dp: Float): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun applySafeAreaInsets(
        settingsButton: ImageButton,
        centerButton: ImageButton,
        overlayButton: ImageButton,
        controlPanel: View,
        stats: View,
        points: View
    ) {
        val root = findViewById<View>(R.id.root)
        val settingsParams = settingsButton.layoutParams as FrameLayout.LayoutParams
        val settingsTop = settingsParams.topMargin
        val settingsEnd = settingsParams.marginEnd
        val centerParams = centerButton.layoutParams as FrameLayout.LayoutParams
        val centerTop = centerParams.topMargin
        val centerEnd = centerParams.marginEnd
        val overlayParams = overlayButton.layoutParams as FrameLayout.LayoutParams
        val overlayTop = overlayParams.topMargin
        val overlayEnd = overlayParams.marginEnd
        val statsParams = stats.layoutParams as FrameLayout.LayoutParams
        val statsTop = statsParams.topMargin
        val statsStart = statsParams.marginStart
        val pointsParams = points.layoutParams as FrameLayout.LayoutParams
        val pointsTop = pointsParams.topMargin
        val pointsStart = pointsParams.marginStart
        val panelParams = controlPanel.layoutParams as FrameLayout.LayoutParams
        val panelStart = panelParams.marginStart
        val panelEnd = panelParams.marginEnd
        val panelBottom = panelParams.bottomMargin
        val panelPaddingLeft = controlPanel.paddingLeft
        val panelPaddingRight = controlPanel.paddingRight
        val panelPaddingBottom = controlPanel.paddingBottom

        fun sizeStats() {
            if (root.width <= 0) return
            val available = (root.width - safeLeft - safeRight - dpToPx(104f)).coerceAtLeast(dpToPx(100f))
            val target = minOf(dpToPx(210f), available)
            if ((stats as TextView).maxWidth != target) stats.maxWidth = target
            // Long balances should wrap rather than cover the right-side map controls.
            if ((points as TextView).maxWidth != target) points.maxWidth = target
        }

        // The stats card can grow with font scaling and longer mileage/road totals.
        // Keep the points chip below it rather than relying on a fixed top offset.
        var safeTop = 0
        fun alignPoints() {
            val targetTop = maxOf(pointsTop + safeTop,
                if (stats.bottom > 0) stats.bottom + dpToPx(8f) else 0)
            val params = points.layoutParams as FrameLayout.LayoutParams
            if (params.topMargin != targetTop) {
                params.topMargin = targetTop
                points.layoutParams = params
            }
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            sizeStats()
            alignPoints()
        }
        stats.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> alignPoints() }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            safeLeft = safe.left
            safeRight = safe.right
            safeTop = safe.top
            (stats.layoutParams as FrameLayout.LayoutParams).also { params ->
                if (params.topMargin != statsTop + safe.top || params.marginStart != statsStart + safe.left) {
                    params.topMargin = statsTop + safe.top
                    params.marginStart = statsStart + safe.left
                    stats.layoutParams = params
                }
            }
            (points.layoutParams as FrameLayout.LayoutParams).also { params ->
                if (params.marginStart != pointsStart + safe.left) {
                    params.marginStart = pointsStart + safe.left
                    points.layoutParams = params
                }
            }
            alignPoints()
            // Setters can request layout even for identical values. Keep repeated inset/layout
            // callbacks idle, or the next layout will schedule another layout forever.
            sizeStats()
            (settingsButton.layoutParams as FrameLayout.LayoutParams).also { params ->
                if (params.topMargin != settingsTop + safe.top || params.marginEnd != settingsEnd + safe.right) {
                    params.topMargin = settingsTop + safe.top
                    params.marginEnd = settingsEnd + safe.right
                    settingsButton.layoutParams = params
                }
            }
            (centerButton.layoutParams as FrameLayout.LayoutParams).also { params ->
                if (params.topMargin != centerTop + safe.top || params.marginEnd != centerEnd + safe.right) {
                    params.topMargin = centerTop + safe.top
                    params.marginEnd = centerEnd + safe.right
                    centerButton.layoutParams = params
                }
            }
            (overlayButton.layoutParams as FrameLayout.LayoutParams).also { params ->
                if (params.topMargin != overlayTop + safe.top || params.marginEnd != overlayEnd + safe.right) {
                    params.topMargin = overlayTop + safe.top
                    params.marginEnd = overlayEnd + safe.right
                    overlayButton.layoutParams = params
                }
            }
            // The entire panel is a true edge-to-edge bottom sheet, not a floating card.
            // Keep the navigation/gesture inset INSIDE its background, so the controls
            // stay tappable above the system bar while the sheet reaches the screen edge.
            (controlPanel.layoutParams as FrameLayout.LayoutParams).also { params ->
                if (params.marginStart != panelStart || params.marginEnd != panelEnd ||
                    params.bottomMargin != panelBottom) {
                    params.marginStart = panelStart
                    params.marginEnd = panelEnd
                    params.bottomMargin = panelBottom
                    controlPanel.layoutParams = params
                }
            }
            val paddingLeft = panelPaddingLeft + safe.left
            val paddingRight = panelPaddingRight + safe.right
            val paddingBottom = panelPaddingBottom + safe.bottom
            if (controlPanel.paddingLeft != paddingLeft ||
                controlPanel.paddingRight != paddingRight ||
                controlPanel.paddingBottom != paddingBottom) {
                controlPanel.setPadding(paddingLeft, controlPanel.paddingTop, paddingRight, paddingBottom)
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun showFreshCachedOrStartingLocation() {
        showFreshCachedLocation()
        val activeRenderer = renderer ?: return
        if (Prefs.isDeviceDataDeletionPending(this)) return
        // When Android Location is available, wait for the fresh live fix so startup still
        // follows the current car position. When Location is off, however, saved MapView state
        // must not win over the user's zero point. Apply that fallback once per Activity launch,
        // then leave the camera alone so normal panning is not constantly undone.
        if (!::locationManager.isInitialized || locationManager.isLocationEnabled || startingFallbackApplied) return
        startingFallbackApplied = true
        // A fully rendered *world* frame is not ready if the saved starting
        // camera is still being read from disk. Let that move happen first.
        if (findViewById<View>(R.id.mapStartupCover).visibility == View.VISIBLE) {
            startupWaitingForStartingLocation = true
        }

        summaryExecutor.execute {
            val starting = runCatching {
                ProgressionRepository(applicationContext).startingLocation()
            }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (starting == null || locationManager.isLocationEnabled || renderer !== activeRenderer) {
                    startingFallbackApplied = false
                } else {
                    activeRenderer.centerOnStartingLocation(
                        starting.latitude,
                        starting.longitude,
                        force = true
                    )
                }
                startupWaitingForStartingLocation = false
                // A previous fully-rendered frame may have been the transient
                // world camera. Repaint after the new camera is chosen.
                if (findViewById<View>(R.id.mapStartupCover).visibility == View.VISIBLE) {
                    activeMap?.triggerRepaint()
                }
            }
        }
    }

    private fun showFreshCachedLocation() {
        if (Prefs.isDeviceDataDeletionPending(this) || Prefs.isTrackingPaused(this)) return
        if (!hasLocationPermission() || !::locationManager.isInitialized || !locationManager.isLocationEnabled) return
        val providers = LocationProviders.available(locationManager)
        val cached = providers.mapNotNull { provider ->
            val location = try {
                locationManager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null // Permission may have been revoked after the earlier check.
            } catch (_: IllegalArgumentException) {
                null // The provider may have disappeared.
            } ?: return@mapNotNull null
            location.takeIf {
                isFreshLocation(it) && it.hasAccuracy() && it.accuracy <= MAX_PREVIEW_ACCURACY_M
            }
        }
        val newest = cached.minByOrNull(::locationAgeMillis) ?: return
        val best = cached.filter { locationAgeMillis(it) <= locationAgeMillis(newest) + 2_000L }
            .minByOrNull { it.accuracy } ?: return
        lastPreviewLocation = Location(best)

        if (best.hasBearing() && (!best.hasSpeed() || best.speed >= 0.5f)) {
            previewBearingDegrees = best.bearing.toDouble()
        }
        renderer?.updateCar(best.latitude, best.longitude, previewBearingDegrees, locationAgeMillis(best),
            LocationProviders.measuredSpeedOrNull(best) ?: Float.NaN, best.accuracy)
    }

    private fun locationAgeMillis(location: Location): Long {
        val fixElapsedNanos = location.elapsedRealtimeNanos
        if (fixElapsedNanos > 0L) {
            return ((SystemClock.elapsedRealtimeNanos() - fixElapsedNanos) / 1_000_000L)
                .coerceAtLeast(0L)
        }
        return (System.currentTimeMillis() - location.time).coerceAtLeast(0L)
    }

    private fun startPreviewLocation() {
        if (Prefs.isDeviceDataDeletionPending(this) || Prefs.isTrackingPaused(this) || !resumed ||
            !hasLocationPermission() || TrackingService.isRunning || !locationManager.isLocationEnabled
        ) return
        stopPreviewLocation()
        baselinePreviewCapturedForRegistration = false
        baselinePreviewRequestElapsedNanos = SystemClock.elapsedRealtimeNanos()
        baselinePreviewRequestWallMillis = System.currentTimeMillis()
        LocationProviders.registerHighAccuracy(locationManager, ::registerPreviewProvider)
    }

    private fun registerPreviewProvider(provider: String): Boolean {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            locationManager.requestLocationUpdates(
                provider,
                previewLocationRequest,
                mainExecutor,
                previewLocationListener
            )
            previewLocationRegistered = true
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun isFreshLocation(location: Location): Boolean {
        if (!location.latitude.isFinite() || !location.longitude.isFinite() ||
            location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0 ||
            !location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy < 0f
        ) return false
        val fixElapsedNanos = location.elapsedRealtimeNanos
        if (fixElapsedNanos > 0L) {
            val ageNanos = SystemClock.elapsedRealtimeNanos() - fixElapsedNanos
            return ageNanos in 0..MAX_PREVIEW_LOCATION_AGE_NANOS
        }
        val fixTime = location.time
        if (fixTime <= 0L) return false
        val ageMillis = System.currentTimeMillis() - fixTime
        return ageMillis in 0..MAX_PREVIEW_LOCATION_AGE_MS
    }

    private fun stopPreviewLocation() {
        if (previewLocationRegistered && ::locationManager.isInitialized) {
            runCatching { locationManager.removeUpdates(previewLocationListener) }
            previewLocationRegistered = false
        }
        lastPreviewLocation = null
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasApproximateLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        /**
         * The high-frequency tracking broadcasts are consumed only by MainActivity's dynamic
         * receiver. Tracking continues identically when this is false; it only lets the service
         * avoid allocating and dispatching broadcasts that have no receiver while the map is
         * stopped/backgrounded.
         */
        @Volatile internal var trackingReceiverRegistered = false

        private const val REQUEST_BASIC_PERMISSIONS = 100
        private const val STATE_START_AFTER_PERMISSION = "start_after_permission"
        private const val STATE_MAP_CENTERED = "map_centered"
        const val EXTRA_SHOW_TOUR = "show_map_tour"
        private const val STATE_TOUR_STEP = "map_tour_step"
        private const val STATE_TOUR_PREVIEW = "map_tour_preview"
        private const val STATE_PENDING_RESUME = "pending_tracking_resume"
        private const val PREVIEW_INTERVAL_MS = 5_000L
        private const val PREVIEW_MIN_UPDATE_INTERVAL_MS = 2_500L
        // Stationary fixes keep the car and nearby clearing fresh without recording a drive.
        private const val PREVIEW_MIN_DISTANCE_M = 0f
        private const val MAX_PREVIEW_ACCURACY_M = 100f
        private const val MAX_BASELINE_ACCURACY_M = 25f
        private const val MAX_PREVIEW_LOCATION_AGE_MS = 60_000L
        private const val MAX_PREVIEW_LOCATION_AGE_NANOS = MAX_PREVIEW_LOCATION_AGE_MS * 1_000_000L
    }
}
