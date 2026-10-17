package com.roadconquest.app.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import com.roadconquest.app.progression.Cosmetics
import com.roadconquest.app.R
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.data.ProgressionRepository
import com.roadconquest.app.data.PlaceKind
import com.roadconquest.app.util.Prefs
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
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
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.GeoJsonOptions
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
    private var baseProvider = ""
    private var initialStyleReady: (() -> Unit)? = null
    private val retryBaseProvider = Runnable { refreshBaseProvider() }
    private val providerListener: () -> Unit = { mainHandler.post { refreshBaseProvider() } }
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
    // The style starts with a ready 512px full-world cloud image. Re-uploading
    // identical pixels during a rapid pinch can briefly invalidate the native
    // texture on Android 12; only replace it when its exploration mask changes.
    private var worldFogHasExploration = false
    private var carIconBitmap: Bitmap? = null
    private var appliedCarStyle = ""
    private var appliedCarColor = ""
    private var appliedRoadColor = ""
    private var fogEnabled = Prefs.isFogEnabled(context)
    private var overlayMode = Prefs.placeOverlayMode(context)
    private var nearbyExplorationRequested = false
    private var nearbyCenter: Pair<Double, Double>? = null
    private var nearbyExplorationRevision = 0
    private var nearbyVisitedFeatures = EMPTY_FEATURES.toJson()
    private var nearbyUnvisitedFeatures = EMPTY_FEATURES.toJson()
    private var nearbyNumberFeatures = EMPTY_FEATURES.toJson()
    private val nearbyMarkerImages = HashMap<String, Bitmap>()
    @Volatile private var shownOverlayKeys = emptyList<String>()
    @Volatile private var shownOverlayCacheGeneration = -1L
    private val overlayClient = PlaceOverlayClient()
    @Volatile private var overlayGeneration = 0
    @Volatile private var visibleTownViewport: OverlayViewport? = null
    // These are owned by overlayExecutor. Camera changes can redraw a new
    // viewport from memory instead of refetching every visited boundary.
    private var loadedOverlayGeneration = -1
    private var loadedOverlayKind: PlaceKind? = null
    private var loadedOverlayCacheGeneration = -1L
    private var loadedOverlayData: List<PlaceOverlayData> = emptyList()
    private val overlayBounds = HashMap<String, OverlayViewport?>()
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
    private var placeDataRevision = 0
    private var detailedFogCoordinates: DoubleArray? = null
    private var regionalFogCoordinates: DoubleArray? = null
    private val fogCoverageScreen = DoubleArray(8)
    private var showingDetailedFog = false
    private var showingRegionalFog = false
    private var showingOverviewWorldFog = false
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
        // Mark a changed detailed image as in flight before choosing the
        // idle handoff; otherwise the regional mask briefly drops too early.
        scheduleFogRender()
        updateFogCoverage(cameraPosition = cameraPosition)
        refreshViewport()
        refreshVisibleTownOverlays()
    }

    fun initialize(onReady: () -> Unit) {
        initialStyleReady = onReady
        BaseMapProviders.install()
        com.roadconquest.app.services.ServiceConfig.refresh(context)
        BaseMapProviders.addListener(providerListener)
        map.setMaxZoomPreference(FogBitmapRenderer.MAX_ZOOM)
        map.setMaxPitchPreference(0.0)
        map.uiSettings.setTiltGesturesEnabled(false)
        mapView.addOnLayoutChangeListener(layoutListener)
        updateCameraLimits()
        map.addOnCameraMoveStartedListener(cameraMoveStartedListener)
        map.addOnCameraMoveListener(cameraMoveListener)
        map.addOnCameraIdleListener(cameraIdleListener)
        loadStyle { }
    }

    fun setMapMode(mode: MapMode) {
        if (destroyed || mode == mapMode) return
        mapMode = mode
        loadStyle { }
    }

    fun setFogEnabled(enabled: Boolean) {
        // Settings/Activity resume repeats the current choice frequently. A
        // no-op must not resubmit four native raster opacity properties or
        // queue another bitmap render; resumeViewport handles camera refresh.
        if (destroyed || enabled == fogEnabled) return
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

    /** Only locally recorded mile cells are used; exploring never calls the map API. */
    fun isNearbyExplorationVisible(): Boolean = nearbyExplorationRequested

    fun hideNearbyExploration() {
        nearbyExplorationRequested = false
        nearbyCenter = null
        nearbyExplorationRevision++
        nearbyVisitedFeatures = EMPTY_FEATURES.toJson()
        nearbyUnvisitedFeatures = EMPTY_FEATURES.toJson()
        nearbyNumberFeatures = EMPTY_FEATURES.toJson()
        applyNearbyExploration()
    }

    fun showNearbyExploration(
        latitude: Double, longitude: Double,
        onShown: (Int, Int) -> Unit, onError: () -> Unit,
        fitCamera: Boolean = true
    ) {
        if (destroyed) return
        nearbyExplorationRequested = true
        nearbyCenter = latitude to longitude
        val revision = ++nearbyExplorationRevision
        val initialViewportRevision = viewportRevision
        overlayExecutor.execute {
            val result = runCatching {
                val cells = repository.nearbyExplorationTiles(latitude, longitude)
                val visited = JSONArray()
                val unvisited = JSONArray()
                val labels = JSONArray()
                cells.forEachIndexed { index, (cell, explored) ->
                    val (lat, lon) = FogGrid.center(cell)
                    val corners = FogGrid.nearLongitude(FogGrid.corners(lat, lon), longitude)
                    val ring = JSONArray()
                    for (i in 0..4) {
                        val corner = i % 4
                        ring.put(JSONArray().put(corners[corner * 2 + 1]).put(corners[corner * 2]))
                    }
                    val polygon = JSONObject().put("type", "Feature")
                        .put("geometry", JSONObject().put("type", "Polygon")
                            .put("coordinates", JSONArray().put(ring)))
                        .put("properties", JSONObject())
                    (if (explored) visited else unvisited).put(polygon)
                    labels.put(JSONObject().put("type", "Feature")
                        .put("geometry", JSONObject().put("type", "Point").put("coordinates",
                            JSONArray().put((corners[1] + corners[3]) / 2.0).put(lat)))
                        .put("properties", JSONObject().put("icon", nearbyMarkerId(index + 1, explored))))
                }
                fun collection(features: JSONArray): String = JSONObject()
                    .put("type", "FeatureCollection").put("features", features).toString()
                NearbyOverlaySnapshot(collection(visited), collection(unvisited),
                    collection(labels), cells.map { it.second }, visited.length())
            }
            mainHandler.post {
                if (destroyed || revision != nearbyExplorationRevision ||
                    !nearbyExplorationRequested) return@post
                result.fold({ snapshot ->
                    nearbyVisitedFeatures = snapshot.visited
                    nearbyUnvisitedFeatures = snapshot.unvisited
                    nearbyNumberFeatures = snapshot.markers
                    ensureNearbyMarkerImages(snapshot.states)
                    applyNearbyExploration()
                    // Never snap the camera back if the user panned while the
                    // local query was waiting behind another overlay operation.
                    if (fitCamera && initialViewportRevision == viewportRevision) {
                        fitNearbyExploration(latitude, longitude)
                    }
                    onShown(snapshot.exploredCount, snapshot.states.size)
                }, { error ->
                    Log.w("RoadConquest", "Could not load nearby cells", error)
                    hideNearbyExploration()
                    onError()
                })
            }
        }
    }

    /** Fit the 25-cell neighborhood into the visible map area, not under the bottom sheet. */
    private fun fitNearbyExploration(latitude: Double, longitude: Double) {
        val origin = FogGrid.cell(latitude, longitude) ?: return
        val cells = FogGrid.neighbors(origin)
        if (cells.isEmpty() || mapView.width <= 0 || mapView.height <= 0) return
        val corners = cells.flatMap { cell ->
            val (lat, lon) = FogGrid.center(cell)
            FogGrid.corners(lat, lon).toList().chunked(2)
                .map { (cornerLat, cornerLon) -> cornerLat to cornerLon }
        }
        // MapLibre's ordinary LatLngBounds does not wrap across the date line.
        // Use a compact centered view there instead of zooming out to the world.
        if (corners.any { (_, lon) -> lon !in -180.0..180.0 } ||
            corners.maxOf { it.second } - corners.minOf { it.second } > 180.0) {
            map.moveCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(map.cameraPosition).target(LatLng(latitude, longitude))
                    .zoom(11.0).build()
            ))
            return
        }
        val bounds = LatLngBounds.Builder().apply {
            corners.forEach { (lat, lon) -> include(LatLng(lat, lon)) }
        }.build()
        // Use measured screen obstructions, not fixed heights: the tracking
        // sheet and text chips can grow with font scale, device insets and locale.
        // Keep at least 120dp of the map available on shorter/landscape screens.
        val density = context.resources.displayMetrics.density
        val root = mapView.rootView
        val controlsTop = listOf(R.id.overlayButton, R.id.pointsText).maxOf { id ->
            root.findViewById<View>(id)?.bottom ?: 0
        } + (8 * density).toInt()
        val panelTop = root.findViewById<View>(R.id.controlPanel)?.top ?: 0
        val measuredTop = controlsTop.takeIf { it > 0 } ?: (52 * density).toInt()
        val measuredBottom = if (panelTop > 0) mapView.height - panelTop + (12 * density).toInt()
            else (166 * density).toInt()
        val maxInsets = (mapView.height - 120 * density).coerceAtLeast(0f)
        val combined = (measuredTop + measuredBottom).toFloat()
        val scale = if (combined > maxInsets && combined > 0f) maxInsets / combined else 1f
        val top = (measuredTop * scale).toInt()
        val bottom = (measuredBottom * scale).toInt()
        val side = (24 * density).toInt().coerceAtMost(mapView.width / 6)
        map.getCameraForLatLngBounds(bounds, intArrayOf(side, top, side, bottom))?.let {
            map.moveCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(it).padding(0.0, 0.0, 0.0, 0.0).build()
            ))
        }
    }

    /** Update only the tile colors and numbers after saved exploration changes. */
    fun refreshNearbyExploration() {
        if (!nearbyExplorationRequested) return
        val (latitude, longitude) = nearbyCenter ?: return
        showNearbyExploration(latitude, longitude, { _, _ -> }, {}, fitCamera = false)
    }

    private fun applyNearbyExploration() {
        val style = map.style ?: return
        (style.getSource(NEARBY_VISITED_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(nearbyVisitedFeatures)
        (style.getSource(NEARBY_UNVISITED_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(nearbyUnvisitedFeatures)
        (style.getSource(NEARBY_NUMBERS_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(nearbyNumberFeatures)
    }

    private data class NearbyOverlaySnapshot(
        val visited: String, val unvisited: String, val markers: String,
        val states: List<Boolean>, val exploredCount: Int
    )

    private fun nearbyMarkerId(number: Int, explored: Boolean): String =
        "roadconquest-nearby-$number-${if (explored) "visited" else "new"}"

    private fun ensureNearbyMarkerImages(states: List<Boolean>) {
        states.forEachIndexed { index, explored ->
            val id = nearbyMarkerId(index + 1, explored)
            if (id !in nearbyMarkerImages) {
                val image = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(image)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (explored) 0xFF147A58.toInt() else 0xFF946015.toInt()
                }
                canvas.drawCircle(20f, 20f, 19f, paint)
                paint.color = Color.WHITE
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f
                canvas.drawCircle(20f, 20f, 18f, paint)
                paint.style = Paint.Style.FILL
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = if (index < 9) 19f else 17f
                canvas.drawText("${index + 1}", 20f,
                    20f - (paint.ascent() + paint.descent()) / 2f, paint)
                nearbyMarkerImages[id] = image
                map.style?.addImage(id, image)
            }
        }
    }

    private fun installNearbyExploration(style: Style) {
        style.addSource(GeoJsonSource(NEARBY_VISITED_SOURCE_ID, nearbyVisitedFeatures))
        style.addSource(GeoJsonSource(NEARBY_UNVISITED_SOURCE_ID, nearbyUnvisitedFeatures))
        style.addSource(GeoJsonSource(NEARBY_NUMBERS_SOURCE_ID, nearbyNumberFeatures))
        // Local bitmap numbers work even with the offline fallback style, which
        // intentionally has no glyph endpoint or text-font network requests.
        nearbyMarkerImages.forEach { (id, image) -> style.addImage(id, image) }
        style.addLayer(FillLayer(NEARBY_UNVISITED_LAYER_ID, NEARBY_UNVISITED_SOURCE_ID)
            .withProperties(fillColor(0xFFE4A42D.toInt()), fillOpacity(0.38f),
                fillOutlineColor(0xFFFFD271.toInt())))
        style.addLayer(FillLayer(NEARBY_VISITED_LAYER_ID, NEARBY_VISITED_SOURCE_ID)
            .withProperties(fillColor(0xFF149F72.toInt()), fillOpacity(0.38f),
                fillOutlineColor(0xFF7FE2AE.toInt())))
    }

    /** Numbers belong above confirmed roads; tinted tiles belong below them. */
    private fun installNearbyMarkerLayer(style: Style) {
        style.addLayer(SymbolLayer(NEARBY_NUMBERS_LAYER_ID, NEARBY_NUMBERS_SOURCE_ID)
            .withProperties(iconImage(Expression.image(Expression.get("icon"))),
                iconAllowOverlap(true), iconIgnorePlacement(true)))
    }

    private fun captureTownViewport(): OverlayViewport? {
        if (map.cameraPosition.zoom < 8.0) return null
        val bounds = map.projection.visibleRegion.latLngBounds
        return OverlayViewport.padded(
            bounds.longitudeWest, bounds.longitudeEast,
            bounds.latitudeSouth, bounds.latitudeNorth
        )
    }

    private fun refreshVisibleTownOverlays() {
        if (destroyed || overlayMode != PlaceOverlayMode.TOWN) return
        visibleTownViewport = captureTownViewport()
        val generation = overlayGeneration
        val cacheGeneration = PlaceOverlayCache.generation()
        runCatching {
            overlayExecutor.execute {
                if (!destroyed && generation == overlayGeneration &&
                    loadedOverlayGeneration == generation &&
                    loadedOverlayKind == PlaceKind.TOWN &&
                    loadedOverlayCacheGeneration == cacheGeneration
                ) {
                    postPlaceOverlay(generation, PlaceKind.TOWN, loadedOverlayData, cacheGeneration)
                }
            }
        }
    }

    fun refreshPlaceOverlays() {
        if (destroyed) return
        val generation = ++overlayGeneration
        val kind = overlayMode.kind ?: return
        visibleTownViewport = if (kind == PlaceKind.TOWN) captureTownViewport() else null
        val cacheGeneration = PlaceOverlayCache.generation()
        overlayExecutor.execute {
            val places = runCatching { ProgressionRepository(context).visitedPlaces(kind) }
                .getOrElse {
                    Log.e("RoadConquest", "Could not load discovered places for overlay", it)
                    return@execute
                }
            if (destroyed || generation != overlayGeneration ||
                cacheGeneration != PlaceOverlayCache.generation()) return@execute
            // A fresh overlay generation invalidates old in-memory shapes.
            overlayBounds.clear()
            val loaded = ArrayList<PlaceOverlayData>(places.size)
            val missing = ArrayList<com.roadconquest.app.data.PlaceDiscovery>()
            for (place in places) {
                val cached = if (UnitedStatesMunicipalBoundaries.applies(place)) {
                    PlaceOverlayCache.readOfficialUs(context, place)
                } else PlaceOverlayCache.read(context, place)
                if (!cached.cached) missing += place
                cached.data?.takeIf { it.containsRecordedVisit(place) }?.let(loaded::add)
            }
            loadedOverlayData = loaded
            loadedOverlayGeneration = generation
            loadedOverlayKind = kind
            loadedOverlayCacheGeneration = cacheGeneration
            postPlaceOverlay(generation, kind, loaded, cacheGeneration)
            var changesSincePost = 0
            var lastPostedAt = SystemClock.elapsedRealtime()
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
                val resolved = if (data == null || !us && place.kind == PlaceKind.TOWN) {
                    // A verified worldwide town polygon may have been stored while
                    // this less-trusted overlay fetch was running. write() correctly
                    // kept that polygon; render the accepted cache, not the rejected
                    // network candidate.
                    if (us) PlaceOverlayCache.readOfficialUs(context, place).data
                    else PlaceOverlayCache.read(context, place).data
                } else data
                if (resolved != null && resolved.containsRecordedVisit(place)) {
                    loaded += resolved
                    changesSincePost++
                    val now = SystemClock.elapsedRealtime()
                    // Do not build and upload an increasingly huge source after
                    // EVERY polygon. Keep the first result responsive, then
                    // batch incremental native-map updates (~1.5 seconds max).
                    if (loaded.size == 1 || changesSincePost >= 6 ||
                        now - lastPostedAt >= 1_500L
                    ) {
                        postPlaceOverlay(generation, kind, loaded, cacheGeneration)
                        changesSincePost = 0
                        lastPostedAt = now
                    }
                }
            }
            if (changesSincePost > 0 && !destroyed && generation == overlayGeneration) {
                postPlaceOverlay(generation, kind, loaded, cacheGeneration)
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
        val previousProvider = baseProvider
        baseProvider = BaseMapProviders.preferred(mapMode)
        if (previousProvider.isNotBlank() && previousProvider != baseProvider) {
            val message = when (baseProvider) {
                "local" -> "Map services unavailable. Your saved progress is still visible."
                "geoapify-tiles", "openfreemap-streets" -> "Map service unavailable. Using a backup street map."
                else -> null
            }
            message?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() }
        }
        val styleJson = BaseMapProviders.style(context, mapMode, baseProvider)
        mainHandler.removeCallbacks(retryBaseProvider)
        if (baseProvider != "openfreemap") mainHandler.postDelayed(retryBaseProvider, BaseMapProviders.retryInMs())
        map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
            if (destroyed || generation != styleGeneration) return@setStyle
            installPlaceOverlayLayers(style)
            installFogLayer(style)
            installNearbyExploration(style)
            installRecordedRouteLayer(style)
            installRoadLayer(style)
            installNearbyMarkerLayer(style)
            installCarLayer(style)
            updateCarLayer()
            // Initialize the camera from a fresh cached fix before the first
            // viewport/bitmap query. Otherwise first launch briefly loads a
            // world-scale fog and unrelated default-world tiles, then replaces
            // them in visible chunks after centering on the car.
            initialStyleReady?.let { ready -> initialStyleReady = null; ready() }
            refreshViewport()
            scheduleFogRender()
            onReady()
        }
    }

    private fun refreshBaseProvider() {
        if (destroyed) return
        if (!viewportActive) return
        if (baseProvider != BaseMapProviders.preferred(mapMode)) loadStyle { }
        else if (baseProvider != "openfreemap") {
            mainHandler.removeCallbacks(retryBaseProvider)
            mainHandler.postDelayed(retryBaseProvider, BaseMapProviders.retryInMs())
        }
    }

    fun updateCar(latitude: Double, longitude: Double, bearing: Double, ageMillis: Long = 0L) {
        if (destroyed || !viewportActive) return
        val now = SystemClock.elapsedRealtime()
        val previousVisual = liveLocation.current(now)
        if (!liveLocation.update(latitude, longitude, bearing, now, ageMillis)) return
        mainHandler.removeCallbacks(expireLocation)
        mainHandler.postDelayed(expireLocation, LiveLocation.MAX_AGE_MS - ageMillis)

        // Equal live fixes can still be newer and must extend expiry without another
        // MapLibre source upload. The exploration-only fog never uses live GPS
        // coordinates; persisted cell updates and camera changes schedule its redraw.
        val currentVisual = liveLocation.current(now)
        val positionChanged = previousVisual == null || currentVisual == null ||
            previousVisual.latitude != currentVisual.latitude ||
            previousVisual.longitude != currentVisual.longitude
        val bearingChanged = previousVisual == null || currentVisual == null ||
            previousVisual.bearing != currentVisual.bearing
        if (positionChanged) {
            updateCarLayer()
        } else if (bearingChanged) {
            // Heading-only updates still rotate the marker, but do not re-upload identical
            // GeoJSON or rebuild fog because neither depends on bearing.
            currentVisual?.let { fix ->
                (map.style?.getLayer(CAR_LAYER_ID) as? SymbolLayer)
                    ?.setProperties(iconRotate(fix.bearing.toFloat()))
            }
        }
        if (!centeredOnce) {
            // Initial centering must finish before the first fog/road viewport query.
            // Animating the startup camera loads an unnecessary intermediate area.
            centerOnCar(animated = false)
        }
    }

    fun centerOnCar(): Boolean = centerOnCar(animated = true)

    private fun centerOnCar(animated: Boolean): Boolean {
        if (destroyed) return false
        val fix = liveLocation.current(SystemClock.elapsedRealtime()) ?: return false
        centeredOnce = true
        val currentCamera = map.cameraPosition
        val camera = CameraPosition.Builder(currentCamera)
            .target(LatLng(fix.latitude, fix.longitude))
            .zoom(currentCamera.zoom.coerceIn(FogBitmapRenderer.CENTER_ZOOM, FogBitmapRenderer.MAX_ZOOM))
            .tilt(0.0)
            .build()
        val change = CameraUpdateFactory.newCameraPosition(camera)
        if (animated) map.animateCamera(change) else map.moveCamera(change)
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
        // Once the fix is already gone, another MapLibre marker upload cannot
        // change anything visible, so skip that work entirely.
        if (!liveLocation.clear()) return
        updateCarLayer()
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
        mainHandler.removeCallbacks(retryBaseProvider)
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
            refreshBaseProvider()
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
        // Unlocks recorded while the activity was stopped do not reach its
        // broadcast receiver; refresh the visible local tile overlay on return.
        refreshNearbyExploration()
    }

    fun refreshExploration() {
        loadedPlaceBounds = null
        refreshViewport(loadRoads = false)
        refreshNearbyExploration()
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
                        }
                        // A new camera footprint needs a fog capture even when the
                        // loaded grid cells are unchanged. captureFog() compares
                        // geographic keys before projecting them, so this check is cheap
                        // when there is nothing to redraw. Do not couple this to road
                        // source updates or to whether the cell list changed.
                        scheduleFogRender()
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
            // At country/region zoom levels many municipal shapes become broad
            // patches of color. Keep visited-town geography identifiable without
            // washing out streets and labels beneath the green fills.
            val townFillOpacity = if (mode == PlaceOverlayMode.TOWN) {
                fillOpacity(Expression.interpolate(Expression.linear(), Expression.zoom(),
                    Expression.stop(5f, 0.12f), Expression.stop(11f, 0.30f)))
            } else fillOpacity(0.30f)
            style.addLayer(
                FillLayer(overlayLayerId(mode), overlaySourceId(mode)).withProperties(
                    fillColor(color),
                    townFillOpacity,
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
                    if (mode == PlaceOverlayMode.TOWN) {
                        lineOpacity(Expression.interpolate(Expression.linear(), Expression.zoom(),
                            Expression.stop(5f, 0.56f), Expression.stop(11f, 0.90f)))
                    } else lineOpacity(0.90f),
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
        data: List<PlaceOverlayData>,
        expectedCacheGeneration: Long
    ) {
        // Polygon conversion can be expensive for many visited municipalities.
        // Build immutable GeoJSON on overlayExecutor instead of blocking the map's UI thread.
        val viewport = if (kind == PlaceKind.TOWN && data.size >= 200) visibleTownViewport else null
        val snapshot = if (viewport != null) {
            data.filter { place ->
                val key = "${place.key}:${place.geometryJson.hashCode()}"
                viewport.intersects(overlayBounds.getOrPut(key) {
                    OverlayViewport.ofGeometry(place.geometryJson)
                })
            }
        } else data.toList()
        val keys = snapshot.map { "${it.key}:${it.geometryJson.hashCode()}" }
        // This gets called after normal activity refreshes as well as during
        // incremental downloads. If the complete geometry set is already drawn,
        // skip costly JTS polygon clipping and GeoJSON serialization altogether.
        // These state fields are written on the UI thread and read here on the
        // serial overlay executor; volatility keeps the early check up to date.
        if (keys == shownOverlayKeys &&
            expectedCacheGeneration == shownOverlayCacheGeneration
        ) return
        // Existing boundaries can overlap due to mismatched or historical OSM
        // polygons. Partition only town shapes so their translucent fills never
        // stack or paint an overlapping boundary twice.
        // Keep official Census polygons unchanged in storage. Where different
        // Census municipal layers overlap, partition only the rendered fills
        // so an incorporated city and its parent subdivision cannot double shade.
        val officialUs = if (kind == com.roadconquest.app.data.PlaceKind.TOWN) {
            snapshot.filter { UnitedStatesMunicipalBoundaries.isUnitedStatesKey(it.key) }
        } else emptyList()
        val other = if (officialUs.isNotEmpty()) {
            snapshot.filterNot { UnitedStatesMunicipalBoundaries.isUnitedStatesKey(it.key) }
        } else snapshot
        val otherPartition = if (kind == com.roadconquest.app.data.PlaceKind.TOWN &&
            other.isNotEmpty()
        ) TownOverlayPartition.render(other) else null
        val censusLayers = officialUs.mapNotNull {
            when {
                "|census-4-" in it.key -> 4
                "|census-3-" in it.key -> 3
                "|census-1-" in it.key -> 1
                else -> null
            }
        }.toSet()
        // A single layer cannot overlap itself in normal Census geography.
        // Avoid unnecessary polygon differences on ordinary single-layer maps.
        val officialPartition = if (censusLayers.size > 1) {
            TownOverlayPartition.renderOfficialUs(officialUs)
        } else null
        val officialFills = officialPartition?.fills ?: overlayFeatureCollection(officialUs)
        val officialBorders = officialPartition?.outlines ?:
            TownOverlayPartition.authoritativeOutlines(officialUs)
        val fillFeatures = when {
            officialUs.isNotEmpty() && otherPartition != null ->
                mergeOverlayCollections(officialFills, otherPartition.fills)
            officialUs.isNotEmpty() -> officialFills
            else -> otherPartition?.fills ?: overlayFeatureCollection(snapshot)
        }
        val boundaries = when {
            officialUs.isNotEmpty() && otherPartition != null ->
                mergeOverlayCollections(officialBorders, otherPartition.outlines)
            officialUs.isNotEmpty() -> officialBorders
            else -> otherPartition?.outlines ?: overlayBoundaryFeatureCollection(snapshot)
        }
        mainHandler.post {
            if (destroyed || generation != overlayGeneration || overlayMode.kind != kind ||
                expectedCacheGeneration != PlaceOverlayCache.generation()) return@post
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
        // Native GeoJSON tiling otherwise discards short bends at regional zoom
        // (default tolerance 0.375), leaving real driven segments as dots or gaps.
        // Keep original saved coordinates; this affects drawing only, never scoring.
        style.addSource(GeoJsonSource(
            ROAD_SOURCE_ID, displayedRoadFeatures, GeoJsonOptions().withTolerance(0f)
        ))
        appliedRoadColor = Prefs.roadColor(context)
        val color = Cosmetics.roadColor(context).argb

        // At regional zoom a short road is less than one pixel long: rounded
        // caps render it as a dot. Butt caps and a legible minimum width keep
        // the existing geometry visible without inventing connecting roads.
        val regional = LineLayer("${ROAD_LAYER_ID}-regional", ROAD_SOURCE_ID).withProperties(
            lineColor(color), lineOpacity(242f / 255f),
            lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(6f, 2f), Expression.stop(9f, 2.75f))),
            lineCap(Property.LINE_CAP_BUTT), lineJoin(Property.LINE_JOIN_ROUND)
        )
        regional.setMinZoom(FogBitmapRenderer.MIN_ROAD_ZOOM.toFloat())
        regional.setMaxZoom(9f)
        style.addLayer(regional)

        val detailed = LineLayer(ROAD_LAYER_ID, ROAD_SOURCE_ID).withProperties(
            lineColor(color), lineOpacity(242f / 255f),
            lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(9f, 2.75f), Expression.stop(12f, 4f))),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
        )
        detailed.setMinZoom(9f)
        style.addLayer(detailed)
    }

    private fun installFogRaster(
        style: Style, sourceId: String, layerId: String, quad: LatLngQuad,
        image: Bitmap, opacity: Float
    ) {
        style.addSource(ImageSource(sourceId, quad, image))
        style.addLayer(RasterLayer(layerId, sourceId).withProperties(
            rasterOpacity(opacity), rasterFadeDuration(0f)
        ).apply { setRasterOpacityTransition(TransitionOptions(0L, 0L)) })
    }

    private fun installFogLayer(style: Style) {
        worldFogHasExploration = false
        activeDetailedFogAlternate = false
        detailedFogCoordinates = null
        regionalFogCoordinates = null
        showingDetailedFog = false
        showingRegionalFog = false
        showingOverviewWorldFog = false
        installedRegionalFogKey = null
        // Keep a ready native world layer behind the detailed viewport. A fast pinch can
        // outrun even a padded bitmap; it must never reveal an unrendered rectangle.
        installFogRaster(style, WORLD_FOG_SOURCE_ID, WORLD_FOG_LAYER_ID, worldFogQuad(), overviewFog,
            if (fogEnabled && map.cameraPosition.zoom >= FogBitmapRenderer.MIN_FOG_REVEAL_ZOOM) 1f else 0f)
        // A native zoom-limited world layer stays present through animated zooms.
        // Unlike a Java camera callback, its visibility follows each rendered zoom
        // frame and cannot lag the transition to a world-scale view.
        style.addLayer(RasterLayer(WORLD_OVERVIEW_FOG_LAYER_ID, WORLD_FOG_SOURCE_ID)
            .withProperties(rasterOpacity(if (fogEnabled) 1f else 0f), rasterFadeDuration(0f))
            .apply {
                setMaxZoom(FogBitmapRenderer.MIN_FOG_REVEAL_ZOOM.toFloat())
                setRasterOpacityTransition(TransitionOptions(0L, 0L))
            })
        val transparent = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        // Kept at a broader map scale while idle. Only one fog raster is visible:
        // stacking world/regional/detail would darken the unexplored map and cover holes.
        installFogRaster(style, REGIONAL_FOG_SOURCE_ID, REGIONAL_FOG_LAYER_ID, currentFogQuad(), transparent, 0f)
        installFogRaster(style, FOG_SOURCE_ID, FOG_LAYER_ID, currentFogQuad(), transparent, 0f)
        // Prepare the NEXT georeferenced raster while it is invisible.
        // Updating pixels and corners in place on the visible ImageSource
        // displayed the old clearing at the new coordinates for a frame.
        installFogRaster(style, FOG_ALT_SOURCE_ID, FOG_ALT_LAYER_ID, currentFogQuad(), transparent, 0f)
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
        // provisional trace so the map never appears to have a random hole while the online matcher is
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
        // Prefer a ready geographically anchored regional image throughout a
        // rapid pinch. Swapping detailed ImageSource textures mid-animation
        // can expose an opaque previously visited cell for one Android 12
        // frame even while the detailed quad technically covers the screen.
        // Never use a backup from an older exploration revision.
        val regionalReady = fogEnabled &&
            installedRegionalFogKey?.let {
                it.styleGeneration == styleGeneration && it.visitedRevision == placeDataRevision
            } == true &&
            regionalFogCoordinates?.let { region ->
                map.projection.toScreenLocations(region, fogCoverageScreen)
                FogCoverage.coversViewport(
                    fogCoverageScreen, mapView.width, mapView.height,
                    if (cameraMoving) FOG_MOVING_COVERAGE_MARGIN_FRACTION else 0.0
                )
            } == true
        // Keep the same regional texture until the replacement detailed bitmap
        // has actually finished rendering at rest. Avoid a one-frame handoff
        // back to stale detailed fog between camera-idle and the render result.
        val preferRegional = regionalReady &&
            (cameraMoving || (showingRegionalFog && fogRunning))
        val detailed = fogEnabled && !preferRegional &&
            cameraPosition.zoom >= FogBitmapRenderer.MIN_FOG_REVEAL_ZOOM &&
            coordinates != null && run {
                map.projection.toScreenLocations(coordinates, fogCoverageScreen)
                FogCoverage.coversViewport(
                    fogCoverageScreen,
                    mapView.width,
                    mapView.height,
                    if (cameraMoving) FOG_MOVING_COVERAGE_MARGIN_FRACTION else 0.0
                )
            }
        val regional = regionalReady && (preferRegional || !detailed)
        val overview = fogEnabled && cameraPosition.zoom < FogBitmapRenderer.MIN_FOG_REVEAL_ZOOM
        val changed = detailed != showingDetailedFog || regional != showingRegionalFog ||
            overview != showingOverviewWorldFog
        if (!force && !changed) return
        val detail = map.style?.getLayer(
            if (activeDetailedFogAlternate) FOG_ALT_LAYER_ID else FOG_LAYER_ID
        ) as? RasterLayer
        val inactive = map.style?.getLayer(
            if (activeDetailedFogAlternate) FOG_LAYER_ID else FOG_ALT_LAYER_ID
        ) as? RasterLayer
        val worldLayer = map.style?.getLayer(WORLD_FOG_LAYER_ID) as? RasterLayer
        val overviewLayer = map.style?.getLayer(WORLD_OVERVIEW_FOG_LAYER_ID) as? RasterLayer
        val regionalLayer = map.style?.getLayer(REGIONAL_FOG_LAYER_ID) as? RasterLayer
        showingDetailedFog = detailed
        showingRegionalFog = regional
        showingOverviewWorldFog = overview
        overviewLayer?.setProperties(rasterOpacity(if (fogEnabled) 1f else 0f))
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
            // Below the reveal threshold the native overview is already active.
            // Do not double-darken it with the regular fallback world raster.
            worldLayer?.setProperties(rasterOpacity(if (overview) 0f else 1f))
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
        (map.style?.getSource(ROAD_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(features)
        // Blue roads render in their own source and do not clear exploration-only fog.
    }

    private fun setDisplayedPlaces(places: DoubleArray) {
        if (displayedPlaces.contentEquals(places)) return
        displayedPlaces = places
        // Stored one-mile grid centers are authoritative; legacy samples were backfilled
        // once into this table. Scoring and road matching remain independent.
        savedGridCoordinates = FogGrid.uniqueVisitedCorners(places)
        placeDataRevision++
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
        val captureResult = captureFog() ?: return
        if (captureResult is FogCaptureResult.Unchanged) {
            if (captureResult.world && savedGridCoordinates.isEmpty() && !worldFogHasExploration) {
                updateFogCoverage(force = true)
            } else if (!cameraMoving) {
                scheduleRegionalFogRender()
            }
            return
        }
        val capture = (captureResult as FogCaptureResult.Changed).capture
        // An identical ready world raster was installed with the map style.
        // Keep its GPU texture untouched while zooming if there are no cleared
        // grid cells. But after a deletion, replace any previously revealed
        // world raster once to erase the old exploration mask as required.
        if (capture.world && savedGridCoordinates.isEmpty() && !worldFogHasExploration) {
            installedFogKey = capture.key
            updateFogCoverage(force = true)
            return
        }
        // An unchanged raster is rejected in captureFog() before projecting
        // potentially thousands of visited-cell corners.
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
                        if (source != null) {
                            installedFogKey = capture.key
                            if (capture.world) {
                                worldFogHasExploration = capture.request.gridScreen.isNotEmpty()
                            }
                        }
                        if (!capture.world && source != null) {
                            activeDetailedFogAlternate = nextAlternate
                            detailedFogCoordinates = fogQuadCoordinates(capture.quad)
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
        val captureResult = captureFog(regional = true) ?: return
        if (captureResult is FogCaptureResult.Unchanged) return
        val capture = (captureResult as FogCaptureResult.Changed).capture
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
                            regionalFogCoordinates = fogQuadCoordinates(capture.quad)
                            updateFogCoverage(force = true)
                        }
                    }
                    reusableRegionalFogBitmap = rendered
                }
            }
        }
    }

    private fun fogQuadCoordinates(quad: LatLngQuad) = doubleArrayOf(
        quad.topLeft.latitude, quad.topLeft.longitude,
        quad.topRight.latitude, quad.topRight.longitude,
        quad.bottomRight.latitude, quad.bottomRight.longitude,
        quad.bottomLeft.latitude, quad.bottomLeft.longitude
    )

    private fun captureFog(regional: Boolean = false): FogCaptureResult? {
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
        val key = FogKey(
            styleGeneration, world, raster.westMeters, raster.northMeters,
            raster.metersPerPixel, size, placeDataRevision, null
        )
        // Most pan callbacks keep the exact same snapped north-up raster.
        // Checking the cheap scalar key first avoids reprojecting all unlocked
        // mile cells and reallocating a screen-coordinate array unnecessarily.
        if (key == (if (regional) installedRegionalFogKey else installedFogKey)) {
            return FogCaptureResult.Unchanged(world)
        }
        val corners = savedGridCoordinates
        val screen = raster.projectGridCorners(corners)
        val geographic = raster.corners()
        val quad = LatLngQuad(
            LatLng(geographic[0], geographic[1]),
            LatLng(geographic[2], geographic[3]),
            LatLng(geographic[4], geographic[5]),
            LatLng(geographic[6], geographic[7])
        )
        return FogCaptureResult.Changed(FogCapture(styleGeneration, quad, FogBitmapRenderer.Request(
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
        ), world = world, key = key))
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

    private sealed interface FogCaptureResult {
        data class Changed(val capture: FogCapture) : FogCaptureResult
        data class Unchanged(val world: Boolean) : FogCaptureResult
    }

    private data class FogCapture(
        val styleGeneration: Int,
        val quad: LatLngQuad,
        val request: FogBitmapRenderer.Request,
        val world: Boolean,
        val key: FogKey
    )

    fun destroy() {
        destroyed = true
        BaseMapProviders.removeListener(providerListener)
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
        nearbyMarkerImages.values.forEach { it.recycle() }
        nearbyMarkerImages.clear()
        overlayGeneration++
        // The overlay collections are owned by overlayExecutor. Do not clear
        // its HashMap on this UI thread while an in-flight polygon conversion
        // is reading it; shutdown and the renderer's lifecycle release them.
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
        private const val NEARBY_VISITED_SOURCE_ID = "roadconquest-nearby-visited"
        private const val NEARBY_UNVISITED_SOURCE_ID = "roadconquest-nearby-unvisited"
        private const val NEARBY_NUMBERS_SOURCE_ID = "roadconquest-nearby-numbers"
        private const val NEARBY_VISITED_LAYER_ID = "roadconquest-nearby-visited-fill"
        private const val NEARBY_UNVISITED_LAYER_ID = "roadconquest-nearby-unvisited-fill"
        private const val NEARBY_NUMBERS_LAYER_ID = "roadconquest-nearby-number-labels"
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
        private const val WORLD_OVERVIEW_FOG_LAYER_ID = "roadconquest-world-fog-overview-raster"
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
        // Online matching is paced to seven minutes. Keep the provisional raw-GPS
        // trace long enough to bridge normal batching and an occasional retry,
        // without letting permanently unmatched history clutter the entire map.
        // Display only; never changes matching requests or confirmed-road rewards.
        private const val PENDING_ROUTE_MAX_AGE_MS = 15 * 60_000L
        private const val FOG_RENDER_INTERVAL_MS = 120L
        private const val FOG_MOVING_COVERAGE_MARGIN_FRACTION = 0.05
        private const val STARTING_LOCATION_ZOOM = 15.0
    }
}
