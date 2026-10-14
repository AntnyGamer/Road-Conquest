package com.roadconquest.app

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
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
    private var points: List<SavedTripPoint> = emptyList()
    private var playing = false
    private var generation = 0
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
                fillViewport = true
                addView(root, ScrollView.LayoutParams(
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
        root.addView(TextView(this).apply {
            text = "YOUR JOURNEYS"
            textSize = 12f
            letterSpacing = 0.14f
            setTextColor(Appearance.color(this@TripHistoryActivity, R.attr.rcAccent))
        })
        root.addView(TextView(this).apply {
            text = "Trip history"
            textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Appearance.color(this@TripHistoryActivity, R.attr.rcTextPrimary))
            setPadding(0, dp(8), 0, dp(8))
        })
        val status = TextView(this).also {
            routeStatus = it
            it.text = "Choose a drive to watch your route unfold."
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
        run {
            val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            worker.execute {
                val result = runCatching { TripHistoryRepository(applicationContext).recentTrips() }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    result.fold({ trips ->
                        if (trips.isEmpty()) {
                            column.addView(TextView(this@TripHistoryActivity).apply {
                                text = "No saved drives yet. Your recorded GPS history will appear here."
                            })
                        }
                        trips.forEach { trip ->
                            val miles = trip.distanceMeters / 1609.344
                            column.addView(Button(this@TripHistoryActivity, null, 0,
                                R.style.RoadConquest_SettingsAction).apply {
                                text = String.format(Locale.getDefault(), "%s  •  %.1f mi  •  %d fixes",
                                    formatter.format(Date(trip.startedAt)), miles, trip.pointCount)
                                setOnClickListener { loadTrip(trip) }
                            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                        }
                    }, {
                        status.text = "Trip history is temporarily unavailable."
                    })
                }
            }
        }
    }

    private fun loadTrip(trip: SavedTrip) {
        pauseReplay()
        val expected = ++generation
        // Clear the previous drive immediately; never show its track or marker
        // while the next drive is loading or if loading fails.
        points = emptyList()
        lastRenderedIndex = -1
        seek?.isEnabled = false
        playButton?.isEnabled = false
        seek?.progress = 0
        renderReplay()
        routeStatus?.text = "Loading drive…"
        worker.execute {
            val result = runCatching { TripHistoryRepository(applicationContext).replay(trip) }
            runOnUiThread {
                if (isDestroyed || expected != generation) return@runOnUiThread
                result.fold({ loaded ->
                    points = loaded
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
                }, { routeStatus?.text = "Could not load the selected drive." })
            }
        }
    }

    private fun renderReplay() {
        if (!mapReady) return
        if (points.size < 2) {
            lastRenderedIndex = -1
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
        mapView?.post {
            if (!isDestroyed && mapReady) {
                map?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), dp(26)))
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
            Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude))
        )
        val visible = TripReplayRoute.visibleRanges(points, index).map { range ->
            range.map { i -> Point.fromLngLat(points[i].longitude, points[i].latitude) }
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
    override fun onResume() { super.onResume(); mapView?.onResume() }
    override fun onPause() { pauseReplay(); mapView?.onPause(); super.onPause() }
    override fun onStop() { ForegroundSession.app.onStop(isChangingConfigurations); mapView?.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); mapView?.onSaveInstanceState(out) }
    override fun onDestroy() {
        generation++
        pauseReplay()
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        mapView?.onDestroy()
        super.onDestroy()
    }

    private companion object {
        const val ROUTE = "trip-replay-source"
        const val MARKER = "trip-replay-position"
        const val ROUTE_LINE = "trip-replay-line"
        const val ROUTE_MARKER = "trip-replay-circle"
    }
}
