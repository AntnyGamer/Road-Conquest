package com.roadconquest.app.map

import kotlin.math.*

/**
 * One north-up, world-anchored Mercator raster for all cloud layers and visited cells.
 * No camera animation deltas, previous phase, screen quad inversions, or accumulated
 * floating point translations enter the position of a visited square.
 */
internal class FogGeoRaster private constructor(
    val westMeters: Double,
    val northMeters: Double,
    val metersPerPixel: Double,
    val width: Int,
    val height: Int
) {
    val eastMeters get() = westMeters + width * metersPerPixel
    val southMeters get() = northMeters - height * metersPerPixel
    val centerLongitude get() = Math.toDegrees((westMeters + eastMeters) * 0.5 / RADIUS)
    private val centerX get() = (westMeters + eastMeters) * 0.5

    /** Top-left, top-right, bottom-right, bottom-left latitude/longitude pairs. */
    fun corners(): DoubleArray = doubleArrayOf(
        latitude(northMeters), longitude(westMeters),
        latitude(northMeters), longitude(eastMeters),
        latitude(southMeters), longitude(eastMeters),
        latitude(southMeters), longitude(westMeters)
    )

    /**
     * Each visited tile is projected by the exact inverse of the raster's geographic
     * rectangle. MapLibre then georeferences that rectangle as a single image.
     */
    fun project(gridCorners: DoubleArray): DoubleArray {
        val result = DoubleArray(gridCorners.size)
        for (i in gridCorners.indices step 2) {
            result[i] = projectedLongitude(gridCorners[i + 1])
            result[i + 1] = projectedLatitude(gridCorners[i])
        }
        return result
    }

    /**
     * FogGrid.visitedCorners() always emits north-west, north-east,
     * south-east, south-west. Those four vertices share just two latitudes
     * and two longitudes. Compute each Mercator conversion once per cell;
     * unlike filtering/cropping this preserves every projected coordinate.
     */
    fun projectGridCorners(corners: DoubleArray): DoubleArray {
        require(corners.size % 8 == 0)
        val result = DoubleArray(corners.size)
        for (i in corners.indices step 8) {
            // Wrap the square as a unit. Wrapping each edge independently can
            // put -180 degrees at the right of a world raster and its eastern
            // neighbor at the left, erasing a strip almost one world wide.
            val westX = RADIUS * Math.toRadians(corners[i + 1])
            val eastX = RADIUS * Math.toRadians(corners[i + 3])
            val offset = floor((centerX - (westX + eastX) * 0.5) / WORLD_METERS + 0.5) * WORLD_METERS
            val west = (westX + offset - westMeters) / metersPerPixel
            val east = (eastX + offset - westMeters) / metersPerPixel
            val north = projectedLatitude(corners[i])
            val south = projectedLatitude(corners[i + 4])
            result[i] = west
            result[i + 1] = north
            result[i + 2] = east
            result[i + 3] = north
            result[i + 4] = east
            result[i + 5] = south
            result[i + 6] = west
            result[i + 7] = south
        }
        return result
    }

    private fun projectedLongitude(longitude: Double): Double {
        val x = RADIUS * Math.toRadians(longitude)
        val wrappedX = x + floor((centerX - x) / WORLD_METERS + 0.5) * WORLD_METERS
        return (wrappedX - westMeters) / metersPerPixel
    }

    private fun projectedLatitude(latitude: Double): Double {
        val lat = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val y = RADIUS * ln(tan(PI / 4 + Math.toRadians(lat) / 2))
        return (northMeters - y) / metersPerPixel
    }

    /**
     * Repeat cloud texture in real-world meters, not in screen coordinates. The
     * modulo bounds the translation to a single texture period, so float matrix
     * precision stays intact at zoom 20 and on the opposite side of the globe.
     */
    fun textureMatrix(tileMeters: Double): FloatArray {
        val pixelScale = (tileMeters / (FogTexture.SIZE * metersPerPixel)).toFloat()
        val originX = ((westMeters + HALF_WORLD) % tileMeters + tileMeters) % tileMeters
        val originY = ((HALF_WORLD - northMeters) % tileMeters + tileMeters) % tileMeters
        return floatArrayOf(
            pixelScale, 0f, (-originX / metersPerPixel).toFloat(),
            0f, pixelScale, (-originY / metersPerPixel).toFloat(),
            0f, 0f, 1f
        )
    }

    companion object {
        private const val RADIUS = 6378137.0
        private const val MAX_LATITUDE = 85.05112878
        const val WORLD_METERS = 2 * PI * RADIUS
        private const val HALF_WORLD = WORLD_METERS / 2

        fun fullWorld(size: Int): FogGeoRaster =
            FogGeoRaster(-HALF_WORLD, HALF_WORLD, WORLD_METERS / size, size, size)

        /**
         * Wider, coarser geographic safety image for quick pinch-outs. Its visited
         * squares are drawn from the SAME coordinates and mask as the detailed
         * image; unlike the 512px whole-world fog, mile squares still have pixels.
         * Keep the extent below one Mercator world to avoid wrapping image quads.
         */
        fun pinchFallback(
            latitude: Double, longitude: Double, zoom: Double, size: Int,
            viewportWidth: Int, viewportHeight: Int, viewportMetersPerPixel: Double
        ): FogGeoRaster {
            // At zoom ~11 a four-level coarse backup reduced a one-mile
            // visited cell to about one pixel. Reusing that texture while
            // pinching back toward zoom 15 made already cleared cells opaque
            // on Android 12. Keep the broad four-level reserve above zoom 14.5,
            // but cap lower-zoom reserve so tile holes survive interpolation.
            val zoomReserve = minOf(4.0, (zoom - 10.5).coerceAtLeast(0.0))
            return around(
                latitude, longitude, zoom - zoomReserve, size,
                viewportWidth, viewportHeight,
                viewportMetersPerPixel * 2.0.pow(zoomReserve)
            )
        }

        /**
         * The integer-zoom LOD is stable throughout fractional pinch zooms;
         * rectangles snap to the world origin at 32 bitmap-pixel intervals. A
         * typical capture covers about two to four screens, including rotation.
         */
        fun around(
            latitude: Double, longitude: Double, zoom: Double, size: Int,
            viewportWidth: Int = 0, viewportHeight: Int = 0,
            viewportMetersPerPixel: Double = 0.0
        ): FogGeoRaster {
            var step = WORLD_METERS / (512.0 * 2.0.pow(floor(zoom).coerceIn(1.0, 21.0) - 1.0))
            if (viewportWidth > 0 && viewportHeight > 0 &&
                viewportMetersPerPixel.isFinite() && viewportMetersPerPixel > 0.0
            ) {
                // A north-up Mercator raster must also cover the diagonal of a
                // rotated portrait viewport, plus pan slack. Raster side length
                // formerly matched only ~1024 screen pixels at integer zoom, so
                // tall 1500+ px phones exposed the fallback world image on pan.
                val diagonalPixels = hypot(viewportWidth.toDouble(), viewportHeight.toDouble())
                val requiredStep = diagonalPixels * viewportMetersPerPixel * 1.4 /
                    (size - 64).coerceAtLeast(1)
                // Only grow by powers of two to avoid resizing/flashing during
                // the fractional stages of a pinch-zoom gesture.
                while (step < requiredStep) step *= 2.0
            }
            // ImageSource cannot georeference pixels beyond the Mercator poles.
            // Keep the square within one world, then shift it vertically at the
            // poles instead of clamping only its advertised latitude corners.
            // Otherwise those corners compress the image and move cleared cells.
            step = minOf(step, WORLD_METERS / size)
            val side = step * size
            val cx = RADIUS * Math.toRadians(longitude)
            val cy = RADIUS * ln(tan(PI / 4 + Math.toRadians(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)) / 2))
            val snap = step * 32.0
            val left = floor((cx - side / 2) / snap) * snap
            val top = (ceil((cy + side / 2) / snap) * snap)
                .coerceIn(-HALF_WORLD + side, HALF_WORLD)
            return FogGeoRaster(left, top, step, size, size)
        }

        private fun latitude(y: Double): Double =
            Math.toDegrees(atan(sinh(y.coerceIn(-HALF_WORLD, HALF_WORLD) / RADIUS)))

        private fun longitude(x: Double): Double = Math.toDegrees(x / RADIUS)
    }
}
