package com.roadconquest.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.app.AlertDialog
import android.widget.Toast
import com.roadconquest.app.billing.VipBilling
import com.roadconquest.app.billing.VipEntitlement
import com.roadconquest.app.data.SavedTripInsights
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.roadconquest.app.data.SavedTrip
import com.roadconquest.app.data.SavedTripPoint
import com.roadconquest.app.data.TripHistoryRepository
import com.roadconquest.app.data.TripReplayRoute
import com.roadconquest.app.util.Appearance
import com.roadconquest.app.util.ForegroundSession
import com.roadconquest.app.util.Prefs
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Replays recorded driving history; never changes tracking, matching or stored locations. */
class TripHistoryActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var mapReady = false
    private var seek: SeekBar? = null
    private var playButton: Button? = null
    private var routeStatus: TextView? = null
    private var replayProgress: TextView? = null
    private var selectedTripButton: Button? = null
    private var selectedTrip: SavedTrip? = null
    private var shareSummaryReady = false
    private var insightsText: TextView? = null
    private var shareButton: Button? = null
    private var gpxButton: Button? = null
    private var tripList: LinearLayout? = null
    private var displayedVip: Boolean? = null
    private var tripListGeneration = 0
    private var shownHistoryGeneration = -1L
    private var pendingGpxTrip: SavedTrip? = null
    private var redactGpxEnds = true
    private val vipBilling by lazy {
        VipBilling(this) { _, _, _ ->
            runOnUiThread { if (!isDestroyed) refreshVipControls() }
        }
    }
    private var points: List<SavedTripPoint> = emptyList()
    private var routeCoordinates: List<Point> = emptyList()
    private var playing = false
    @Volatile private var generation = 0
    private var lastRenderedIndex = -1
    private val tick = object : Runnable {
        override fun run() {
            if (!playing || isDestroyed) return
            val slider = seek ?: return
            if (slider.progress >= slider.max) {
                pauseReplay()
                return
            }
            slider.progress = (slider.progress + 5).coerceAtMost(slider.max)
            handler.postDelayed(this, 100L)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Appearance.wrap(newBase))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Appearance.themeRes(this))
        super.onCreate(savedInstanceState)
        savedInstanceState?.takeIf { it.containsKey("pendingGpxFirstId") }?.let { state ->
            pendingGpxTrip = SavedTrip(
                firstId = state.getLong("pendingGpxFirstId"),
                lastId = state.getLong("pendingGpxLastId"),
                startedAt = state.getLong("pendingGpxStartedAt"),
                endedAt = state.getLong("pendingGpxEndedAt"),
                pointCount = state.getInt("pendingGpxPointCount"),
                distanceMeters = state.getDouble("pendingGpxDistance"),
                historyGeneration = state.getLong("pendingGpxGeneration")
            )
            redactGpxEnds = state.getBoolean("pendingGpxHideEnds", true)
        }
        WindowCompat.enableEdgeToEdge(window)
        // System bars sit on this screen's themed background, not the dark map fog.
        val lightSystemBars = !Appearance.isDark(this)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightSystemBars
            isAppearanceLightNavigationBars = lightSystemBars
        }
        MapLibre.getInstance(this)
        // On short screens, a fixed map plus player could leave the trip list
        // with zero space. Allow the whole screen to scroll in that case.
        val compact = resources.displayMetrics.heightPixels / resources.displayMetrics.density < 600f ||
            resources.configuration.fontScale > 1.25f
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(12))
        }
        if (compact) {
            setContentView(ScrollView(this).apply {
                isFillViewport = true
                addView(root, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
        } else {
            setContentView(root)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(dp(18) + safe.left, dp(20) + safe.top, dp(18) + safe.right, dp(12) + safe.bottom)
            insets
        }
        root.addView(Button(this, null, 0, R.style.RoadConquest_BackAction).apply {
            text = "← Back"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(110), dp(48)).apply { bottomMargin = dp(8) })
        root.addView(TextView(this).apply {
            text = "YOUR JOURNEYS"
            textSize = 12f
            letterSpacing = 0.14f
            setTextColor(Appearance.color(this@TripHistoryActivity, R.attr.rcAccent))
        })
        root.addView(TextView(this).apply {
            text = "Trip history"
            isAccessibilityHeading = true
            textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Appearance.color(this@TripHistoryActivity, R.attr.rcTextPrimary))
            setPadding(0, dp(8), 0, dp(8))
        })
        val status = TextView(this).also {
            routeStatus = it
            it.text = "Choose a drive to watch your route unfold."
            it.accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            it.textSize = 14f
            it.setTextColor(Appearance.color(this@TripHistoryActivity, R.attr.rcTextSecondary))
            it.setPadding(0, 0, 0, dp(12))
            root.addView(it)
        }
        // Leave room for the trip list on short phones and landscape displays.
        val screenHeightDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val mapHeightDp = (screenHeightDp * 0.27f).toInt().coerceIn(120, 250)
        mapView = MapView(this).also { view ->
            if (compact) {
                // Map gestures take priority over the surrounding scroll view.
                view.setOnTouchListener { target, event ->
                    target.parent?.requestDisallowInterceptTouchEvent(
                        event.actionMasked != MotionEvent.ACTION_UP &&
                            event.actionMasked != MotionEvent.ACTION_CANCEL)
                    false
                }
            }
            root.addView(view, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(mapHeightDp)).apply { bottomMargin = dp(10) })
            view.onCreate(savedInstanceState)
            view.getMapAsync { ready ->
                if (isDestroyed) return@getMapAsync
                map = ready
                val json = assets.open(Prefs.mapMode(this).styleAsset)
                    .bufferedReader().use { it.readText() }
                ready.setStyle(Style.Builder().fromJson(json)) { style ->
                    if (!isDestroyed) {
                        style.addSource(GeoJsonSource(ROUTE, FeatureCollection.fromFeatures(emptyList())))
                        style.addSource(GeoJsonSource(MARKER, FeatureCollection.fromFeatures(emptyList())))
                        style.addLayer(LineLayer(ROUTE_LINE, ROUTE)
                            .withProperties(lineColor("#337BF2"), lineWidth(4f)))
                        style.addLayer(CircleLayer(ROUTE_MARKER, MARKER)
                            .withProperties(circleColor("#E33B3B"), circleRadius(8f)))
                        mapReady = true
                        lastRenderedIndex = -1
                        renderReplay()
                    }
                }
            }
        }
        seek = SeekBar(this).apply {
            max = 1000
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    showPosition(progress)
                }
                override fun onStartTrackingTouch(bar: SeekBar) { pauseReplay() }
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
            contentDescription = "Replay position"
            root.addView(this)
        }
        replayProgress = TextView(this).apply {
            text = "Choose a drive to replay"
            textSize = 13f
            setTextColor(Appearance.color(this@TripHistoryActivity, R.attr.rcTextSecondary))
            setPadding(0, 0, 0, dp(8))
            root.addView(this)
        }
        playButton = Button(this).apply {
            text = "Play replay"
            isEnabled = false
            setOnClickListener {
                if (playing) pauseReplay() else {
                    if (seek?.progress == seek?.max) seek?.progress = 0
                    playing = true
                    text = "Pause replay"
                    handler.post(tick)
                }
            }
            minHeight = dp(52)
            root.addView(this, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }
        insightsText = TextView(this).apply {
            text = "Choose a drive for its distance, new roads and matching status."
            textSize = 14f
            setTextColor(Appearance.color(this@TripHistoryActivity, R.attr.rcTextSecondary))
            setPadding(0, 0, 0, dp(12))
            root.addView(this)
        }
        root.addView(TextView(this).apply {
            text = "VIP extras: extended trip browsing, private summaries and GPX export. Basic history, replay and full progress backup remain free."
            textSize = 12f
            setTextColor(Appearance.color(this@TripHistoryActivity, R.attr.rcTextSecondary))
        })
        shareButton = Button(this, null, 0, R.style.RoadConquest_SettingsAction).apply {
            text = "Share trip summary · VIP"
            setOnClickListener { shareSelectedTrip() }
            root.addView(this)
        }
        gpxButton = Button(this, null, 0, R.style.RoadConquest_SettingsAction).apply {
            text = "Save trip as GPX · VIP"
            setOnClickListener { selectGpxPrivacy() }
            root.addView(this)
        }
        refreshVipControls()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        if (compact) {
            root.addView(column, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            root.addView(ScrollView(this).apply {
                addView(column)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        tripList = column
        refreshTripList()
    }

    private fun refreshVipControls() {
        val vip = VipEntitlement.isActive(this)
        shareButton?.text = if (vip) "Share trip summary" else "Share trip summary · VIP"
        gpxButton?.text = if (vip) "Save GPX" else "Save GPX · VIP"
        // Never share a placeholder or details from the previously selected trip.
        shareButton?.isEnabled = selectedTrip != null && shareSummaryReady
        gpxButton?.isEnabled = selectedTrip != null && shareSummaryReady
        if (displayedVip != vip && tripList != null) refreshTripList()
    }

    private fun requireVip(): Boolean {
        if (VipEntitlement.isActive(this)) return true
        AlertDialog.Builder(this)
            .setTitle("VIP trip extras")
            .setMessage("Unlock enhanced trip browsing, trip summaries for sharing and GPX export with the existing lifetime VIP purchase. Standard trip history, animated replays and your progress backups remain free.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Open Garage") { _, _ ->
                startActivity(Intent(this, ShopActivity::class.java))
            }.show()
        return false
    }

    private fun refreshTripList() {
        val column = tripList ?: return
        shownHistoryGeneration = TripHistoryRepository(applicationContext).currentHistoryGeneration()
        val vip = VipEntitlement.isActive(this)
        displayedVip = vip
        val listGeneration = ++tripListGeneration
        column.removeAllViews()
        column.addView(TextView(this).apply {
            text = if (vip) "Showing up to 500 recent drives (VIP)" else "Showing up to 100 recent drives · VIP unlocks extended history"
            textSize = 13f
            setTextColor(Appearance.color(this@TripHistoryActivity, R.attr.rcTextSecondary))
        })
        val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        worker.execute {
            val result = runCatching { TripHistoryRepository(applicationContext).recentTrips(if (vip) 500 else 100) }
            runOnUiThread {
                if (isDestroyed || displayedVip != vip ||
                    listGeneration != tripListGeneration) return@runOnUiThread
                result.fold({ trips ->
                    if (trips.isEmpty()) {
                        column.addView(TextView(this@TripHistoryActivity).apply {
                            text = "No saved drives yet. Your recorded GPS history will appear here."
                        })
                    }
                    // Inflate only visible-sized batches. 500 Buttons at once
                    // caused unnecessary main-thread work and view memory on VIP.
                    fun appendBatch(start: Int) {
                        val end = minOf(start + 40, trips.size)
                        trips.subList(start, end).forEach { trip ->
                        val miles = trip.distanceMeters / 1609.344
                        column.addView(Button(this@TripHistoryActivity, null, 0,
                            R.style.RoadConquest_SettingsAction).apply {
                            val duration = DateUtils.formatElapsedTime(
                                ((trip.endedAt - trip.startedAt).coerceAtLeast(0L)) / 1000
                            )
                            text = String.format(Locale.getDefault(), "%s\n%.1f mi  •  %s",
                                formatter.format(Date(trip.startedAt)), miles, duration)
                            minHeight = dp(66)
                            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                            setPadding(dp(16), dp(8), dp(16), dp(8))
                            setOnClickListener {
                                selectedTripButton?.apply {
                                    isSelected = false
                                    contentDescription = null
                                    setBackgroundResource(R.drawable.settings_action_background)
                                    setTextColor(Appearance.color(this@TripHistoryActivity, R.attr.rcTextPrimary))
                                }
                                selectedTripButton = this
                                isSelected = true
                                contentDescription = "$text, selected trip"
                                setBackgroundResource(R.drawable.selected_filter_background)
                                setTextColor(Appearance.color(this@TripHistoryActivity, R.attr.rcOnAccent))
                                loadTrip(trip)
                            }
                        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                        }
                        if (end < trips.size) {
                            column.addView(Button(this@TripHistoryActivity, null, 0,
                                R.style.RoadConquest_SettingsAction).apply {
                                text = "Show more drives (${trips.size - end} remaining)"
                                minHeight = dp(52)
                                setOnClickListener {
                                    column.removeView(this)
                                    appendBatch(end)
                                }
                            }, LinearLayout.LayoutParams(-1, -2))
                        }
                    }
                    if (trips.isNotEmpty()) appendBatch(0)
                }, {
                    column.addView(TextView(this@TripHistoryActivity).apply {
                        text = "Trip history is temporarily unavailable."
                    })
                })
            }
        }
    }

    private fun isTripSafeToShare(trip: SavedTrip): Boolean {
        if (TripHistoryRepository(applicationContext).isCurrentTrip(trip)) return true
        selectedTrip = null
        shareSummaryReady = false
        refreshVipControls()
        insightsText?.text = "Driving history changed. Reopen Trips to select a current drive."
        return false
    }

    private fun shareSelectedTrip() {
        val trip = selectedTrip ?: return
        if (!requireVip() || !isTripSafeToShare(trip)) return
        val details = insightsText?.text?.toString().orEmpty()
        val message = String.format(Locale.getDefault(),
            "Road Conquest drive • %s\n%.1f miles • %d minutes\n%s\nShared without exact GPS coordinates.",
            DateFormat.getDateInstance().format(Date(trip.startedAt)),
            trip.distanceMeters / 1609.344,
            (trip.endedAt - trip.startedAt).coerceAtLeast(0L) / 60_000,
            details
        )
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }, "Share your Road Conquest trip"))
    }

    private fun selectGpxPrivacy() {
        val trip = selectedTrip ?: return
        if (!requireVip() || !isTripSafeToShare(trip)) return
        AlertDialog.Builder(this)
            .setTitle("Save private GPX route")
            .setMessage("GPX files contain precise GPS locations. Omitting the first and last two minutes can reduce exposure of your start and destination, but does not guarantee privacy. Review the route before sharing; Full route includes exact endpoints.")
            .setItems(arrayOf("Hide first and last 2 minutes (recommended)", "Export full route, including exact endpoints")) { _, choice ->
                pendingGpxTrip = trip
                redactGpxEnds = choice == 0
                val name = "roadconquest-trip-${trip.startedAt}.gpx"
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/gpx+xml"
                    putExtra(Intent.EXTRA_TITLE, name)
                }, GPX_DOCUMENT_REQUEST)
            }
            .setNegativeButton("Cancel", null).show()
    }

    @Deprecated("Uses Android document picker for direct user-controlled GPX files")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != GPX_DOCUMENT_REQUEST) return
        val trip = pendingGpxTrip
        pendingGpxTrip = null
        val uri = data?.data ?: return
        if (resultCode != Activity.RESULT_OK || trip == null || !VipEntitlement.isActive(this)) return
        val hideEnds = redactGpxEnds
        worker.execute {
            val result = runCatching {
                val stream = contentResolver.openOutputStream(uri, "w")
                    ?: error("Could not open selected file")
                val pointCount = stream.use {
                    TripHistoryRepository(applicationContext).exportGpx(trip, it, hideEnds)
                }
                if (pointCount == 0) {
                    // A short drive has no privacy-safe coordinates to export.
                    // Don't leave an empty document in the user's selected folder.
                    runCatching { contentResolver.delete(uri, null, null) }
                }
                pointCount
            }.onFailure {
                // A cloud document provider may be slow: delete incomplete files
                // on the worker rather than blocking the Android UI thread.
                runCatching { contentResolver.delete(uri, null, null) }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                Toast.makeText(this, result.fold(
                    { if (it == 0) "No coordinates remain after hiding endpoints. Consider a different trip." else "GPX saved ($it GPS points)" },
                    { "Could not save GPX. Choose another file or reload the trip." }
                ), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadTrip(trip: SavedTrip) {
        pauseReplay()
        selectedTrip = trip
        shareSummaryReady = false
        refreshVipControls()
        insightsText?.text = "Checking new roads, towns and matching status…"
        val expected = ++generation
        // Clear the previous drive immediately; never show its track or marker
        // while the next drive is loading or if loading fails.
        points = emptyList()
        routeCoordinates = emptyList()
        lastRenderedIndex = -1
        seek?.isEnabled = false
        playButton?.isEnabled = false
        seek?.progress = 0
        renderReplay()
        routeStatus?.text = "Loading drive…"
        replayProgress?.text = "Loading drive…"
        worker.execute {
            // Drop queued requests superseded by a newer selection before
            // touching the database, not merely after completing the work.
            if (expected != generation) return@execute
            val result = runCatching {
                val history = TripHistoryRepository(applicationContext)
                history.replay(trip) to history.insights(trip)
            }
            runOnUiThread {
                if (isDestroyed || expected != generation) return@runOnUiThread
                result.fold({ (loaded, insights) ->
                    insightsText?.text = String.format(Locale.getDefault(),
                        "New roads: %,d (estimated) • New towns: %,d • New tiles: %,d\n%,d GPS fixes awaiting road matching. Older tiles without saved dates are excluded.",
                        insights.newRoadGroups, insights.newTowns, insights.newTiles, insights.pendingFixes
                    )
                    shareSummaryReady = loaded.size >= 2
                    refreshVipControls()
                    points = loaded
                    // Cache MapLibre geometries once instead of reconstructing
                    // every previously travelled point on every animation tick.
                    routeCoordinates = loaded.map { Point.fromLngLat(it.longitude, it.latitude) }
                    lastRenderedIndex = -1
                    seek?.isEnabled = loaded.size >= 2
                    playButton?.isEnabled = loaded.size >= 2
                    seek?.progress = 0
                    routeStatus?.text = if (loaded.size < 2) "Drive unavailable."
                        else String.format(Locale.getDefault(),
                            "%.1f mi • %d min • %d recorded fixes",
                            trip.distanceMeters / 1609.344,
                            (trip.endedAt - trip.startedAt).coerceAtLeast(0L) / 60_000,
                            trip.pointCount)
                    renderReplay()
                    if (loaded.size < 2) replayProgress?.text = "Drive unavailable"
                    else if (!mapReady) replayProgress?.text = "Preparing replay map…"
                }, {
                    routeStatus?.text = "Could not load the selected drive."
                    shareSummaryReady = false
                    refreshVipControls()
                    insightsText?.text = "Trip details unavailable."
                    replayProgress?.text = "Replay unavailable"
                })
            }
        }
    }

    private fun renderReplay() {
        if (!mapReady) return
        if (points.size < 2) {
            lastRenderedIndex = -1
            if (points.isEmpty()) replayProgress?.text = "Choose a drive to replay"
            map?.style?.getSourceAs<GeoJsonSource>(ROUTE)?.setGeoJson(
                FeatureCollection.fromFeatures(emptyList())
            )
            map?.style?.getSourceAs<GeoJsonSource>(MARKER)?.setGeoJson(
                FeatureCollection.fromFeatures(emptyList())
            )
            return
        }
        // No route is exposed on selection: the replay draws only the traversed prefix.
        showPosition(seek?.progress ?: 0)
        val bounds = LatLngBounds.Builder()
        points.forEach { bounds.include(LatLng(it.latitude, it.longitude)) }
        val expectedGeneration = generation
        val tripBounds = bounds.build()
        mapView?.post {
            if (!isDestroyed && mapReady && generation == expectedGeneration) {
                map?.moveCamera(CameraUpdateFactory.newLatLngBounds(tripBounds, dp(26)))
            }
        }
    }

    private fun showPosition(position: Int) {
        if (!mapReady || points.isEmpty()) return
        val index = ((points.size - 1) * position / 1000.0).toInt()
            .coerceIn(0, points.lastIndex)
        // A slider tick may refer to the same sampled GPS fix. Skip needless
        // MapLibre source updates; the visible route has not changed.
        if (index == lastRenderedIndex) return
        val point = points[index]
        val style = map?.style ?: return
        lastRenderedIndex = index
        style.getSourceAs<GeoJsonSource>(MARKER)?.setGeoJson(
            Feature.fromGeometry(routeCoordinates[index])
        )
        val firstTimestamp = points.first().timestamp
        val totalSeconds = ((points.last().timestamp - firstTimestamp).coerceAtLeast(0L)) / 1000L
        val elapsedSeconds = ((point.timestamp - firstTimestamp).coerceIn(0L, totalSeconds * 1000L)) / 1000L
        replayProgress?.text = "${DateUtils.formatElapsedTime(elapsedSeconds)} / ${DateUtils.formatElapsedTime(totalSeconds)}"
        val visible = TripReplayRoute.visibleRanges(points, index).map { range ->
            routeCoordinates.subList(range.first, range.last + 1)
        }
        val source = style.getSourceAs<GeoJsonSource>(ROUTE) ?: return
        if (visible.isEmpty()) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        } else {
            source.setGeoJson(Feature.fromGeometry(MultiLineString.fromLngLats(visible)))
        }
    }

    private fun pauseReplay() {
        playing = false
        handler.removeCallbacks(tick)
        playButton?.text = "Play replay"
    }

    override fun onStart() { super.onStart(); ForegroundSession.app.onStart(); mapView?.onStart() }
    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        val currentGeneration = TripHistoryRepository(applicationContext).currentHistoryGeneration()
        if (shownHistoryGeneration != -1L && shownHistoryGeneration != currentGeneration) {
            // A restore or Delete all data occurred while this screen was paused.
            // Do not keep a deleted GPS route on screen or allow it to be replayed.
            generation++
            pauseReplay()
            selectedTrip = null
            selectedTripButton = null
            shareSummaryReady = false
            points = emptyList()
            routeCoordinates = emptyList()
            lastRenderedIndex = -1
            seek?.isEnabled = false
            playButton?.isEnabled = false
            seek?.progress = 0
            renderReplay()
            routeStatus?.text = "Driving history changed. Choose a saved drive."
            replayProgress?.text = "Choose a drive to replay"
            insightsText?.text = "Choose a drive for its distance, new roads and matching status."
            refreshTripList()
        }
        vipBilling.refresh(includeOffer = false)
        refreshVipControls()
    }
    override fun onPause() { pauseReplay(); mapView?.onPause(); super.onPause() }
    override fun onStop() { ForegroundSession.app.onStop(isChangingConfigurations); mapView?.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onSaveInstanceState(out: Bundle) {
        pendingGpxTrip?.let { trip ->
            out.putLong("pendingGpxFirstId", trip.firstId)
            out.putLong("pendingGpxLastId", trip.lastId)
            out.putLong("pendingGpxStartedAt", trip.startedAt)
            out.putLong("pendingGpxEndedAt", trip.endedAt)
            out.putInt("pendingGpxPointCount", trip.pointCount)
            out.putDouble("pendingGpxDistance", trip.distanceMeters)
            out.putLong("pendingGpxGeneration", trip.historyGeneration)
            out.putBoolean("pendingGpxHideEnds", redactGpxEnds)
        }
        super.onSaveInstanceState(out)
        mapView?.onSaveInstanceState(out)
    }
    override fun onDestroy() {
        generation++
        pauseReplay()
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        vipBilling.close()
        mapView?.onDestroy()
        super.onDestroy()
    }

    private companion object {
        const val GPX_DOCUMENT_REQUEST = 7124
        const val ROUTE = "trip-replay-source"
        const val MARKER = "trip-replay-position"
        const val ROUTE_LINE = "trip-replay-line"
        const val ROUTE_MARKER = "trip-replay-circle"
    }
}
