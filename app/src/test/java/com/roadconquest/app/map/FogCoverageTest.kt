package com.roadconquest.app.map

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class FogCoverageTest {
    @Test fun minimumZoomKeepsRotatedCornersInsideBothWorldEdges() {
        for ((width, height) in listOf(709 to 1536, 1536 to 709, 2560 to 1600)) {
            for (latitude in listOf(0.0, 40.0, -40.0, 80.0, -80.0)) {
                val zoom = FogCoverage.minimumZoom(width, height, 2f, latitude)
                val worldPixels = 512.0 * 2.0 * 2.0.pow(zoom)
                val y = (1.0 - ln(tan(PI / 4.0 + Math.toRadians(latitude) / 2.0)) / PI) / 2.0
                for (bearing in 0..359 step 5) {
                    val radians = Math.toRadians(bearing.toDouble())
                    val halfSpan = (abs(sin(radians)) * width + abs(cos(radians)) * height) / 2.0
                    assertTrue("North edge stays outside the viewport", y * worldPixels > halfSpan)
                    assertTrue("South edge stays outside the viewport", (1.0 - y) * worldPixels > halfSpan)
                }
            }
        }
    }

    @Test fun fastZoomOutInvalidatesAnExposedFogRectangle() {
        assertTrue(FogCoverage.coversViewport(doubleArrayOf(-300.0, -600.0, 500.0, -600.0,
            500.0, 1000.0, -300.0, 1000.0), 200, 400))
        assertFalse(FogCoverage.coversViewport(doubleArrayOf(75.0, 125.0, 125.0, 125.0,
            125.0, 225.0, 75.0, 225.0), 200, 400))
    }

    @Test fun movingCameraFallsBackBeforeDetailedFogCanReachTheViewportEdge() {
        val almostExposed = doubleArrayOf(
            -45.0, -90.0, 245.0, -90.0,
            245.0, 490.0, -45.0, 490.0
        )
        assertTrue(FogCoverage.coversViewport(almostExposed, 200, 400))
        assertFalse(FogCoverage.coversViewport(almostExposed, 200, 400, 0.50))

        val roomy = doubleArrayOf(
            -110.0, -210.0, 310.0, -210.0,
            310.0, 610.0, -110.0, 610.0
        )
        assertTrue(FogCoverage.coversViewport(roomy, 200, 400, 0.50))
    }

    @Test fun rotationAndPanningRequireCoverageAtEveryCorner() {
        assertTrue(FogCoverage.coversViewport(doubleArrayOf(100.0, -250.0, 550.0, 200.0,
            100.0, 650.0, -350.0, 200.0), 200, 400))
        assertFalse(FogCoverage.coversViewport(doubleArrayOf(100.0, -20.0, 320.0, 200.0,
            100.0, 420.0, -120.0, 200.0), 200, 400))
        assertFalse(FogCoverage.coversViewport(doubleArrayOf(10.0, -600.0, 800.0, -600.0,
            800.0, 1000.0, 10.0, 1000.0), 200, 400))
    }

    @Test fun invalidMapLatitudeNeverProducesNanZoom() {
        for (latitude in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(0.0, FogCoverage.minimumZoom(1080, 2400, 2f, latitude), 0.0)
        }
    }

    @Test fun invalidOrCollapsedQuadsUseTheWorldFallback() {
        assertFalse(FogCoverage.coversViewport(DoubleArray(8), 200, 400))
        assertFalse(FogCoverage.coversViewport(DoubleArray(8) { Double.NaN }, 200, 400))
        assertFalse(FogCoverage.coversViewport(doubleArrayOf(), 200, 400))
        assertEquals(0.0, FogCoverage.minimumZoom(0, 0, 2f, 40.0), 0.0)
    }
}
