package com.roadconquest.app.map

import kotlin.math.*

/** Camera limits and coverage checks in physical view pixels, including rotated viewports. */
internal object FogCoverage {
    fun minimumZoom(width: Int, height: Int, pixelRatio: Float, latitude: Double): Double {
        if (width <= 0 || height <= 0 || !pixelRatio.isFinite() || pixelRatio <= 0f ||
            !latitude.isFinite()) return 0.0
        val lat = Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878))
        val y = (1.0 - ln(tan(PI / 4.0 + lat / 2.0)) / PI) / 2.0
        // A diagonal fits at every bearing. Both poles must remain beyond the nearest
        // viewport edge, including when the camera is centered away from the equator.
        val availableWorld = (2.0 * min(y, 1.0 - y)).coerceAtLeast(1e-6)
        return log2(hypot(width.toDouble(), height.toDouble()) * 1.02 /
            (512.0 * pixelRatio * availableWorld)).coerceIn(0.0, FogBitmapRenderer.MAX_ZOOM)
    }

    fun coversViewport(
        quad: DoubleArray,
        width: Int,
        height: Int,
        marginFraction: Double = 0.0
    ): Boolean {
        if (quad.size != 8 || quad.any { !it.isFinite() } || width <= 0 || height <= 0 ||
            !marginFraction.isFinite() || marginFraction < 0.0
        ) return false
        val marginX = 2.0 + width * marginFraction
        val marginY = 2.0 + height * marginFraction
        for (corner in 0..3) {
            val x = if (corner == 0 || corner == 3) -marginX else width + marginX
            val y = if (corner < 2) -marginY else height + marginY
            var orientation = 0
            for (edge in 0..3) {
                val i = edge * 2
                val j = (i + 2) % 8
                val cross = (quad[j] - quad[i]) * (y - quad[i + 1]) -
                    (quad[j + 1] - quad[i + 1]) * (x - quad[i])
                if (abs(cross) < 1e-6) return false
                val side = if (cross > 0.0) 1 else -1
                if (orientation != 0 && orientation != side) return false
                orientation = side
            }
        }
        return true
    }
}
