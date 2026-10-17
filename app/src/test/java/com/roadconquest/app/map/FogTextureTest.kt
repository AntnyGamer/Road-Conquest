package com.roadconquest.app.map

import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class FogTextureTest {
    @Test fun patternContainsSoftCloudVariationAndTilesWithoutSharpSeams() {
        val bitmap = FogTexture.create()
        val size = FogTexture.SIZE
        val sampleStep = (size / 32).coerceAtLeast(1)
        val shades = (0 until size step sampleStep).flatMap { y ->
            (0 until size step sampleStep).map { x -> Color.red(bitmap.getPixel(x, y)) }
        }

        assertTrue("Cloud shading must remain visibly textured", shades.max() - shades.min() > 50)

        for (i in 0 until size) {
            assertTrue(abs(Color.red(bitmap.getPixel(0, i)) - Color.red(bitmap.getPixel(size - 1, i))) <= 2)
            assertTrue(abs(Color.red(bitmap.getPixel(i, 0)) - Color.red(bitmap.getPixel(i, size - 1))) <= 2)
            assertEquals(255, Color.alpha(bitmap.getPixel(i, i)))
        }

        // Natural fog should change gradually rather than forming hard checker/grid boundaries.
        var maxNeighborDelta = 0
        for (y in 0 until size step 4) {
            for (x in 0 until size - 1 step 4) {
                maxNeighborDelta = maxOf(
                    maxNeighborDelta,
                    abs(Color.red(bitmap.getPixel(x, y)) - Color.red(bitmap.getPixel(x + 1, y)))
                )
            }
        }
        for (y in 0 until size - 1 step 4) {
            for (x in 0 until size step 4) {
                maxNeighborDelta = maxOf(
                    maxNeighborDelta,
                    abs(Color.red(bitmap.getPixel(x, y)) - Color.red(bitmap.getPixel(x, y + 1)))
                )
            }
        }
        assertTrue("Fog texture should stay softly varying", maxNeighborDelta <= 8)
    }

}
