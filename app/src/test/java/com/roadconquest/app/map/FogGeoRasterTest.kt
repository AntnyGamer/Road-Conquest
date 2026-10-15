package com.roadconquest.app.map

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class FogGeoRasterTest {
    private fun phase(position: Double, translation: Float, scale: Float): Double {
        val textureX = (position - translation) / scale
        return ((textureX % FogTexture.SIZE + FogTexture.SIZE) % FogTexture.SIZE)
    }

    private fun longitudeDelta(a: Double, b: Double): Double =
        ((a - b + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    private fun phaseDelta(a: Double, b: Double): Double =
        ((a - b + FogTexture.SIZE / 2) % FogTexture.SIZE + FogTexture.SIZE) %
            FogTexture.SIZE - FogTexture.SIZE / 2

    @Test fun exploringAtSameLocationNeverTeleportsWhenRasterRepositions() {
        val corner = doubleArrayOf(40.0, -74.0, 40.0, -73.99,
            39.99, -73.99, 39.99, -74.0)
        val rasterA = FogGeoRaster.around(40.0, -74.0, 13.0, 896)
        val rasterB = FogGeoRaster.around(40.005, -74.005, 13.0, 896)
        val rasterC = FogGeoRaster.around(40.0, -74.0, 13.8, 896)
        for (raster in listOf(rasterA, rasterB, rasterC)) {
            val projected = raster.project(corner)
            val bounds = raster.corners()
            val left = bounds[1]
            val top = bounds[0]
            val metersPerPixel = raster.metersPerPixel
            assertTrue(metersPerPixel > 0)
            // Invert the raster projection, simulating MapLibre's attachment
            // of each image to the exact same real-world fog coordinates.
            for (i in projected.indices step 2) {
                val lng = left + Math.toDegrees(projected[i] * metersPerPixel / 6378137.0)
                val mercatorTop = 6378137.0 * ln(tan(PI / 4 + Math.toRadians(top) / 2))
                val northMeters = mercatorTop - projected[i + 1] * metersPerPixel
                val lat = Math.toDegrees(atan(sinh(northMeters / 6378137.0)))
                assertEquals(corner[i], lat, 1e-7)
                assertEquals(0.0, longitudeDelta(corner[i + 1], lng), 1e-7)
            }
        }
    }

    @Test fun squareProjectionPreservesPointLocationsWithinOneWorldCopy() {
        for ((latitude, longitude) in listOf(
            39.96 to -75.02, 0.0 to 179.999, 0.0 to -179.999,
            79.0 to 10.0, -80.0 to 15.0
        )) {
            val corners = FogGrid.visitedCorners(doubleArrayOf(
                latitude, longitude, latitude + 0.02, longitude + 0.01
            ))
            val rasters = listOf(
                FogGeoRaster.fullWorld(512),
                FogGeoRaster.around(latitude, longitude, 15.0, 1024)
            )
            for (raster in rasters) {
                val individual = raster.project(corners)
                val squares = raster.projectGridCorners(corners)
                val worldWidth = FogGeoRaster.WORLD_METERS / raster.metersPerPixel
                for (i in squares.indices step 2) {
                    val difference = squares[i] - individual[i]
                    assertEquals("Each corner retains its location modulo one world",
                        0.0, difference - round(difference / worldWidth) * worldWidth, 1e-8)
                    assertEquals(individual[i + 1], squares[i + 1], 0.0)
                }
            }
        }
    }

    @Test fun dateLineAndAntipodalCellsNeverRevealAWorldWideStrip() {
        for (raster in listOf(FogGeoRaster.fullWorld(512),
            FogGeoRaster.around(0.0, -160.0, 15.0, 1024))) {
            for (longitude in listOf(-179.999, 179.999, 20.0)) {
                val corners = FogGrid.corners(0.0, longitude)
                val squares = raster.projectGridCorners(corners)
                val expectedWidth = Math.toRadians(corners[3] - corners[1]) *
                    6378137.0 / raster.metersPerPixel
                assertEquals("Both edges must use the same world copy",
                    expectedWidth, squares[2] - squares[0], 1e-8)
                assertEquals(squares[0], squares[6], 0.0)
                assertEquals(squares[2], squares[4], 0.0)
            }
        }
    }

    @Test fun fogTexturePhaseRemainsConstantAtSameWorldPointWhenPanning() {
        val a = FogGeoRaster.around(40.0, -74.0, 16.0, 1024)
        val b = FogGeoRaster.around(40.015, -73.99, 16.0, 1024)
        val location = doubleArrayOf(40.004, -74.002, 40.004, -74.002,
            40.004, -74.002, 40.004, -74.002)
        for (cloudMeters in listOf(
            FogBitmapRenderer.CLOUD_DETAIL_METERS,
            FogBitmapRenderer.CLOUD_MEDIUM_METERS,
            FogBitmapRenderer.CLOUD_BROAD_METERS
        )) {
            val mA = a.textureMatrix(cloudMeters)
            val mB = b.textureMatrix(cloudMeters)
            val xA = a.project(location)[0]
            val xB = b.project(location)[0]
            val yA = a.project(location)[1]
            val yB = b.project(location)[1]
            assertEquals("Cloud X must not drift when the camera pans",
                0.0, phaseDelta(phase(xA, mA[2], mA[0]), phase(xB, mB[2], mB[0])), 0.03)
            assertEquals("Cloud Y must not drift when the camera pans",
                0.0, phaseDelta(phase(yA, mA[5], mA[4]), phase(yB, mB[5], mB[4])), 0.03)
        }
    }

    @Test fun portraitAndRotatedViewportRemainInsideDetailedFogRaster() {
        for ((width, height) in listOf(720 to 1600, 1080 to 2400, 1600 to 720)) {
            val metersPerPixel = 2.4
            val fog = FogGeoRaster.around(
                40.0, -74.0, 16.0, 1024, width, height, metersPerPixel
            )
            val diagonal = hypot(width.toDouble(), height.toDouble()) * metersPerPixel
            val rasterSide = 1024 * fog.metersPerPixel
            val padding = 32 * fog.metersPerPixel
            // A rectangular viewport rotated by any bearing fits inside the
            // square raster even when its center is snapped by 32 pixels.
            assertTrue("All rotated phone corners remain covered by fog",
                rasterSide - 2 * padding > diagonal)
            val center = FogGeoRaster.around(
                40.0, -74.0, 16.15, 1024, width, height, metersPerPixel * 0.90
            )
            assertEquals("No raster LOD jitter for small fractional camera gestures",
                fog.metersPerPixel, center.metersPerPixel, 1e-9)
        }
    }

    @Test fun globalBitmapDoesNotMagicallyClearAnAreaWiderThanAMile() {
        val world = FogGeoRaster.fullWorld(512)
        val oneMile = FogGrid.corners(39.98, -75.02)
        val xy = world.project(oneMile)
        val minX = listOf(xy[0], xy[2], xy[4], xy[6]).min()
        val maxX = listOf(xy[0], xy[2], xy[4], xy[6]).max()
        assertTrue("A mile is far below one 512px world texel", maxX - minX < 0.1)
        assertTrue(world.metersPerPixel > 70_000)
    }
    @Test fun regionalPinchBackupRetainsReadableMileSquaresAndCoversFourZoomLevels() {
        val width = 1080
        val height = 2340
        val metersPerPixel = 3.0
        val backup = FogGeoRaster.pinchFallback(
            39.96, -75.02, 15.0, 768, width, height, metersPerPixel
        )
        val square = FogGrid.corners(39.96, -75.02)
        val projected = backup.project(square)
        val span = (0..3).map { projected[it * 2] }.let { it.max() - it.min() }
        assertTrue("A mile square must occupy real backup pixels, unlike a world texel", span >= 2.0)
        val diagonalAtFourZoomLevelsOut = hypot(width.toDouble(), height.toDouble()) *
            metersPerPixel * 16.0
        assertTrue("The backup must cover a four-level pinch-out and a safety margin",
            768 * backup.metersPerPixel > diagonalAtFourZoomLevelsOut * 1.25)
        assertTrue("The visited cell remains inside the backup",
            projected.indices.step(2).all { i ->
                projected[i] in 0.0..768.0 && projected[i + 1] in 0.0..768.0
            })
        assertTrue("The backup must be geographically wider than the detailed source",
            backup.metersPerPixel >
                FogGeoRaster.around(39.96, -75.02, 15.0, 1024,
                    width, height, metersPerPixel).metersPerPixel)
    }

    @Test fun regionalBackupKeepsDateLineCellsInTheirCorrectWorldCopy() {
        for (lon in listOf(179.97, -179.97)) {
            val backing = FogGeoRaster.pinchFallback(
                0.0, lon, 15.0, 768, 1080, 2340, 3.0
            )
            val corners = FogGrid.corners(0.0, lon)
            val xy = backing.project(corners)
            assertTrue(xy.indices.step(2).all { i ->
                xy[i] in 0.0..768.0 && xy[i + 1] in 0.0..768.0
            })
        }
    }
}
