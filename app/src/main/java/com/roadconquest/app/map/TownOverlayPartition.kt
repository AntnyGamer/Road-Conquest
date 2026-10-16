package com.roadconquest.app.map

import org.json.JSONArray
import org.json.JSONObject
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.LinearRing
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.util.GeometryFixer
import org.locationtech.jts.operation.union.UnaryUnionOp

/**
 * Turn individually translucent, potentially overlapping town polygons into
 * mutually exclusive map features. This is a VISUAL partition only: original
 * visited places, rewards, dates and cached municipal boundaries stay unchanged.
 *
 * Small municipalities take precedence over enclosing/overlapping larger ones.
 * Adjacent polygons still share their true borders; no pixel can be filled twice.
 * Work runs on MapRenderer's background overlay executor, never the map/UI thread.
 */
internal object TownOverlayPartition {
    internal data class Result(
        val fills: String,
        val outlines: String,
        val shown: List<PlaceOverlayData>
    )

    private val factory = GeometryFactory()

    fun render(data: List<PlaceOverlayData>): Result = render(data, officialUs = false)

    /**
     * If Census incorporated/consolidated places overlap county subdivisions,
     * show only one translucent fill. Prioritize incorporated municipalities
     * without changing any authoritative cached source polygon or reward.
     */
    fun renderOfficialUs(data: List<PlaceOverlayData>): Result = render(data, officialUs = true)

    private fun render(data: List<PlaceOverlayData>, officialUs: Boolean): Result {
        data class Candidate(val overlay: PlaceOverlayData, val geometry: Geometry,
                             val bounds: org.locationtech.jts.geom.Envelope = geometry.envelopeInternal)
        val candidates = data.distinctBy(PlaceOverlayData::key).mapNotNull { overlay ->
            val geometry = runCatching {
                polygonal(GeometryFixer.fix(decode(JSONObject(overlay.geometryJson))))
            }.getOrNull()
            geometry?.takeUnless { it.isEmpty }?.let { Candidate(overlay, it) }
        }.sortedWith(compareBy<Candidate> {
            if (!officialUs) 0 else when {
                "|census-4-" in it.overlay.key -> 0
                "|census-3-" in it.overlay.key -> 1
                "|census-1-" in it.overlay.key -> 2
                else -> 3
            }
        }.thenBy { it.geometry.area }.thenBy { it.overlay.key })

        val placed = ArrayList<Candidate>(candidates.size)
        for (candidate in candidates) {
            // Each candidate's immutable geometry envelope is computed once.
            // Reuse it across every later candidate's overlap checks.
            val overlapping = placed.asSequence().filter {
                it.bounds.intersects(candidate.bounds)
            }.map(Candidate::geometry).toList()
            // Each candidate was already fixed and reduced to polygons above.
            // With no geometric overlap, re-running the costly topology fixer
            // cannot change the visible result and needlessly reconstructs it.
            val clipped = if (overlapping.isEmpty()) candidate.geometry else {
                runCatching {
                    val remaining = if (overlapping.size == 1) {
                        candidate.geometry.difference(overlapping[0])
                    } else {
                        candidate.geometry.difference(UnaryUnionOp.union(overlapping))
                    }
                    polygonal(GeometryFixer.fix(remaining))
                }.getOrNull() ?: continue
            }
            if (!clipped.isEmpty && clipped.area > 0.0) {
                placed += Candidate(candidate.overlay, clipped)
            }
        }

        val fills = JSONArray()
        val outlines = ArrayList<Geometry>(placed.size)
        val shown = ArrayList<PlaceOverlayData>(placed.size)
        for (candidate in placed) {
            val shape = encodePolygonal(candidate.geometry) ?: continue
            val overlay = candidate.overlay.copy(geometryJson = shape.toString())
            fills.put(overlay.featureJson())
            shown += overlay
            outlines += candidate.geometry.boundary
        }

        // Node and dissolve coincident shared borders: adjacent towns have only
        // one thin outline, not doubled/darkened boundary strokes.
        val lines = JSONArray()
        if (outlines.isNotEmpty()) {
            runCatching { collectLines(UnaryUnionOp.union(outlines), lines) }
                .onFailure { lines.removeAll(); outlines.forEach { collectLines(it, lines) } }
        }
        val boundaryFeatures = JSONArray()
        if (lines.length() > 0) {
            boundaryFeatures.put(JSONObject()
                .put("type", "Feature")
                .put("properties", JSONObject())
                .put("geometry", JSONObject()
                    .put("type", "MultiLineString")
                    .put("coordinates", lines)))
        }
        return Result(collection(fills), collection(boundaryFeatures), shown)
    }

    /**
     * Dissolve coincident borders when no cross-layer visual partition is necessary.
     */
    fun authoritativeOutlines(data: List<PlaceOverlayData>): String {
        val edges = data.mapNotNull { overlay ->
            runCatching {
                polygonal(GeometryFixer.fix(decode(JSONObject(overlay.geometryJson)))).boundary
            }.getOrNull()?.takeUnless { it.isEmpty }
        }
        val lines = JSONArray()
        if (edges.isNotEmpty()) {
            runCatching { collectLines(UnaryUnionOp.union(edges), lines) }
                .onFailure { edges.forEach { collectLines(it, lines) } }
        }
        val features = JSONArray()
        if (lines.length() > 0) {
            features.put(JSONObject()
                .put("type", "Feature")
                .put("properties", JSONObject())
                .put("geometry", JSONObject()
                    .put("type", "MultiLineString")
                    .put("coordinates", lines)))
        }
        return collection(features)
    }

    private fun decode(json: JSONObject): Geometry {
        val polygons = json.getJSONArray("coordinates")
        return when (json.getString("type")) {
            "Polygon" -> readPolygon(polygons)
            "MultiPolygon" -> factory.createMultiPolygon(Array(polygons.length()) { index ->
                readPolygon(polygons.getJSONArray(index))
            })
            else -> error("Unsupported town geometry")
        }
    }

    private fun readPolygon(rings: JSONArray): Polygon {
        require(rings.length() > 0)
        val shell = readRing(rings.getJSONArray(0))
        val holes = Array(rings.length() - 1) { index -> readRing(rings.getJSONArray(index + 1)) }
        return factory.createPolygon(shell, holes)
    }

    private fun readRing(json: JSONArray): LinearRing {
        val coords = Array(json.length()) { index ->
            val point = json.getJSONArray(index)
            val longitude = point.getDouble(0)
            val latitude = point.getDouble(1)
            require(longitude.isFinite() && latitude.isFinite() &&
                longitude in -180.0..180.0 && latitude in -90.0..90.0)
            Coordinate(longitude, latitude)
        }
        require(coords.size >= 3)
        // Most provider rings are already closed: pass the array directly to JTS
        // instead of allocating an ArrayList and converting it back to an array.
        val ring = if (coords.first().equals2D(coords.last())) coords
            else Array(coords.size + 1) { index ->
                if (index < coords.size) coords[index] else Coordinate(coords.first())
            }
        return factory.createLinearRing(ring)
    }

    private fun polygonal(source: Geometry): Geometry {
        val polygons = ArrayList<Polygon>()
        fun flatten(geometry: Geometry) {
            when (geometry) {
                is Polygon -> if (!geometry.isEmpty && geometry.area > 0.0) polygons += geometry
                is GeometryCollection -> for (i in 0 until geometry.numGeometries) {
                    flatten(geometry.getGeometryN(i))
                }
            }
        }
        flatten(source)
        return factory.createMultiPolygon(polygons.toTypedArray())
    }

    private fun encodePolygonal(geometry: Geometry): JSONObject? {
        val polygons = (0 until geometry.numGeometries)
            .mapNotNull { geometry.getGeometryN(it) as? Polygon }
        if (polygons.isEmpty()) return null
        return if (polygons.size == 1) {
            JSONObject().put("type", "Polygon").put("coordinates", polygonCoordinates(polygons[0]))
        } else {
            val coordinates = JSONArray()
            polygons.forEach { coordinates.put(polygonCoordinates(it)) }
            JSONObject().put("type", "MultiPolygon").put("coordinates", coordinates)
        }
    }

    private fun polygonCoordinates(polygon: Polygon): JSONArray {
        val rings = JSONArray().put(coordinates(polygon.exteriorRing))
        for (i in 0 until polygon.numInteriorRing) rings.put(coordinates(polygon.getInteriorRingN(i)))
        return rings
    }

    private fun coordinates(line: LineString): JSONArray {
        val result = JSONArray()
        line.coordinates.forEach { coordinate ->
            result.put(JSONArray().put(coordinate.x).put(coordinate.y))
        }
        return result
    }

    private fun collectLines(shape: Geometry, lines: JSONArray) {
        when (shape) {
            is LineString -> if (!shape.isEmpty && shape.numPoints >= 2) lines.put(coordinates(shape))
            is GeometryCollection -> for (i in 0 until shape.numGeometries) {
                collectLines(shape.getGeometryN(i), lines)
            }
        }
    }

    private fun collection(features: JSONArray): String =
        JSONObject().put("type", "FeatureCollection").put("features", features).toString()

    // API 31+ JSONArray does not provide a clear() operation.
    private fun JSONArray.removeAll() {
        while (length() > 0) remove(length() - 1)
    }
}
