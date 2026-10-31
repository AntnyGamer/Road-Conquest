package com.roadconquest.app.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LinearRing
import org.locationtech.jts.geom.util.GeometryFixer
import kotlin.math.*

enum class TownMedal(val label: String, val color: Int) {
    NONE("No badge yet", 0xFF66758B.toInt()),
    BRONZE("Bronze", 0xFFBF814B.toInt()),
    SILVER("Silver", 0xFFB9C9DC.toInt()),
    GOLD("Gold", 0xFFF3C651.toInt())
}

data class TownCompletionProgress(
    val percent: Int,
    val medal: TownMedal,
    val latitude: Double,
    val longitude: Double
)

/** Measures the part of the ORIGINAL town boundary covered by unlocked fog tiles.
 * Equal-area latitude coordinates keep large/northern towns honest. Edge tiles
 * count only for their overlap, holes do not count, and no road catalog is needed.
 * All geometry and local queries run on the overlay worker, never the UI thread.
 */
internal class TownCompletion {
    private val factory = GeometryFactory()
    private data class Shape(val geometry: Geometry, val latitude: Double, val longitude: Double,
        var revision: Pair<Long, Long>? = null, var progress: TownCompletionProgress? = null)
    private val shapes = LinkedHashMap<String, Shape>(32, 0.75f, true)

    private fun shape(json: String): Shape {
        shapes[json]?.let { return it }
        val parsed = readShape(JSONObject(json))
        // A count-only cache can retain hundreds of huge municipal geometries.
        // Bound both the number of towns and the actual coordinate workload.
        if (parsed.geometry.numPoints <= 50_000) {
            shapes[json] = parsed
            var points = shapes.values.sumOf { it.geometry.numPoints }
            val iterator = shapes.entries.iterator()
            while (shapes.size > 32 || points > 50_000) {
                val oldest = iterator.next()
                points -= oldest.value.geometry.numPoints
                iterator.remove()
            }
        }
        return parsed
    }

    fun anchor(geometryJson: String): Pair<Double, Double>? = runCatching {
        shape(geometryJson).let { it.latitude to wrap(it.longitude) }
    }.getOrNull()

    fun calculate(
        geometryJson: String,
        revision: Pair<Long, Long>? = null,
        explored: (north: Double, east: Double, south: Double, west: Double) -> DoubleArray
    ): TownCompletionProgress? = runCatching {
        val shape = shape(geometryJson)
        if (revision != null && shape.revision == revision) return shape.progress
        val geometry = shape.geometry
        var covered = 0.0
        val corners = DoubleArray(8)
        for (index in 0 until geometry.numGeometries) {
            if (Thread.currentThread().isInterrupted) return null
            val polygon = geometry.getGeometryN(index)
            val bounds = polygon.envelopeInternal
            val centerLongitude = bounds.centre().x
            val prepared = org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(polygon)
            // Tile centers can lie just outside the town while their footprint overlaps it.
            val south = latitude(bounds.minY) - 0.02
            val north = latitude(bounds.maxY) + 0.02
            val west = bounds.minX - 0.03
            val east = bounds.maxX + 0.03
            val samples = if (east - west >= 360.0) explored(north, 180.0, south, -180.0)
                else explored(north, wrap(east), south, wrap(west))
            val seen = HashSet<Long>()
            for (i in 0 until samples.size - 1 step 2) {
                val tile = FogGrid.cell(samples[i], samples[i + 1]) ?: continue
                if (!seen.add(tile.key)) continue
                FogGrid.writeCorners(tile, corners, 0)
                val tileWest = near(corners[1], centerLongitude)
                val tileEast = tileWest + (corners[3] - corners[1])
                val tileBounds = org.locationtech.jts.geom.Envelope(
                    tileWest, tileEast, sin(Math.toRadians(corners[4])), sin(Math.toRadians(corners[0])))
                if (bounds.intersects(tileBounds)) {
                    val tileShape = factory.toGeometry(tileBounds)
                    covered += when {
                        prepared.covers(tileShape) -> tileShape.area
                        prepared.intersects(tileShape) -> polygon.intersection(tileShape).area
                        else -> 0.0
                    }
                }
            }
        }
        val fraction = (covered / geometry.area).coerceIn(0.0, 1.0)
        val percent = if (fraction >= 1.0 - 1e-9) 100 else floor(fraction * 100.0 + 1e-9).toInt().coerceAtMost(99)
        TownCompletionProgress(percent, medalFor(percent), shape.latitude, wrap(shape.longitude)).also {
            shape.revision = revision
            shape.progress = it
        }
    }.getOrNull()

    private fun readShape(json: JSONObject): Shape {
        fun ring(points: JSONArray, anchor: Double?): LinearRing {
            var previous = anchor
            val coords = ArrayList<Coordinate>(points.length() + 1)
            for (i in 0 until points.length()) {
                val point = points.getJSONArray(i)
                val longitude = point.getDouble(0)
                val latitude = point.getDouble(1)
                require(longitude.isFinite() && longitude in -180.0..180.0 &&
                    latitude.isFinite() && latitude in -85.05112878..85.05112878)
                val x = previous?.let { near(longitude, it) } ?: longitude
                coords += Coordinate(x, sin(Math.toRadians(latitude)))
                previous = x
            }
            require(coords.size >= 3)
            if (!coords.first().equals2D(coords.last())) coords += Coordinate(coords.first())
            return factory.createLinearRing(coords.toTypedArray())
        }
        fun polygon(rings: JSONArray): Geometry {
            val shell = ring(rings.getJSONArray(0), null)
            val anchor = shell.envelopeInternal.centre().x
            return GeometryFixer.fix(factory.createPolygon(shell,
                Array(rings.length() - 1) { ring(rings.getJSONArray(it + 1), anchor) }))
        }
        val coords = json.getJSONArray("coordinates")
        val pieces = when (json.getString("type")) {
            "Polygon" -> listOf(polygon(coords))
            "MultiPolygon" -> List(coords.length()) { polygon(coords.getJSONArray(it)) }
            else -> error("Town boundary must be polygonal")
        }
        // Union prevents overlapping source components from counting twice.
        val geometry = org.locationtech.jts.operation.union.UnaryUnionOp.union(pieces)
        require(!geometry.isEmpty && geometry.area > 0.0)
        val largest = (0 until geometry.numGeometries).map { geometry.getGeometryN(it) }.maxBy { it.area }
        // A centroid may fall outside a concave town or inside a lake. InteriorPoint cannot.
        val center = largest.interiorPoint.coordinate
        return Shape(geometry, latitude(center.y), center.x)
    }

    private fun latitude(y: Double) = Math.toDegrees(asin(y.coerceIn(-1.0, 1.0)))
    private fun near(longitude: Double, anchor: Double) = longitude + round((anchor - longitude) / 360.0) * 360.0
    private fun wrap(longitude: Double) = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    companion object {
        fun medalFor(percent: Int): TownMedal = when {
            percent >= 100 -> TownMedal.GOLD
            percent >= 50 -> TownMedal.SILVER
            percent >= 25 -> TownMedal.BRONZE
            else -> TownMedal.NONE
        }
        fun imageId(progress: TownCompletionProgress) = "town-completion-${progress.medal.name}-${progress.percent}"

        /** Local artwork also works on the offline style without downloading glyphs. */
        fun badge(progress: TownCompletionProgress): Bitmap {
            val bitmap = Bitmap.createBitmap(88, 104, Bitmap.Config.ARGB_8888).apply {
                // MapLibre derives image pixelRatio from bitmap density. Keep
                // the medal and 13 dp percentage readable on high-density phones.
                density = android.util.DisplayMetrics.DENSITY_DEFAULT
            }
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            if (progress.medal != TownMedal.NONE) {
                paint.color = 0xFF28466B.toInt()
                canvas.drawPath(Path().apply {
                    moveTo(25f, 45f); lineTo(18f, 75f); lineTo(32f, 69f); lineTo(40f, 78f); lineTo(46f, 46f); close()
                    moveTo(44f, 46f); lineTo(49f, 78f); lineTo(58f, 69f); lineTo(71f, 75f); lineTo(64f, 45f); close()
                }, paint)
                paint.color = 0xFF16283D.toInt()
                canvas.drawCircle(44f, 32f, 29f, paint)
                paint.color = progress.medal.color
                canvas.drawCircle(44f, 32f, 26f, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = 0xAAFFFFFF.toInt()
                canvas.drawCircle(44f, 32f, 21f, paint)
                paint.style = Paint.Style.FILL; paint.color = 0xFF24324A.toInt()
                val star = Path()
                for (i in 0..9) {
                    val angle = -PI / 2 + i * PI / 5
                    val radius = if (i % 2 == 0) 14 else 6
                    val x = 44f + (cos(angle) * radius).toFloat()
                    val y = 32f + (sin(angle) * radius).toFloat()
                    if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
                }
                star.close(); canvas.drawPath(star, paint)
            }
            paint.color = 0xF21C2C43.toInt()
            canvas.drawRoundRect(7f, 79f, 81f, 103f, 12f, 12f, paint)
            paint.color = Color.WHITE; paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = 20f; paint.textAlign = Paint.Align.CENTER
            canvas.drawText("${progress.percent}%", 44f, 98f, paint)
            return bitmap
        }
    }
}
