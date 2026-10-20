package com.roadconquest.app.map

import android.graphics.Matrix
import kotlin.math.*

/** Captured only for an off-screen bitmap; MapLibre handles subsequent camera gestures. */
class FogTextureTransform {
    private val coordinates = DoubleArray(6)
    private val screen = DoubleArray(6)
    private val values = FloatArray(9)
    private var previousX = Double.NaN
    private var previousY = Double.NaN
    private var previousTileMeters = BASE_TILE_METERS
    private var phaseX = 128.0
    private var phaseY = 128.0

    fun update(latitude: Double, longitude: Double, zoom: Double,
               project: (DoubleArray, DoubleArray) -> Unit, output: Matrix): Boolean {
        if (!latitude.isFinite() || !longitude.isFinite() || !zoom.isFinite()) return false
        val x = EARTH_RADIUS_M * Math.toRadians(longitude)
        val y = EARTH_RADIUS_M * ln(tan(PI / 4 + Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878)) / 2))
        val tileMeters = tileMetersForZoom(zoom)
        val probe = minOf(tileMeters, 1000.0)
        val east = if (longitude < 179.0) probe else -probe
        val north = if (latitude > 0) -probe else probe
        coordinates[0] = latitude.coerceIn(-85.05112878, 85.05112878)
        coordinates[1] = longitude
        coordinates[2] = coordinates[0]
        coordinates[3] = Math.toDegrees((x + east) / EARTH_RADIUS_M)
        coordinates[4] = Math.toDegrees(atan(sinh((y + north) / EARTH_RADIUS_M)))
        coordinates[5] = longitude
        project(coordinates, screen)
        if (screen.any { !it.isFinite() }) return false
        val xx = (screen[2] - screen[0]) / east * tileMeters / FogTexture.SIZE
        val xy = (screen[3] - screen[1]) / east * tileMeters / FogTexture.SIZE
        val yx = -(screen[4] - screen[0]) / north * tileMeters / FogTexture.SIZE
        val yy = -(screen[5] - screen[1]) / north * tileMeters / FogTexture.SIZE
        if (!xx.isFinite() || !xy.isFinite() || !yx.isFinite() || !yy.isFinite() || abs(xx * yy - xy * yx) < 1e-12) return false
        // Preserve the cloud phase at the center during zoom; only panning advances it.
        // Recomputing a world-origin modulo at each zoom would race through repeats and flash.
        var nextPhaseX = phaseX
        var nextPhaseY = phaseY
        if (previousX.isFinite()) {
            val panScale = sqrt(previousTileMeters * tileMeters)
            val world = 2.0 * PI * EARTH_RADIUS_M
            val dx = ((x - previousX + world / 2) % world + world) % world - world / 2
            nextPhaseX = repeatPhase(phaseX + dx / panScale * FogTexture.SIZE)
            nextPhaseY = repeatPhase(phaseY - (y - previousY) / panScale * FogTexture.SIZE)
        }
        values[Matrix.MSCALE_X] = xx.toFloat()
        values[Matrix.MSKEW_X] = yx.toFloat()
        values[Matrix.MTRANS_X] = (screen[0] - xx * nextPhaseX - yx * nextPhaseY).toFloat()
        values[Matrix.MSKEW_Y] = xy.toFloat()
        values[Matrix.MSCALE_Y] = yy.toFloat()
        values[Matrix.MTRANS_Y] = (screen[1] - xy * nextPhaseX - yy * nextPhaseY).toFloat()
        values[Matrix.MPERSP_0] = 0f; values[Matrix.MPERSP_1] = 0f; values[Matrix.MPERSP_2] = 1f
        if (values.any { !it.isFinite() }) return false
        output.setValues(values)
        phaseX = nextPhaseX; phaseY = nextPhaseY
        previousX = x; previousY = y; previousTileMeters = tileMeters
        return true
    }

    private fun repeatPhase(value: Double) = (value % FogTexture.SIZE + FogTexture.SIZE) % FogTexture.SIZE

    companion object {
        private const val EARTH_RADIUS_M = 6378137.0
        private const val BASE_TILE_METERS = 256.0
        internal fun tileMetersForZoom(zoom: Double): Double = minOf(
            BASE_TILE_METERS * 2.0.pow((18.0 - zoom).coerceIn(-4.0, 18.0) * 1.25),
            2.0 * PI * EARTH_RADIUS_M
        )
    }
}
