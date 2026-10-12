package com.roadconquest.app.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import com.roadconquest.app.progression.Cosmetics
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.data.ProgressionRepository
import com.roadconquest.app.data.PlaceKind
import com.roadconquest.app.util.Prefs
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.layers.PropertyFactory.fillAntialias
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.fillOutlineColor
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.rasterFadeDuration
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.*

class MapRenderer(
    private val context: Context,
    private val map: MapLibreMap,
    private val repository: TrackingRepository,
    private val mapView: MapView,
    initiallyCentered: Boolean = false
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val fogExecutor = Executors.newSingleThreadExecutor()
    private val overlayExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var centeredOnce = initiallyCentered
    val hasCentered: Boolean get() = centeredOnce
    private var mapMode = Prefs.mapMode(context)
    private var styleGeneration = 0
    private var queryRunning = false
    private var queryAgain = false
    private var queryRoadsAgain = false
    private var viewportRevision = 0
    private var cameraMoving = false
    private var viewportActive = true
    private var resumeVisibilityCheck: Runnable? = null
    private var resumeFrameListener: MapView.OnDidFinishRenderingFrameListener? = null
    private var resumeGeneration = 0
    private var fogRunning = false
    private var fogAgain = false
    private var reusableFogBitmap: Bitmap? = null
    private var installedFogKey: FogKey? = null
    // A wider, geographically anchored mask preserves visited mile squares when a
    // fast pinch outruns the detailed bitmap. The 512px world image cannot resolve them.
    private var regionalFogRunning = false
    private var reusableRegionalFogBitmap: Bitmap? = null
    private var installedRegionalFogKey: FogKey? = null
    private var carIconBitmap: Bitmap? = null
    private var appliedCarStyle = ""
    private var appliedCarColor = ""
    private var appliedRoadColor = ""
    private var fogEnabled = Prefs.isFogEnabled(context)
    private var overlayMode = Prefs.placeOverlayMode(context)
    private var shownOverlayKeys = emptyList<String>()
    private var shownOverlayCacheGeneration = -1L
    private val overlayClient = PlaceOverlayClient()
    @Volatile private var overlayGeneration = 0
    @Volatile private var destroyed = false
    private val liveLocation = LiveLocation()
    private var displayedRoads = OverlayRoads.EMPTY
    private var displayedRoadFeatures = EMPTY_FEATURES
    // A newly installed GeoJSON source already contains EMPTY_FEATURES. Avoid sending the
    // same empty data over the native bridge on every viewport/GPS refresh.
    private var pendingRouteSourceIsEmpty = true
    private var displayedPlaces = doubleArrayOf()
    private var savedGridCoordinates = doubleArrayOf()
    private var loadedRoadBounds: RoadQueryBounds? = null
    private var loadedPlaceBounds: RoadQueryBounds? = null
    private var roadDataRevision = 0
    private var placeDataRevision = 0
    private var roadProjectionDataRevision = -1
    private var roadProjectionViewportRevision = -1
    private var placeProjectionDataRevision = -1
    private var placeProjectionViewportRevision = -1
    private var roadProjectionWidth = -1
    private var roadProjectionHeight = -1
    private var placeProjectionWidth = -1
    private var placeProjectionHeight = -1
    private var roadScreenCache = doubleArrayOf()
    private var placeScreenCache = doubleArrayOf()
    private val liveCoordinates = DoubleArray(2)
    private val liveScreenCache = DoubleArray(2)
    private var detailedFogCoordinates: DoubleArray? = null
    private var regionalFogCoordinates: DoubleArray? = null
    private val fogCoverageScreen = DoubleArray(8)
    private var showingDetailedFog = false
    private var showingRegionalFog = false
    private var activeDetailedFogAlternate = false
    private var lastFogRenderAt = 0L
    private var minimumZoom = Double.NaN
    private val renderFog = Runnable { scheduleFogRender() }
    private val expirePendingRoutes = Runnable { if (!destroyed) refreshTracking() }
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (!viewportActive) return@OnLayoutChangeListener
        val cameraPosition = map.cameraPosition
        updateCameraLimits(cameraPosition)
        updateFogCoverage(cameraPosition = cameraPosition)
        scheduleFogRender()
    }

    private val expireLocation = Runnable {
        if (!destroyed && liveLocation.current(SystemClock.elapsedRealtime()) == null) clearCurrentLocation()
    }
    private val cameraMoveStartedListener = MapLibreMap.OnCameraMoveStartedListener {
        if (!viewportActive) return@OnCameraMoveStartedListener
        // Native animations can advance before the next Java camera-move callback.
        // Cover the whole world before motion starts, not after a bitmap edge escapes.
        cameraMoving = true
        updateFogCoverage()
    }
    private val cameraMoveListener = MapLibreMap.OnCameraMoveListener {
        if (!viewportActive) return@OnCameraMoveListener
        cameraMoving = true
        viewportRevision++
        // CameraPosition crosses the MapLibre/native boundary. Snapshot it once per move callback
        // and share that exact frame between the limit and coverage calculations.
        val cameraPosition = map.cameraPosition
        updateCameraLimits(cameraPosition)
        updateFogCoverage(cameraPosition = cameraPosition)
        if (cameraPosition.zoom >= FogBitmapRenderer.MIN_FOG_REVEAL_ZOOM) {
            scheduleFogRender()
        }
    }
    private val cameraIdleListener = MapLibreMap.OnCameraIdleListener {
        if (!viewportActive) return@OnCameraIdleListener
        cameraMoving = false
        val cameraPosition = map.cameraPosition
        updateFogCoverage(cameraPosition = cameraPosition)
        refreshViewport()
        scheduleFogRender()
    }

    fun initialize(onReady: () -> Unit) {
        map.setMaxZoomPreference(FogBitmapRenderer.MAX_ZOOM)
        map.setMaxPitchPreference(0.0)
        map.uiSettings.setTiltGesturesEnabled(false)
        mapView.addOnLayoutChangeListener(layoutListener)
        updateCameraLimits()
        map.addOnCameraMoveStartedListener(cameraMoveStartedListener)
        map.addOnCameraMoveListener(cameraMoveListener)
        map.addOnCameraIdleListener(cameraIdleListener)
        loadStyle(onReady)
    }

    fun setMapMode(mode: MapMode) {
        if (destroyed || mode == mapMode) return
        mapMode = mode
        loadStyle { }
    }

    fun setFogEnabled(enabled: Boolean) {
        if (destroyed) return
        fogEnabled = enabled
        val cameraPosition = map.cameraPosition
        updateFogCoverage(force = true, cameraPosition = cameraPosition)
        if (enabled) scheduleFogRender()
    }

    fun placeOverlayMode(): PlaceOverlayMode = overlayMode

    fun setPlaceOverlayMode(mode: PlaceOverlayMode) {
        if (destroyed || mode == overlayMode) return
        overlayMode = mode
        Prefs.setPlaceOverlayMode(context, mode)
        clearPlaceOverlaySources()
        refreshPlaceOverlays()
    }

    fun refreshPlaceOverlays() {
        if (destroyed) return
        val generation = ++overlayGeneration
        val kind = overlayMode.kind ?: return
        val cacheGeneration = PlaceOverlayCache.generation()
        overlayExecutor.execute {
            val places = runCatching { ProgressionRepository(context).visitedPlaces(kind) }
                .getOrElse {
                    Log.e("RoadConquest", "Could not load discovered places for overlay", it)
                    return@execute
                }
            if (destroyed || generation != overlayGeneration ||
                cacheGeneration != PlaceOverlayCache.generation()) return@execute
            val loaded = ArrayList<PlaceOverlayData>(places.size)
            val missing = ArrayList<com.roadconquest.app.data.PlaceDiscovery>()
            for (place in places) {
                val cached = if (UnitedStatesMunicipalBoundaries.applies(place)) {
                    PlaceOverlayCache.readOfficialUs(context, place)
                } else PlaceOverlayCache.read(context, place)
                if (!cached.cached) missing += place
                cached.data?.let(loaded::add)
            }
            postPlaceOverlay(generation, kind, loaded)
            for (place in missing) {
                if (destroyed || generation != overlayGeneration ||
                    cacheGeneration != PlaceOverlayCache.generation()) return@execute
                // Every US state uses the SAME Census municipal source.
                // Do not fall back to Nominatim for U.S. municipalities:
                // mixing boundary datasets causes overlaps and artificial gaps.
                val us = UnitedStatesMunicipalBoundaries.applies(place)
                val result = runCatching {
                    if (us) UnitedStatesMunicipalBoundaries.fetch(place)
                    else overlayClient.fetch(place)
                }
                if (result.isFailure) {
                    Log.w("RoadConquest", "Could not load place boundary", result.exceptionOrNull())
                    continue
                }
                if (cacheGeneration != PlaceOverlayCache.generation()) return@execute
                val data = result.getOrNull()
                val written = if (us) {
                    PlaceOverlayCache.writeOfficialUs(context, place, data, cacheGeneration)
                } else PlaceOverlayCache.write(context, place, data, cacheGeneration)
                if (!written) return@execute
                // Another discovery worker may have cached a verified polygon
                // while our lookup was in flight. If our older lookup returned
                // no boundary, render that preserved positive cache right now.
                val resolved = data ?: if (us) {
                    PlaceOverlayCache.readOfficialUs(context, place).data
                } else PlaceOverlayCache.read(context, place).data
                if (resolved != null) {
                    loaded += resolved
                    // Show completed boundaries immediately without waiting for
                    // a future viewport or discovery refresh.
                    postPlaceOverlay(generation, kind, loaded)
                }
            }
        }
    }

    fun cancelPlaceOverlayLoads() {
        overlayGeneration++
    }

    fun overlayInfoAt(screenPoint: PointF): PlaceOverlayInfo? {
        val mode = overlayMode
        val kind = mode.kind ?: return null
        val layerId = overlayLayerId(mode)
        val feature = map.queryRenderedFeatures(screenPoint, layerId).firstOrNull() ?: return null
        val name = feature.getStringProperty("overlay_name") ?: return null
        val key = feature.getStringProperty("overlay_key") ?: return null
        val featureKind = runCatching {
            PlaceKind.valueOf(feature.getStringProperty("overlay_kind") ?: kind.name)
        }.getOrDefault(kind)
        val population = feature.getNumberProperty("population")?.toLong()
        val area = feature.getNumberProperty("area_sq_km")?.toDouble() ?: return null
        return PlaceOverlayInfo(key, name, featureKind, population, area)
    }

    fun refreshCosmetics() {
        if (destroyed) return
        val style = map.style ?: return
        val road = Prefs.roadColor(context)
        if (road != appliedRoadColor) {
            val color = Cosmetics.roadColor(context).argb
            (style.getLayer(ROAD_LAYER_ID) as? LineLayer)?.setProperties(lineColor(color))
            (style.getLayer(PENDING_ROUTE_LAYER_ID) as? LineLayer)?.setProperties(lineColor(color))
            appliedRoadColor = road
        }
        val carStyle = Prefs.carStyle(context)
        val carColor = Prefs.carColor(context)
        if (carStyle != appliedCarStyle || carColor != appliedCarColor) {
            updateCarImage(style)
            appliedCarStyle = carStyle
            appliedCarColor = carColor
        }
    }

    private fun loadStyle(onReady: () -> Unit) {
        val generation = ++styleGeneration
        val styleJson = context.assets.open(mapMode.styleAsset).bufferedReader().use { it.readText() }
        map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
            if (destroyed || generation != styleGeneration) return@setStyle
            installPlaceOverlayLayers(style)
            installFogLayer(style)
            installRecordedRouteLayer(style)
            installRoadLayer(style)
            installCarLayer(style)
            updateCarLayer()
            refreshViewport()
            scheduleFogRender()
            onReady()
        }
    }

    fun updateCar(latitude: Double, longitude: Double, bearing: Double, ageMillis: Long = 0L) {
        if (destroyed || !viewportActive) return
        val now = SystemClock.elapsedRealtime()
        val previousVisual = liveLocation.current(now)
        if (!liveLocation.update(latitude, longitude, bearing, now, ageMillis)) return
        mainHandler.removeCallbacks(expireLocation)
        mainHandler.postDelayed(expireLocation, LiveLocation.MAX_AGE_MS - ageMillis)

        // Equal live fixes can still be newer and must extend expiry, but they do not need
        // another MapLibre source upload or fog bitmap render. Only skip work when every
        // user-visible value is exactly unchanged.
        val currentVisual = liveLocation.current(now)
        val positionChanged = previousVisual == null || currentVisual == null ||
            previousVisual.latitude != currentVisual.latitude ||
            previousVisual.longitude != currentVisual.longitude
        val bearingChanged = previousVisual == null || currentVisual == null ||
            previousVisual.bearing != currentVisual.bearing
        if (positionChanged) {
            updateCarLayer()
            scheduleFogRender()
        } else if (bearingChanged) {
            // Heading-only updates still rotate the marker, but do not re-upload identical
            // GeoJSON or rebuild fog because neither depends on bearing.
            currentVisual?.let { fix ->
                (map.style?.getLayer(CAR_LAYER_ID) as? SymbolLayer)
                    ?.setProperties(iconRotate(fix.bearing.toFloat()))
            }
        }
        if (!centeredOnce) {
            centeredOnce = true
            centerOnCar()
        }
    }

    fun centerOnCar(): Boolean {
        if (destroyed) return false
        val fix = liveLocation.current(SystemClock.elapsedRealtime()) ?: return false
        centeredOnce = true
        val currentCamera = map.cameraPosition
        val camera = CameraPosition.Builder(currentCamera)
            .target(LatLng(fix.latitude, fix.longitude))
            .zoom(currentCamera.zoom.coerceIn(FogBitmapRenderer.CENTER_ZOOM, FogBitmapRenderer.MAX_ZOOM))
            .tilt(0.0)
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(camera))
        return true
    }

    fun centerOnStartingLocation(latitude: Double, longitude: Double, force: Boolean = false): Boolean {
        if (destroyed || (!force && centeredOnce) || !latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -85.05112878..85.05112878 || longitude !in -180.0..180.0
        ) return false
        centeredOnce = true
        val camera = CameraPosition.Builder(map.cameraPosition)
            .target(LatLng(latitude, longitude))
            .zoom(STARTING_LOCATION_ZOOM)
            .tilt(0.0)
            .build()
        map.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
        return true
    }

    fun clearCurrentLocation() {
        if (destroyed) return
        mainHandler.removeCallbacks(expireLocation)
        // refreshControls can ask for a clear repeatedly while Location stays unavailable.
        // Once the fix is already gone, another MapLibre upload and fog render cannot change
        // anything visible, so skip that work entirely.
        if (!liveLocation.clear()) return
        updateCarLayer()
        scheduleFogRender()
    }

    fun refreshViewport() = refreshViewport(loadRoads = true)

    fun refreshRoads() {
        loadedRoadBounds = null
        refreshViewport(loadRoads = true)
    }

    fun pauseViewport() {
        // These timers only serve the visible map. Keep persistence/tracking independent.
        viewportActive = false
        resumeGeneration++
        viewportRevision++
        resumeVisibilityCheck?.let(mainHandler::removeCallbacks)
        resumeVisibilityCheck = null
        resumeFrameListener?.let(mapView::removeOnDidFinishRenderingFrameListener)
        resumeFrameListener = null
        mainHandler.removeCallbacks(renderFog)
        mainHandler.removeCallbacks(expirePendingRoutes)
        mainHandler.removeCallbacks(expireLocation)
        fogAgain = false
        queryAgain = false
        queryRoadsAgain = false
    }

    fun resumeViewport(reloadRoads: Boolean) {
        if (destroyed) return
        if (!viewportActive) {
            viewportActive = true
            updateCarLayer()
            val now = SystemClock.elapsedRealtime()
            liveLocation.current(now)?.let { mainHandler.postDelayed(expireLocation, it.expiresAt - now) }
        }
        if (reloadRoads) loadedRoadBounds = null
        loadedPlaceBounds = null
        // onResume runs just before the window becomes visible. Arming the frame callback there
        // can lose the forced repaint, leaving MapLibre's transient overview camera in use.
        cameraMoving = false
        viewportRevision++
        val generation = ++resumeGeneration
        resumeVisibilityCheck?.let(mainHandler::removeCallbacks)
        resumeFrameListener?.let(mapView::removeOnDidFinishRenderingFrameListener)
        resumeFrameListener = null
        lateinit var awaitVisible: Runnable
        awaitVisible = Runnable {
            if (destroyed || !viewportActive || generation != resumeGeneration) return@Runnable
            if (!mapView.isShown || mapView.width <= 0 || mapView.height <= 0) {
                mainHandler.postDelayed(awaitVisible, RESUME_VISIBILITY_RETRY_MS)
                return@Runnable
            }
            resumeVisibilityCheck = null
            lateinit var listener: MapView.OnDidFinishRenderingFrameListener
            listener = MapView.OnDidFinishRenderingFrameListener { _, _, _ ->
                mapView.removeOnDidFinishRenderingFrameListener(listener)
                if (resumeFrameListener === listener) resumeFrameListener = null
                if (!destroyed && generation == resumeGeneration) {
                    cameraMoving = false
                    viewportRevision++
                    updateCameraLimits()
                    updateFogCoverage(force = true)
                    refreshViewport(loadRoads = true)
                }
            }
            resumeFrameListener = listener
            mapView.addOnDidFinishRenderingFrameListener(listener)
            map.triggerRepaint()
        }
        resumeVisibilityCheck = awaitVisible
        mainHandler.post(awaitVisible)
    }

    fun refreshExploration() {
        loadedPlaceBounds = null
        refreshViewport(loadRoads = false)
    }

    fun refreshTracking() = refreshViewport(loadRoads = false)

    private fun refreshViewport(loadRoads: Boolean) {
        if (destroyed || !viewportActive) return
        if (cameraMoving || queryRunning) {
            queryAgain = true
            queryRoadsAgain = queryRoadsAgain || loadRoads
            return
        }
        val cameraPosition = map.cameraPosition
        if (cameraPosition.zoom < FogBitmapRenderer.MIN_ROAD_ZOOM) {
            // Keep querying explored cells at every zoom. Only blue roads and
            // provisional tracks are hidden at these very broad overview scales.
            mainHandler.removeCallbacks(expirePendingRoutes)
            applyPendingRoute(EMPTY_FEATURES, hasPending = false)
            if (displayedRoads !== OverlayRoads.EMPTY) {
                setDisplayedRoads(OverlayRoads.EMPTY, EMPTY_FEATURES)
            }
            loadedRoadBounds = null
        }
        val bounds = map.projection.visibleRegion.latLngBounds
        val north = bounds.latitudeNorth
        val south = bounds.latitudeSouth
        val width = mapView.width.toFloat()
        val height = mapView.height.toFloat()
        val centerLongitude = cameraPosition.target?.longitude ?: 0.0
        val topLeft = unwrappedCorner(0f, 0f, centerLongitude).longitude
        val topRight = unwrappedCorner(width, 0f, centerLongitude).longitude
        val bottomRight = unwrappedCorner(width, height, centerLongitude).longitude
        val bottomLeft = unwrappedCorner(0f, height, centerLongitude).longitude
        val minLongitude = minOf(topLeft, topRight, bottomRight, bottomLeft)
        val maxLongitude = maxOf(topLeft, topRight, bottomRight, bottomLeft)
        fun wrappedLongitude(longitude: Double) = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val east = wrappedLongitude(maxLongitude)
        val west = wrappedLongitude(minLongitude)
        val revision = viewportRevision
        queryAgain = false
        queryRoadsAgain = false
        queryRunning = true
        val loadedRoadBoundsAtStart = loadedRoadBounds
        val loadedPlaceBoundsAtStart = loadedPlaceBounds
        executor.execute {
            val result = runCatching {
                // Match the larger native fog footprint so a normal pan continues to show
                // already-unlocked roads while the gesture is in progress.
                // Include a full grid cell beyond the existing fade padding, so a visited
                // sample outside the viewport can still clear a cell intersecting it.
                val fadePad = (FogBitmapRenderer.ROAD_FULL_M + 1_609.344) / 110_000.0
                val latPad = (north - south) * FogBitmapRenderer.VIEWPORT_PADDING_MULTIPLIER + fadePad
                val longitudeSpan = if (east >= west) east - west else east - west + 360.0
                val lonPad = longitudeSpan * FogBitmapRenderer.VIEWPORT_PADDING_MULTIPLIER +
                    fadePad / cos(Math.toRadians((north + south) / 2)).coerceAtLeast(0.01)
                fun wrap(longitude: Double) = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
                val querySpan = (longitudeSpan + 2 * lonPad).coerceAtMost(360.0)
                // At world overview scale, individual screen corners may be
                // several wrapped copies apart. Load ALL persisted grid cells so
                // the world bitmap never loses remote explored territory.
                val worldOverview = cameraPosition.zoom < FogBitmapRenderer.MIN_FOG_REVEAL_ZOOM
                val queryWest = if (worldOverview || querySpan >= 360.0) -180.0 else wrap(west - lonPad)
                val queryEast = if (worldOverview || querySpan >= 360.0) 180.0 else wrap(east + lonPad)
                val queryNorth = if (worldOverview) 90.0 else (north + latPad).coerceAtMost(90.0)
                val querySouth = if (worldOverview) -90.0 else (south - latPad).coerceAtLeast(-90.0)
                val footprint = RoadQueryBounds(
                    queryNorth,
                    querySouth,
                    if (worldOverview) 0.0 else wrap((minLongitude + maxLongitude) / 2.0),
                    if (worldOverview) 360.0 else querySpan
                )
                val queryRoads = loadRoads && cameraPosition.zoom >= FogBitmapRenderer.MIN_ROAD_ZOOM &&
                    loadedRoadBoundsAtStart?.contains(footprint) != true
                // Switching back from a global overview must restore the small
                // viewport-specific projection cache instead of keeping every
                // explored square worldwide in every subsequent detailed frame.
                val queryPlaces = loadedPlaceBoundsAtStart?.contains(footprint) != true ||
                    (!worldOverview && loadedPlaceBoundsAtStart?.longitudeSpan == 360.0)
                val roadQuery = if (queryRoads) {
                    repository.getRoadsInBoundsResult(
                        queryNorth, queryEast, querySouth, queryWest, limit = Int.MAX_VALUE
                    )
                } else {
                    null
                }
                val roads = roadQuery?.roads?.let { saved ->
                    // Optional historical evidence must never prevent normal roads from
                    // rendering if reading the extra visit table fails.
                    val visits = runCatching { repository.getVisitWindowsForRoads(saved) }
                        .getOrDefault(emptyMap())
                    OverlayRoads.prepare(saved, visits)
                }
                val places = if (queryPlaces) {
                    repository.getExploredGridInBounds(queryNorth, queryEast, querySouth, queryWest)
                } else {
                    null
                }
                val pending = if (cameraPosition.zoom >= FogBitmapRenderer.MIN_ROAD_ZOOM) {
                    repository.getPendingRouteInBoundsResult(
                        queryNorth, queryEast, querySouth, queryWest,
                        visibleSinceMillis = System.currentTimeMillis() - PENDING_ROUTE_MAX_AGE_MS
                    )
                } else TrackingRepository.PendingRouteResult(emptyList(), null)
                RoadDisplay(
                    roads,
                    roads?.let(::roadFeatures),
                    places,
                    footprint.takeIf { roadQuery?.complete == true },
                    footprint.takeIf { queryPlaces },
                    if (pending.roads.isEmpty()) EMPTY_FEATURES
                    else roadFeatures(OverlayRoads.prepare(pending.roads)),
                    pending.roads.isNotEmpty(),
                    pending.oldestVisiblePendingTimestampMillis?.plus(PENDING_ROUTE_MAX_AGE_MS)
                )
            }
            result.exceptionOrNull()?.let { Log.e("RoadConquest", "Could not refresh saved map data", it) }
            mainHandler.post {
                if (destroyed) return@post
                queryRunning = false
                if (!viewportActive) return@post
                if (revision == viewportRevision) {
                    result.getOrNull()?.let {
                        applyPendingRoute(it.pendingRoute, hasPending = it.pendingVisible)
                        // Also expire the preview when the map is idle and location updates stop.
                        mainHandler.removeCallbacks(expirePendingRoutes)
                        if (it.pendingVisible && it.nextPendingExpiryMillis != null) {
                            // Use the oldest visible pending fix, not the time of this redraw:
                            // multiple unresolved trails must expire independently, even on an idle map.
                            mainHandler.postDelayed(
                                expirePendingRoutes,
                                (it.nextPendingExpiryMillis - System.currentTimeMillis()).coerceAtLeast(1_000L)
                            )
                        }
                        if (it.places != null) {
                            loadedPlaceBounds = it.placeBounds
                            setDisplayedPlaces(it.places)
                        }
                        if (it.overlay != null && it.features != null && !queryRoadsAgain) {
                            loadedRoadBounds = it.roadBounds
                            setDisplayedRoads(it.overlay, it.features)
                        } else {
                            scheduleFogRender()
                        }
                    }
                }
                if (queryAgain || revision != viewportRevision) {
                    val reloadRoads = queryRoadsAgain || revision != viewportRevision
                    queryAgain = false
                    queryRoadsAgain = false
                    if (!cameraMoving) refreshViewport(reloadRoads)
                }
            }
        }
    }

    private fun installPlaceOverlayLayers(style: Style) {
        // A new MapLibre style has new empty GeoJSON sources even if the names match.
        shownOverlayKeys = emptyList()
        shownOverlayCacheGeneration = -1L
        for (mode in listOf(PlaceOverlayMode.COUNTRY, PlaceOverlayMode.STATE, PlaceOverlayMode.TOWN)) {
            style.addSource(GeoJsonSource(overlaySourceId(mode), EMPTY_FEATURES))
            style.addSource(GeoJsonSource(overlayBoundarySourceId(mode), EMPTY_FEATURES))
            val color = when (mode) {
                PlaceOverlayMode.COUNTRY -> 0xFF2F80ED.toInt()
                PlaceOverlayMode.STATE -> 0xFF8E44AD.toInt()
                PlaceOverlayMode.TOWN -> 0xFF27AE60.toInt()
                PlaceOverlayMode.NONE -> Color.TRANSPARENT
            }
            val outline = when (mode) {
                PlaceOverlayMode.COUNTRY -> 0xFF174A8B.toInt()
                PlaceOverlayMode.STATE -> 0xFF5D2C72.toInt()
                PlaceOverlayMode.TOWN -> 0xFF176B3D.toInt()
                PlaceOverlayMode.NONE -> Color.TRANSPARENT
            }
            style.addLayer(
                FillLayer(overlayLayerId(mode), overlaySourceId(mode)).withProperties(
                    fillColor(color),
                    fillOpacity(0.30f),
                    // Keep the built-in antialiased edge subtle; the dedicated 1.25 px line
                    // below supplies the visible separator without making borders look heavy.
                    fillOutlineColor(color),
                    // Antialiased translucent town fills render a second faded
                    // fringe on shared edges. Dedicated dissolved border lines
                    // handle these interfaces without dark overlap seams.
                    fillAntialias(mode != PlaceOverlayMode.TOWN)
                )
            )
            // A dedicated boundary layer gives neighboring places a stable, thin separator.
            // Fill outlines alone can disappear where adjacent/overlapping polygons meet.
            style.addLayer(
                LineLayer(overlayBoundaryLayerId(mode), overlayBoundarySourceId(mode)).withProperties(
                    lineColor(outline),
                    lineOpacity(0.90f),
                    lineWidth(1.25f),
                    lineCap(Property.LINE_CAP_ROUND),
                    lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
        }
        refreshPlaceOverlays()
    }

    private fun clearPlaceOverlaySources() {
        shownOverlayKeys = emptyList()
        shownOverlayCacheGeneration = -1L
        for (mode in listOf(PlaceOverlayMode.COUNTRY, PlaceOverlayMode.STATE, PlaceOverlayMode.TOWN)) {
            (map.style?.getSource(overlaySourceId(mode)) as? GeoJsonSource)?.setGeoJson(EMPTY_FEATURES)
            (map.style?.getSource(overlayBoundarySourceId(mode)) as? GeoJsonSource)?.setGeoJson(EMPTY_FEATURES)
        }
    }

    private fun postPlaceOverlay(
        generation: Int,
        kind: com.roadconquest.app.data.PlaceKind,
        data: List<PlaceOverlayData>
    ) {
        // Polygon conversion can be expensive for many visited municipalities.
        // Build immutable GeoJSON on overlayExecutor instead of blocking the map's UI thread.
        val snapshot = data.toList()
        // Existing boundaries can overlap due to mismatched or historical OSM
        // polygons. Partition only town shapes so their translucent fills never
        // stack or paint an overlapping boundary twice.
        // Official US Census boundaries are used as supplied.
        // Clipping individual municipalities invents slivers and gaps; only
        // shared boundary linework is dissolved. Other countries retain
        // the verified administrative OpenStreetMap fallback.
        val officialUs = if (kind == com.roadconquest.app.data.PlaceKind.TOWN) {
            snapshot.filter { UnitedStatesMunicipalBoundaries.isUnitedStatesKey(it.key) }
        } else emptyList()
        val other = if (officialUs.isNotEmpty()) {
            snapshot.filterNot { UnitedStatesMunicipalBoundaries.isUnitedStatesKey(it.key) }
        } else snapshot
        val otherPartition = if (kind == com.roadconquest.app.data.PlaceKind.TOWN &&
            other.isNotEmpty()
        ) TownOverlayPartition.render(other) else null
        val keys = snapshot.map { "${it.key}:${it.geometryJson.hashCode()}" }
        val fillFeatures = when {
            officialUs.isNotEmpty() && otherPartition != null ->
                mergeOverlayCollections(overlayFeatureCollection(officialUs), otherPartition.fills)
            officialUs.isNotEmpty() -> overlayFeatureCollection(officialUs)
            else -> otherPartition?.fills ?: overlayFeatureCollection(snapshot)
        }
        val boundaries = when {
            officialUs.isNotEmpty() && otherPartition != null ->
                mergeOverlayCollections(TownOverlayPartition.authoritativeOutlines(officialUs), otherPartition.outlines)
            officialUs.isNotEmpty() -> TownOverlayPartition.authoritativeOutlines(officialUs)
            else -> otherPartition?.outlines ?: overlayBoundaryFeatureCollection(snapshot)
        }
        mainHandler.post {
            if (destroyed || generation != overlayGeneration || overlayMode.kind != kind) return@post
            val mode = when (kind) {
                com.roadconquest.app.data.PlaceKind.COUNTRY -> PlaceOverlayMode.COUNTRY
                com.roadconquest.app.data.PlaceKind.STATE -> PlaceOverlayMode.STATE
                com.roadconquest.app.data.PlaceKind.TOWN -> PlaceOverlayMode.TOWN
            }
            val cacheGeneration = PlaceOverlayCache.generation()
            if (keys == shownOverlayKeys && cacheGeneration == shownOverlayCacheGeneration) return@post
            (map.style?.getSource(overlaySourceId(mode)) as? GeoJsonSource)
                ?.setGeoJson(fillFeatures)
            (map.style?.getSource(overlayBoundarySourceId(mode)) as? GeoJsonSource)
                ?.setGeoJson(boundaries)
            shownOverlayKeys = keys
            shownOverlayCacheGeneration = cacheGeneration
        }
    }

    private fun mergeOverlayCollections(first: String, second: String): String {
        val features = JSONArray()
        for (source in listOf(first, second)) {
            val items = JSONObject(source).getJSONArray("features")
            for (index in 0 until items.length()) features.put(items.getJSONObject(index))
        }
        return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
    }

    private fun overlayFeatureCollection(data: List<PlaceOverlayData>): String {
        val features = JSONArray()
        data.forEach { features.put(it.featureJson()) }
        return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
    }

    private fun overlayBoundaryFeatureCollection(data: List<PlaceOverlayData>): String {
        val features = JSONArray()
        fun addRings(rings: JSONArray) {
            for (ringIndex in 0 until rings.length()) {
                val ring = rings.optJSONArray(ringIndex) ?: continue
                if (ring.length() < 2) continue
                features.put(
                    JSONObject()
                        .put("type", "Feature")
                        .put("properties", JSONObject())
                        .put("geometry", JSONObject()
                            .put("type", "LineString")
                            .put("coordinates", ring))
                )
            }
        }
        for (overlay in data) {
            val geometry = runCatching { JSONObject(overlay.geometryJson) }.getOrNull() ?: continue
            val coordinates = geometry.optJSONArray("coordinates") ?: continue
            when (geometry.optString("type")) {
                "Polygon" -> addRings(coordinates)
                "MultiPolygon" -> {
                    for (polygonIndex in 0 until coordinates.length()) {
                        coordinates.optJSONArray(polygonIndex)?.let(::addRings)
                    }
                }
            }
        }
        return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
    }

    private fun overlaySourceId(mode: PlaceOverlayMode): String = when (mode) {
        PlaceOverlayMode.COUNTRY -> COUNTRY_OVERLAY_SOURCE_ID
        PlaceOverlayMode.STATE -> STATE_OVERLAY_SOURCE_ID
        PlaceOverlayMode.TOWN -> TOWN_OVERLAY_SOURCE_ID
        PlaceOverlayMode.NONE -> error("None has no overlay source")
    }

    private fun overlayBoundarySourceId(mode: PlaceOverlayMode): String = when (mode) {
        PlaceOverlayMode.COUNTRY -> COUNTRY_OVERLAY_BOUNDARY_SOURCE_ID
        PlaceOverlayMode.STATE -> STATE_OVERLAY_BOUNDARY_SOURCE_ID
        PlaceOverlayMode.TOWN -> TOWN_OVERLAY_BOUNDARY_SOURCE_ID
        PlaceOverlayMode.NONE -> error("None has no overlay boundary source")
    }

    private fun overlayLayerId(mode: PlaceOverlayMode): String = when (mode) {
        PlaceOverlayMode.COUNTRY -> COUNTRY_OVERLAY_LAYER_ID
        PlaceOverlayMode.STATE -> STATE_OVERLAY_LAYER_ID
        PlaceOverlayMode.TOWN -> TOWN_OVERLAY_LAYER_ID
        PlaceOverlayMode.NONE -> error("None has no overlay layer")
    }

    private fun overlayBoundaryLayerId(mode: PlaceOverlayMode): String = when (mode) {
        PlaceOverlayMode.COUNTRY -> COUNTRY_OVERLAY_BOUNDARY_LAYER_ID
        PlaceOverlayMode.STATE -> STATE_OVERLAY_BOUNDARY_LAYER_ID
        PlaceOverlayMode.TOWN -> TOWN_OVERLAY_BOUNDARY_LAYER_ID
        PlaceOverlayMode.NONE -> error("None has no overlay boundary layer")
    }

    private fun installRoadLayer(style: Style) {
        style.addSource(GeoJsonSource(ROAD_SOURCE_ID, displayedRoadFeatures))
        appliedRoadColor = Prefs.roadColor(context)
        val layer = LineLayer(ROAD_LAYER_ID, ROAD_SOURCE_ID).withProperties(
            lineColor(Cosmetics.roadColor(context).argb),
            lineOpacity(242f / 255f),
            lineWidth(4f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND)
        )
        layer.setMinZoom(FogBitmapRenderer.MIN_ROAD_ZOOM.toFloat())
        style.addLayer(layer)
    }

    private fun installFogLayer(style: Style) {
        activeDetailedFogAlternate = false
        detailedFogCoordinates = null
        regionalFogCoordinates = null
        showingDetailedFog = false
        showingRegionalFog = false
        installedRegionalFogKey = null
        // Keep a ready native world layer behind the detailed viewport. A fast pinch can
        // outrun even a padded bitmap; it must never reveal an unrendered rectangle.
        style.addSource(ImageSource(WORLD_FOG_SOURCE_ID, worldFogQuad(), overviewFog))
        style.addLayer(RasterLayer(WORLD_FOG_LAYER_ID, WORLD_FOG_SOURCE_ID).withProperties(
            rasterOpacity(if (fogEnabled) 1f else 0f), rasterFadeDuration(0f)
        ).apply { setRasterOpacityTransition(TransitionOptions(0L, 0L)) })
        val transparent = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        // Kept at a broader map scale while idle. Only one fog raster is visible:
        // stacking world/regional/detail would darken the unexplored map and cover holes.
        style.addSource(ImageSource(REGIONAL_FOG_SOURCE_ID, currentFogQuad(), transparent))
        style.addLayer(RasterLayer(REGIONAL_FOG_LAYER_ID, REGIONAL_FOG_SOURCE_ID).withProperties(
            rasterOpacity(0f), rasterFadeDuration(0f)
        ).apply { setRasterOpacityTransition(TransitionOptions(0L, 0L)) })
        style.addSource(ImageSource(FOG_SOURCE_ID, currentFogQuad(), transparent))
        style.addLayer(
            RasterLayer(FOG_LAYER_ID, FOG_SOURCE_ID).withProperties(
                rasterOpacity(0f),
                rasterFadeDuration(0f)
            ).apply { setRasterOpacityTransition(TransitionOptions(0L, 0L)) }
        )
        // Prepare the NEXT georeferenced raster while it is invisible.
        // Updating pixels and corners in place on the visible ImageSource
        // displayed the old clearing at the new coordinates for a frame.
        style.addSource(ImageSource(FOG_ALT_SOURCE_ID, currentFogQuad(), transparent))
        style.addLayer(
            RasterLayer(FOG_ALT_LAYER_ID, FOG_ALT_SOURCE_ID).withProperties(
                rasterOpacity(0f),
                rasterFadeDuration(0f)
            ).apply { setRasterOpacityTransition(TransitionOptions(0L, 0L)) }
        )
        transparent.recycle()
    }

    private fun applyPendingRoute(features: FeatureCollection, hasPending: Boolean) {
        // The native source starts empty after each style install. Only upload an empty
        // collection once when clearing a previously nonempty preview; preserve every
        // nonempty update so new/changed corners are still shown immediately.
        if (!hasPending && pendingRouteSourceIsEmpty) return
        val source = map.style?.getSource(PENDING_ROUTE_SOURCE_ID) as? GeoJsonSource ?: return
        source.setGeoJson(if (hasPending) features else EMPTY_FEATURES)
        pendingRouteSourceIsEmpty = !hasPending
    }

    private fun installRecordedRouteLayer(style: Style) {
        style.addSource(GeoJsonSource(PENDING_ROUTE_SOURCE_ID, EMPTY_FEATURES))
        pendingRouteSourceIsEmpty = true
        // Pending evidence is raw GPS, not final road geometry. Keep a faint, narrow
        // provisional trace so the map never appears to have a random hole while OSRM is
        // resolving the interval. Confirmed traveled roads remain thicker and nearly opaque,
        // and are drawn from road-snapped geometry only.
        val layer = LineLayer(PENDING_ROUTE_LAYER_ID, PENDING_ROUTE_SOURCE_ID).withProperties(
            lineColor(Cosmetics.roadColor(context).argb), lineWidth(3f), lineOpacity(0.60f),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
        )
        layer.setMinZoom(FogBitmapRenderer.MIN_ROAD_ZOOM.toFloat())
        style.addLayer(layer)
    }

    private fun updateCameraLimits(cameraPosition: CameraPosition = map.cameraPosition) {
        val target = cameraPosition.target ?: return
        val next = FogCoverage.minimumZoom(mapView.width, mapView.height, mapView.pixelRatio, target.latitude)
        if (!minimumZoom.isFinite() || abs(next - minimumZoom) > 0.001) {
            minimumZoom = next
            map.setMinZoomPreference(next)
        }
    }

    private fun updateFogCoverage(
        force: Boolean = false,
        cameraPosition: CameraPosition = map.cameraPosition
    ) {
        val coordinates = detailedFogCoordinates
        // The detailed ImageSource remains geographically attached to its quad
        // while MapLibre animates the camera. Keep it active until its actual
        // image bounds approach the viewport, instead of oscillating between a
        // low-resolution world mask and a detailed mask during every pinch.
        val detailed = fogEnabled && cameraPosition.zoom >= FogBitmapRenderer.MIN_FOG_REVEAL_ZOOM &&
            coordinates != null && run {
                map.projection.toScreenLocations(coordinates, fogCoverageScreen)
                FogCoverage.coversViewport(
                    fogCoverageScreen,
                    mapView.width,
                    mapView.height,
                    if (cameraMoving) FOG_MOVING_COVERAGE_MARGIN_FRACTION else 0.0
                )
            }
        val regional = fogEnabled && !detailed && regionalFogCoordinates?.let { region ->
            map.projection.toScreenLocations(region, fogCoverageScreen)
            FogCoverage.coversViewport(
                fogCoverageScreen, mapView.width, mapView.height,
                if (cameraMoving) FOG_MOVING_COVERAGE_MARGIN_FRACTION else 0.0
            )
        } == true
        val changed = detailed != showingDetailedFog || regional != showingRegionalFog
        if (!force && !changed) return
        val detail = map.style?.getLayer(
            if (activeDetailedFogAlternate) FOG_ALT_LAYER_ID else FOG_LAYER_ID
        ) as? RasterLayer
        val inactive = map.style?.getLayer(
            if (activeDetailedFogAlternate) FOG_LAYER_ID else FOG_ALT_LAYER_ID
        ) as? RasterLayer
        val worldLayer = map.style?.getLayer(WORLD_FOG_LAYER_ID) as? RasterLayer
        val regionalLayer = map.style?.getLayer(REGIONAL_FOG_LAYER_ID) as? RasterLayer
        showingDetailedFog = detailed
        showingRegionalFog = regional
        // MapLibre submits each native property change separately. Revealing the
        // replacement *before* hiding the old raster prevents one uncovered frame
        // during rapid animated zooms (observed on Android 12). All raster fade
        // durations and transitions are zero so any temporary overlap is bounded
        // to the same UI callback, not an animated double-darkened handoff.
        if (!fogEnabled) {
            worldLayer?.setProperties(rasterOpacity(0f))
            regionalLayer?.setProperties(rasterOpacity(0f))
            detail?.setProperties(rasterOpacity(0f))
            inactive?.setProperties(rasterOpacity(0f))
        } else if (detailed) {
            detail?.setProperties(rasterOpacity(1f))
            inactive?.setProperties(rasterOpacity(0f))
            regionalLayer?.setProperties(rasterOpacity(0f))
            worldLayer?.setProperties(rasterOpacity(0f))
        } else if (regional) {
            regionalLayer?.setProperties(rasterOpacity(1f))
            detail?.setProperties(rasterOpacity(0f))
            inactive?.setProperties(rasterOpacity(0f))
            worldLayer?.setProperties(rasterOpacity(0f))
        } else {
            worldLayer?.setProperties(rasterOpacity(1f))
            detail?.setProperties(rasterOpacity(0f))
            inactive?.setProperties(rasterOpacity(0f))
            regionalLayer?.setProperties(rasterOpacity(0f))
        }
    }

    private fun installCarLayer(style: Style) {
        updateCarImage(style)
        appliedCarStyle = Prefs.carStyle(context)
        appliedCarColor = Prefs.carColor(context)
        style.addSource(GeoJsonSource(CAR_SOURCE_ID, EMPTY_FEATURES))
        style.addLayer(
            SymbolLayer(CAR_LAYER_ID, CAR_SOURCE_ID).withProperties(
                iconImage(CAR_IMAGE_ID),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconRotate(0f)
            )
        )
    }

    private fun updateCarImage(style: Style) {
        val next = Cosmetics.renderCarIcon(context)
        runCatching { style.addImage(CAR_IMAGE_ID, next) }
            .onSuccess {
                carIconBitmap?.recycle()
                carIconBitmap = next
            }
            .onFailure { next.recycle() }
    }

    private fun setDisplayedRoads(roads: OverlayRoads, features: FeatureCollection) {
        displayedRoads = roads
        displayedRoadFeatures = features
        roadDataRevision++
        (map.style?.getSource(ROAD_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(features)
        scheduleFogRender()
    }

    private fun setDisplayedPlaces(places: DoubleArray) {
        if (displayedPlaces.contentEquals(places)) return
        displayedPlaces = places
        // Stored one-mile grid centers are authoritative; legacy samples were backfilled
        // once into this table. Scoring and road matching remain independent.
        savedGridCoordinates = FogGrid.visitedCorners(places)
        placeDataRevision++
    }

    private fun projectRoads(roads: OverlayRoads): DoubleArray {
        if (roads.coordinates.isEmpty()) return roads.coordinates
        val width = mapView.width
        val height = mapView.height
        if (roadProjectionDataRevision != roadDataRevision ||
            roadProjectionViewportRevision != viewportRevision ||
            roadProjectionWidth != width || roadProjectionHeight != height ||
            roadScreenCache.size != roads.coordinates.size
        ) {
            if (roadScreenCache.size != roads.coordinates.size) {
                roadScreenCache = DoubleArray(roads.coordinates.size)
            }
            map.projection.toScreenLocations(roads.coordinates, roadScreenCache)
            roadProjectionDataRevision = roadDataRevision
            roadProjectionViewportRevision = viewportRevision
            roadProjectionWidth = width
            roadProjectionHeight = height
        }
        return roadScreenCache
    }

    private fun projectPlaces(places: DoubleArray): DoubleArray {
        if (places.isEmpty()) return places
        val width = mapView.width
        val height = mapView.height
        if (placeProjectionDataRevision != placeDataRevision ||
            placeProjectionViewportRevision != viewportRevision ||
            placeProjectionWidth != width || placeProjectionHeight != height ||
            placeScreenCache.size != places.size
        ) {
            if (placeScreenCache.size != places.size) {
                placeScreenCache = DoubleArray(places.size)
            }
            val centerLongitude = map.cameraPosition.target?.longitude ?: 0.0
            map.projection.toScreenLocations(FogGrid.nearLongitude(places, centerLongitude), placeScreenCache)
            placeProjectionDataRevision = placeDataRevision
            placeProjectionViewportRevision = viewportRevision
            placeProjectionWidth = width
            placeProjectionHeight = height
        }
        return placeScreenCache
    }

    private fun updateCarLayer() {
        if (!viewportActive) return
        val style = map.style ?: return
        val fix = liveLocation.current(SystemClock.elapsedRealtime())
        val source = style.getSource(CAR_SOURCE_ID) as? GeoJsonSource ?: return
        if (fix == null) {
            source.setGeoJson(EMPTY_FEATURES)
            return
        }
        source.setGeoJson(Point.fromLngLat(fix.longitude, fix.latitude))
        (style.getLayer(CAR_LAYER_ID) as? SymbolLayer)?.setProperties(iconRotate(fix.bearing.toFloat()))
    }

    private fun scheduleFogRender() {
        if (destroyed || !viewportActive || !fogEnabled) return
        // captureFog() reads the current camera when a new bitmap is needed.
        // Overview uses a world-sized bitmap with its own explored-cell mask.
        // Never replace recorded exploration with fully opaque static fog.
        if (fogRunning) {
            fogAgain = true
            return
        }
        mainHandler.removeCallbacks(renderFog)
        val delay = FOG_RENDER_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastFogRenderAt)
        if (cameraMoving && delay > 0L) {
            mainHandler.postDelayed(renderFog, delay)
            return
        }
        val capture = captureFog() ?: return
        // MapLibre already animates this georeferenced ImageSource in native
        // coordinates. Do not repaint/replace an identical cloud rectangle on
        // each 120ms pan callback: that caused needless texture uploads and
        // could flash an otherwise stable clearing on the GPU.
        if (capture.key == installedFogKey) {
            if (!cameraMoving) scheduleRegionalFogRender()
            return
        }
        lastFogRenderAt = SystemClock.elapsedRealtime()
        fogAgain = false
        fogRunning = true
        val reusable = reusableFogBitmap
        reusableFogBitmap = null
        fogExecutor.execute {
            val bitmap = runCatching { FogBitmapRenderer.render(capture.request, reusable) }
            bitmap.exceptionOrNull()?.let { Log.e("RoadConquest", "Could not render map fog", it) }
            mainHandler.post {
                val rendered = bitmap.getOrNull()
                if (destroyed) {
                    rendered?.recycle()
                    return@post
                }
                fogRunning = false
                if (rendered != null) {
                    if (viewportActive && capture.styleGeneration == styleGeneration) {
                        val nextAlternate = !activeDetailedFogAlternate
                        val id = if (capture.world) WORLD_FOG_SOURCE_ID
                            else if (nextAlternate) FOG_ALT_SOURCE_ID else FOG_SOURCE_ID
                        val source = map.style?.getSource(id) as? ImageSource
                        // The incoming detailed source is HIDDEN while its quad
                        // and bitmap are both replaced; visible pixels cannot be
                        // mistakenly attached to a new geographic location.
                        source?.setCoordinates(capture.quad)
                        // MapLibre 13.6.1 synchronously copies source bitmap pixels.
                        source?.setImage(rendered)
                        if (source != null) installedFogKey = capture.key
                        if (!capture.world && source != null) {
                            activeDetailedFogAlternate = nextAlternate
                            detailedFogCoordinates = doubleArrayOf(
                                capture.quad.topLeft.latitude, capture.quad.topLeft.longitude,
                                capture.quad.topRight.latitude, capture.quad.topRight.longitude,
                                capture.quad.bottomRight.latitude, capture.quad.bottomRight.longitude,
                                capture.quad.bottomLeft.latitude, capture.quad.bottomLeft.longitude
                            )
                        }
                        updateFogCoverage(force = true)
                    }
                    reusableFogBitmap = rendered
                }
                if (fogAgain) {
                    fogAgain = false
                    scheduleFogRender()
                } else if (!cameraMoving) {
                    scheduleRegionalFogRender()
                }
            }
        }
    }

    /**
     * A second, wider raster remains in native map coordinates during a pinch.
     * Render it only at rest, behind the priority detailed job, and never move its
     * ImageSource while visible. World fog remains the final safety layer.
     */
    private fun scheduleRegionalFogRender() {
        if (destroyed || !viewportActive || !fogEnabled || cameraMoving ||
            regionalFogRunning || fogRunning
        ) return
        val capture = captureFog(regional = true) ?: return
        if (capture.key == installedRegionalFogKey) return
        regionalFogRunning = true
        val reusable = reusableRegionalFogBitmap
        reusableRegionalFogBitmap = null
        fogExecutor.execute {
            val bitmap = runCatching { FogBitmapRenderer.render(capture.request, reusable) }
            bitmap.exceptionOrNull()?.let { Log.e("RoadConquest", "Could not render regional fog", it) }
            mainHandler.post {
                val rendered = bitmap.getOrNull()
                if (destroyed) {
                    rendered?.recycle()
                    return@post
                }
                regionalFogRunning = false
                if (rendered != null) {
                    // Recheck motion after drawing: a newly started pinch must not
                    // publish an obsolete backup over the region currently displayed.
                    if (viewportActive && !cameraMoving && !showingRegionalFog &&
                        capture.styleGeneration == styleGeneration
                    ) {
                        val source = map.style?.getSource(REGIONAL_FOG_SOURCE_ID) as? ImageSource
                        source?.setCoordinates(capture.quad)
                        source?.setImage(rendered)
                        if (source != null) {
                            installedRegionalFogKey = capture.key
                            regionalFogCoordinates = doubleArrayOf(
                                capture.quad.topLeft.latitude, capture.quad.topLeft.longitude,
                                capture.quad.topRight.latitude, capture.quad.topRight.longitude,
                                capture.quad.bottomRight.latitude, capture.quad.bottomRight.longitude,
                                capture.quad.bottomLeft.latitude, capture.quad.bottomLeft.longitude
                            )
                            updateFogCoverage(force = true)
                        }
                    }
                    reusableRegionalFogBitmap = rendered
                }
            }
        }
    }

    private fun captureFog(regional: Boolean = false): FogCapture? {
        if (mapView.width <= 0 || mapView.height <= 0 || map.style == null) return null
        val position = map.cameraPosition
        val center = position.target ?: return null
        if (regional && position.zoom < REGIONAL_FOG_MIN_ZOOM) return null
        val size = if (regional) REGIONAL_FOG_DIMENSION
            else FogBitmapRenderer.bitmapDimensionForZoom(position.zoom)
        val mercatorMetersPerPixel =
            map.projection.getMetersPerPixelAtLatitude(center.latitude) /
                mapView.pixelRatio / cos(Math.toRadians(center.latitude)).coerceAtLeast(0.01)
        if (!mercatorMetersPerPixel.isFinite() || mercatorMetersPerPixel <= 0.0) return null

        // The raster and ALL its erased mile-cell geometry use exactly the same
        // north-up Web Mercator coordinates. Neither depends on camera bearing,
        // device screen projection, previous gesture deltas nor texture phase.
        val world = !regional && (position.zoom < FogBitmapRenderer.MIN_FOG_REVEAL_ZOOM ||
            maxOf(mapView.width, mapView.height) * mercatorMetersPerPixel >=
                FogGeoRaster.WORLD_METERS)
        val raster = when {
            world -> FogGeoRaster.fullWorld(size)
            regional -> FogGeoRaster.pinchFallback(
                center.latitude, center.longitude, position.zoom, size,
                mapView.width, mapView.height, mercatorMetersPerPixel
            )
            else -> FogGeoRaster.around(
                center.latitude, center.longitude, position.zoom, size,
                mapView.width, mapView.height, mercatorMetersPerPixel
            )
        }
        // The live marker is not authoritative exploration: its current preview
        // can wander while parked or immediately after reopening the app.
        // Only persisted, movement-validated mile cells may clear the fog.
        val corners = savedGridCoordinates
        val screen = raster.project(corners)
        val geographic = raster.corners()
        val quad = LatLngQuad(
            LatLng(geographic[0], geographic[1]),
            LatLng(geographic[2], geographic[3]),
            LatLng(geographic[4], geographic[5]),
            LatLng(geographic[6], geographic[7])
        )
        val key = FogKey(
            styleGeneration, world, raster.westMeters, raster.northMeters,
            raster.metersPerPixel, size, placeDataRevision, null
        )
        return FogCapture(styleGeneration, quad, FogBitmapRenderer.Request(
            bitmapWidth = size,
            bitmapHeight = size,
            screenLeft = 0f,
            screenTop = 0f,
            screenScale = 1f,
            roads = OverlayRoads.EMPTY,
            roadScreen = doubleArrayOf(),
            centerLatitude = center.latitude,
            metersPerScreenPixelAtCenter = raster.metersPerPixel,
            liveLatitude = null,
            liveScreen = null,
            textureMatrix = raster.textureMatrix(FogBitmapRenderer.CLOUD_DETAIL_METERS),
            mediumCloudMatrix = raster.textureMatrix(FogBitmapRenderer.CLOUD_MEDIUM_METERS),
            broadCloudMatrix = raster.textureMatrix(FogBitmapRenderer.CLOUD_BROAD_METERS),
            gridMode = true,
            gridCoordinates = corners,
            gridScreen = screen
        ), world = world, key = key)
    }

    private fun currentFogQuad(): LatLngQuad {
        if (map.cameraPosition.zoom < 6.0) return worldFogQuad()
        if (mapView.width <= 0 || mapView.height <= 0) {
            val bounds = map.projection.visibleRegion.latLngBounds
            return LatLngQuad(
                LatLng(bounds.latitudeNorth, bounds.longitudeWest),
                LatLng(bounds.latitudeNorth, bounds.longitudeEast),
                LatLng(bounds.latitudeSouth, bounds.longitudeEast),
                LatLng(bounds.latitudeSouth, bounds.longitudeWest)
            )
        }
        val padX = mapView.width * FogBitmapRenderer.VIEWPORT_PADDING_MULTIPLIER
        val padY = mapView.height * FogBitmapRenderer.VIEWPORT_PADDING_MULTIPLIER
        return fogQuad(-padX, -padY, mapView.width + padX, mapView.height + padY)
    }

    private fun worldFogQuad() = LatLngQuad(
        LatLng(85.05112878, -180.0), LatLng(85.05112878, 180.0),
        LatLng(-85.05112878, 180.0), LatLng(-85.05112878, -180.0)
    )

    private fun unwrappedCorner(x: Float, y: Float, center: Double): LatLng {
        val point = map.projection.fromScreenLocation(PointF(x, y))
        val longitude = center + ((point.longitude - center + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return LatLng(point.latitude, longitude)
    }

    private fun fogQuad(
        left: Float, top: Float, right: Float, bottom: Float,
        centerLongitude: Double = map.cameraPosition.target?.longitude ?: 0.0
    ): LatLngQuad =
        LatLngQuad(
            unwrappedCorner(left, top, centerLongitude), unwrappedCorner(right, top, centerLongitude),
            unwrappedCorner(right, bottom, centerLongitude), unwrappedCorner(left, bottom, centerLongitude)
        )

    private fun roadFeatures(roads: OverlayRoads): FeatureCollection {
        if (roads.coordinates.isEmpty()) return EMPTY_FEATURES
        val features = ArrayList<Feature>((roads.starts.size - 1).coerceAtLeast(0))
        for (road in 0 until roads.starts.size - 1) {
            val points = ArrayList<Point>((roads.starts[road + 1] - roads.starts[road]) / 2)
            for (i in roads.starts[road] until roads.starts[road + 1] step 2) {
                points += Point.fromLngLat(roads.coordinates[i + 1], roads.coordinates[i])
            }
            if (points.size >= 2) features += Feature.fromGeometry(LineString.fromLngLats(points))
        }
        return FeatureCollection.fromFeatures(features)
    }

    private data class RoadDisplay(
        val overlay: OverlayRoads?,
        val features: FeatureCollection?,
        val places: DoubleArray?,
        val roadBounds: RoadQueryBounds?,
        val placeBounds: RoadQueryBounds?,
        val pendingRoute: FeatureCollection,
        val pendingVisible: Boolean,
        val nextPendingExpiryMillis: Long?
    )

    private data class RoadQueryBounds(
        val north: Double,
        val south: Double,
        val longitudeCenter: Double,
        val longitudeSpan: Double
    ) {
        fun contains(other: RoadQueryBounds): Boolean {
            if (north + BOUNDS_EPSILON < other.north || south - BOUNDS_EPSILON > other.south ||
                longitudeSpan + BOUNDS_EPSILON < other.longitudeSpan
            ) return false
            if (longitudeSpan >= 360.0 - BOUNDS_EPSILON) return true
            val centerDistance = abs(
                ((other.longitudeCenter - longitudeCenter + 540.0) % 360.0) - 180.0
            )
            return other.longitudeSpan + 2 * centerDistance <= longitudeSpan + BOUNDS_EPSILON
        }
    }

    private data class FogKey(
        val styleGeneration: Int,
        val world: Boolean,
        val westMeters: Double,
        val northMeters: Double,
        val metersPerPixel: Double,
        val imageDimension: Int,
        val visitedRevision: Int,
        val liveCellKey: Long?
    )

    private data class FogCapture(
        val styleGeneration: Int,
        val quad: LatLngQuad,
        val request: FogBitmapRenderer.Request,
        val world: Boolean,
        val key: FogKey
    )

    fun destroy() {
        destroyed = true
        resumeGeneration++
        map.removeOnCameraMoveStartedListener(cameraMoveStartedListener)
        map.removeOnCameraMoveListener(cameraMoveListener)
        map.removeOnCameraIdleListener(cameraIdleListener)
        mapView.removeOnLayoutChangeListener(layoutListener)
        mainHandler.removeCallbacksAndMessages(null)
        resumeFrameListener?.let(mapView::removeOnDidFinishRenderingFrameListener)
        resumeFrameListener = null
        reusableFogBitmap?.recycle()
        reusableFogBitmap = null
        reusableRegionalFogBitmap?.recycle()
        reusableRegionalFogBitmap = null
        carIconBitmap?.recycle()
        carIconBitmap = null
        overlayGeneration++
        executor.shutdownNow()
        fogExecutor.shutdownNow()
        overlayExecutor.shutdownNow()
    }

    companion object {
        private const val COUNTRY_OVERLAY_SOURCE_ID = "roadconquest-country-overlays"
        private const val STATE_OVERLAY_SOURCE_ID = "roadconquest-state-overlays"
        private const val TOWN_OVERLAY_SOURCE_ID = "roadconquest-town-overlays"
        private const val COUNTRY_OVERLAY_BOUNDARY_SOURCE_ID = "roadconquest-country-overlay-boundaries"
        private const val STATE_OVERLAY_BOUNDARY_SOURCE_ID = "roadconquest-state-overlay-boundaries"
        private const val TOWN_OVERLAY_BOUNDARY_SOURCE_ID = "roadconquest-town-overlay-boundaries"
        private const val COUNTRY_OVERLAY_LAYER_ID = "roadconquest-country-overlays-fill"
        private const val STATE_OVERLAY_LAYER_ID = "roadconquest-state-overlays-fill"
        private const val TOWN_OVERLAY_LAYER_ID = "roadconquest-town-overlays-fill"
        private const val COUNTRY_OVERLAY_BOUNDARY_LAYER_ID = "roadconquest-country-overlays-outline"
        private const val STATE_OVERLAY_BOUNDARY_LAYER_ID = "roadconquest-state-overlays-outline"
        private const val TOWN_OVERLAY_BOUNDARY_LAYER_ID = "roadconquest-town-overlays-outline"
        private const val ROAD_SOURCE_ID = "roadconquest-traveled-roads"
        private const val PENDING_ROUTE_SOURCE_ID = "roadconquest-recorded-route"
        private const val PENDING_ROUTE_LAYER_ID = "roadconquest-recorded-route-line"
        private const val ROAD_LAYER_ID = "roadconquest-traveled-roads-line"
        private const val FOG_SOURCE_ID = "roadconquest-fog"
        private const val FOG_LAYER_ID = "roadconquest-fog-raster"
        private const val FOG_ALT_SOURCE_ID = "roadconquest-fog-buffer"
        private const val FOG_ALT_LAYER_ID = "roadconquest-fog-buffer-raster"
        private const val WORLD_FOG_SOURCE_ID = "roadconquest-world-fog"
        private const val WORLD_FOG_LAYER_ID = "roadconquest-world-fog-raster"
        private const val REGIONAL_FOG_SOURCE_ID = "roadconquest-regional-fog"
        private const val REGIONAL_FOG_LAYER_ID = "roadconquest-regional-fog-raster"
        private const val REGIONAL_FOG_DIMENSION = 768
        private const val REGIONAL_FOG_MIN_ZOOM = 7.0
        private val overviewFog by lazy {
            // The instantly available safety layer must use the EXACT same
            // geographic cloud shader/color as subsequent detailed captures.
            // Otherwise the first pan/zoom swaps a blue legacy texture in
            // behind the dark gray clouds and appears to change map tint.
            val geo = FogGeoRaster.fullWorld(FogTexture.SIZE)
            FogBitmapRenderer.render(FogBitmapRenderer.Request(
                bitmapWidth = FogTexture.SIZE,
                bitmapHeight = FogTexture.SIZE,
                screenLeft = 0f,
                screenTop = 0f,
                screenScale = 1f,
                roads = OverlayRoads.EMPTY,
                roadScreen = doubleArrayOf(),
                centerLatitude = 0.0,
                metersPerScreenPixelAtCenter = geo.metersPerPixel,
                liveLatitude = null,
                liveScreen = null,
                textureMatrix = geo.textureMatrix(FogBitmapRenderer.CLOUD_DETAIL_METERS),
                mediumCloudMatrix = geo.textureMatrix(FogBitmapRenderer.CLOUD_MEDIUM_METERS),
                broadCloudMatrix = geo.textureMatrix(FogBitmapRenderer.CLOUD_BROAD_METERS),
                gridMode = true
            ))
        }
        private const val CAR_SOURCE_ID = "roadconquest-car"
        private const val CAR_LAYER_ID = "roadconquest-car-symbol"
        private const val CAR_IMAGE_ID = "roadconquest-car-image"
        private val EMPTY_FEATURES = FeatureCollection.fromFeatures(emptyList<Feature>())
        private const val BOUNDS_EPSILON = 1e-9
        private const val RESUME_VISIBILITY_RETRY_MS = 16L
        // Native MapLibre transforms the georeferenced bitmap between refreshes, so ~8 fps
        // while actively gesturing is visually continuous without wasting battery on 12.5 fps
        // off-screen bitmap redraws. Idle renders still happen immediately.
        // Live raw fixes are a short-lived preview, not permanently unlocked roads.
        // Their matching/retry state and GPS history remain in the database.
        private const val PENDING_ROUTE_MAX_AGE_MS = 120_000L
        private const val FOG_RENDER_INTERVAL_MS = 120L
        private const val FOG_MOVING_COVERAGE_MARGIN_FRACTION = 0.05
        private const val STARTING_LOCATION_ZOOM = 15.0
    }
}
