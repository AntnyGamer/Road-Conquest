package com.roadconquest.app.map

import android.graphics.Color
import com.roadconquest.app.data.RoadRecord
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FogOverlayTest {
    private fun request(
        roads: OverlayRoads = OverlayRoads.EMPTY,
        roadScreen: DoubleArray = doubleArrayOf(),
        metersPerPixel: Double = 1.0,
        liveLatitude: Double? = null,
        liveScreen: DoubleArray? = null
    ) = FogBitmapRenderer.Request(
        bitmapWidth = 640,
        bitmapHeight = 640,
        screenLeft = 0f,
        screenTop = 0f,
        screenScale = 1f,
        roads = roads,
        roadScreen = roadScreen,
        centerLatitude = 0.0,
        metersPerScreenPixelAtCenter = metersPerPixel,
        liveLatitude = liveLatitude,
        liveScreen = liveScreen
    )

    private fun alpha(x: Int, y: Int, request: FogBitmapRenderer.Request): Int =
        Color.alpha(FogBitmapRenderer.render(request).getPixel(x, y))

    private fun junctionRoad(name: String, time: Long, vararg points: Pair<Double, Double>): RoadRecord {
        val json = org.json.JSONArray()
        for ((lon, lat) in points) json.put(org.json.JSONArray().put(lon).put(lat))
        val lats = points.map { it.second }
        val lons = points.map { it.first }
        return RoadRecord(name, name, json.toString(), time, time + 5_000L,
            lats.min(), lats.max(), lons.min(), lons.max())
    }

    @Test fun confirmedFlandersToColonialTurnVisuallyJoinsWithoutInventingRoadHistory() {
        // Translated coordinates derived from the geometry of a recorded broken turn.
        val incoming = junctionRoad("approach", 1_000_000L,
            -74.096758 to 39.967769, -74.096682 to 39.967903, -74.096632 to 39.967990)
        val outgoing = junctionRoad("exit", 1_008_000L,
            -74.096525 to 39.968062, -74.096446 to 39.968034, -74.096312 to 39.967987)
        val stitched = OverlayRoads.prepare(listOf(incoming, outgoing))
        assertEquals("Confirmed centerline turn should become a single blue chain", 2, stitched.starts.size)
        assertTrue("Intersection vertex adds a short curved corner", stitched.coordinates.size > 12)
    }

    @Test fun confirmedColonialToNextRoadTurnVisuallyJoinsAcrossTwoMissingFixes() {
        val incoming = junctionRoad("approach", 1_000_000L,
            -74.095820 to 39.967811, -74.095752 to 39.967787, -74.095658 to 39.967758)
        val outgoing = junctionRoad("exit", 1_010_000L,
            -74.095391 to 39.967677, -74.095348 to 39.967762, -74.095284 to 39.967889)
        val stitched = OverlayRoads.prepare(listOf(incoming, outgoing))
        assertEquals(2, stitched.starts.size)
        assertTrue(stitched.coordinates.size > 12)
    }

    @Test fun recordedBenignoTurnUsesTheApproachBeforeATinyTerminalSegment() {
        // Shifted coordinates retain the recorded road's shape and its 0.64 m final edge.
        // Beta 24 rejected this valid ~26 m junction despite confirmed roads on both sides.
        val approach = junctionRoad("exit connector", 1_000_000L,
            -74.099435 to 40.861467, -74.099409 to 40.861480,
            -74.099356 to 40.861503, -74.099301 to 40.861521,
            -74.099294 to 40.861523)
        val exit = junctionRoad("boulevard", 1_006_000L,
            -74.099167 to 40.861733, -74.099198 to 40.861876,
            -74.099234 to 40.862039)
        val overlay = OverlayRoads.prepare(listOf(approach, exit))
        assertEquals("Confirmed turn must be a single continuous line", 2, overlay.starts.size)
        assertTrue("Connector includes centerline bend", overlay.coordinates.size > 16)
    }

    @Test fun shortFirstExitEdgeAlsoUsesNearbyConfirmedDirection() {
        val approach = junctionRoad("exit connector", 1_000_000L,
            -74.099435 to 40.861467, -74.099356 to 40.861503,
            -74.099294 to 40.861523)
        val exit = junctionRoad("boulevard", 1_006_000L,
            -74.099167 to 40.861733, -74.099168 to 40.861738,
            -74.099198 to 40.861876, -74.099234 to 40.862039)
        assertEquals(2, OverlayRoads.prepare(listOf(approach, exit)).starts.size)
    }

    @Test fun previouslyUnlockedRoadCanJoinANewlyUnlockedTurnOnALaterDrive() {
        val incoming = junctionRoad("approach", 1_000_000L,
            -74.096758 to 39.967769, -74.096682 to 39.967903, -74.096632 to 39.967990
        ).copy(lastDrivenAt = 2_000_000L)
        val outgoing = junctionRoad("exit", 2_008_000L,
            -74.096525 to 39.968062, -74.096446 to 39.968034, -74.096312 to 39.967987)
        // Their first-unlocked dates are far apart, but the approach was recently driven.
        assertEquals(2, OverlayRoads.prepare(listOf(incoming, outgoing)).starts.size)
    }

    @Test fun twoPreviouslyUnlockedRoadsJoinWhenDrivenTogetherAgain() {
        val incoming = junctionRoad("approach", 1_000_000L,
            -74.096758 to 39.967769, -74.096682 to 39.967903, -74.096632 to 39.967990
        ).copy(lastDrivenAt = 2_000_000L)
        val outgoing = junctionRoad("exit", 1_120_000L,
            -74.096525 to 39.968062, -74.096446 to 39.968034, -74.096312 to 39.967987
        ).copy(lastDrivenAt = 2_008_000L)
        assertEquals(2, OverlayRoads.prepare(listOf(incoming, outgoing)).starts.size)
    }

    @Test fun originalTurnConnectionPersistsWhenOnlyOneRoadIsDrivenLater() {
        val incoming = junctionRoad("approach", 1_000_000L,
            -74.096758 to 39.967769, -74.096682 to 39.967903, -74.096632 to 39.967990
        ).copy(lastDrivenAt = 2_000_000L)
        val outgoing = junctionRoad("exit", 1_008_000L,
            -74.096525 to 39.968062, -74.096446 to 39.968034, -74.096312 to 39.967987)
        assertEquals(2, OverlayRoads.prepare(listOf(incoming, outgoing)).starts.size)
    }

    @Test fun unrelatedDrivingTimesNeverStitchAnOldAndANewRoad() {
        val incoming = junctionRoad("approach", 1_000_000L,
            -74.096758 to 39.967769, -74.096682 to 39.967903, -74.096632 to 39.967990
        ).copy(lastDrivenAt = 2_000_000L)
        val outgoing = junctionRoad("exit", 2_120_000L,
            -74.096525 to 39.968062, -74.096446 to 39.968034, -74.096312 to 39.967987)
        assertEquals(3, OverlayRoads.prepare(listOf(incoming, outgoing)).starts.size)
    }

    @Test fun revisitedWrongDirectionDoesNotGetConnectedAsATurn() {
        val incoming = junctionRoad("approach", 1_000_000L,
            -74.096758 to 39.967769, -74.096682 to 39.967903, -74.096632 to 39.967990
        ).copy(lastDrivenAt = 2_000_000L)
        val wrongDirection = junctionRoad("wrong exit direction", 1_008_000L,
            -74.096525 to 39.968062, -74.096604 to 39.968090, -74.096680 to 39.968117
        ).copy(lastDrivenAt = 2_008_000L)
        assertEquals("Recent timestamps must not override incompatible road geometry",
            3, OverlayRoads.prepare(listOf(incoming, wrongDirection)).starts.size)
    }

    @Test fun revisitedRoadsDoNotBridgeUnrelatedRecentTrips() {
        val incoming = junctionRoad("approach", 1_000_000L,
            -74.096758 to 39.967769, -74.096682 to 39.967903, -74.096632 to 39.967990
        ).copy(lastDrivenAt = 2_000_000L)
        val distantVisit = junctionRoad("exit", 1_008_000L,
            -74.096525 to 39.968062, -74.096446 to 39.968034, -74.096312 to 39.967987
        ).copy(lastDrivenAt = 2_120_000L)
        // Their original unlocks formed an eligible turn, so this must STILL show it.
        // A distant revisit must never erase that already-confirmed visual connection.
        assertEquals(2, OverlayRoads.prepare(listOf(incoming, distantVisit)).starts.size)
    }

    @Test fun competingLaterVisitCannotErasePreviouslyVisibleFirstUnlockTurn() {
        val incoming = junctionRoad("approach", 1_000_000L,
            -74.096758 to 39.967769, -74.096682 to 39.967903, -74.096632 to 39.967990)
        val originalExit = junctionRoad("original exit", 1_008_000L,
            -74.096525 to 39.968062, -74.096446 to 39.968034, -74.096312 to 39.967987)
        // A later competing exit begins 1-3 m from the earlier one. Its *recent* visit
        // must not make the genuinely recorded original first-unlock turn disappear.
        val competingExit = junctionRoad("later exit", 1_300_000L,
            -74.096505 to 39.968062, -74.096426 to 39.968034, -74.096292 to 39.967987)
        val roads = listOf(incoming, originalExit, competingExit)
        val original = OverlayRoads.prepare(roads)
        assertEquals(3, original.starts.size)
        val later = OverlayRoads.prepare(listOf(
            incoming.copy(lastDrivenAt = 2_000_000L),
            originalExit,
            competingExit.copy(lastDrivenAt = 2_008_000L)
        ))
        assertArrayEquals(original.coordinates, later.coordinates, 0.0)
        assertArrayEquals(original.starts, later.starts)
    }

    @Test fun interveningRoadVisitsRecoverTurnNotPresentInAggregateTimestamps() {
        val incoming = junctionRoad("approach", 1_000_000L,
            -74.096758 to 39.967769, -74.096682 to 39.967903, -74.096632 to 39.967990)
        val outgoing = junctionRoad("exit", 1_120_000L,
            -74.096525 to 39.968062, -74.096446 to 39.968034, -74.096312 to 39.967987)
        val roads = listOf(incoming, outgoing)
        assertEquals(3, OverlayRoads.prepare(roads).starts.size)
        val visits = mapOf(
            incoming.segmentId to listOf(2_000_000L to 2_005_000L),
            outgoing.segmentId to listOf(2_010_000L to 2_015_000L)
        )
        val repaired = OverlayRoads.prepare(roads, visits)
        assertEquals("The same-trip visit windows should join this genuine corner",
            2, repaired.starts.size)
        assertTrue(repaired.coordinates.size > 12)
    }

    @Test fun extraVisitCandidatesCannotRemoveAPreviouslyShownCorner() {
        val incoming = junctionRoad("approach", 1_000_000L,
            -74.096758 to 39.967769, -74.096682 to 39.967903, -74.096632 to 39.967990)
        val outgoing = junctionRoad("exit", 1_008_000L,
            -74.096525 to 39.968062, -74.096446 to 39.968034, -74.096312 to 39.967987)
        val roads = listOf(incoming, outgoing)
        val original = OverlayRoads.prepare(roads)
        val visits = mapOf(
            incoming.segmentId to listOf(2_000_000L to 2_005_000L),
            outgoing.segmentId to listOf(2_010_000L to 2_015_000L)
        )
        val withVisits = OverlayRoads.prepare(roads, visits)
        assertArrayEquals("Adding visit evidence may never displace an existing repair",
            original.coordinates, withVisits.coordinates, 0.0)
        assertArrayEquals(original.starts, withVisits.starts)
    }

    @Test fun competingVisitCandidateCannotDisplaceOriginalConfirmedCorner() {
        val incoming = junctionRoad("approach", 1_000_000L,
            -74.096758 to 39.967769, -74.096682 to 39.967903, -74.096632 to 39.967990)
        val originalExit = junctionRoad("original exit", 1_008_000L,
            -74.096525 to 39.968062, -74.096446 to 39.968034, -74.096312 to 39.967987)
        val otherExit = junctionRoad("later exit", 1_300_000L,
            -74.096445 to 39.968062, -74.096366 to 39.968034, -74.096232 to 39.967987)
        val roads = listOf(incoming, originalExit, otherExit)
        val original = OverlayRoads.prepare(roads)
        val visits = mapOf(
            incoming.segmentId to listOf(2_000_000L to 2_005_000L),
            otherExit.segmentId to listOf(2_010_000L to 2_015_000L)
        )
        val withNewCandidate = OverlayRoads.prepare(roads, visits)
        assertArrayEquals(original.coordinates, withNewCandidate.coordinates, 0.0)
        assertArrayEquals(original.starts, withNewCandidate.starts)
    }

    @Test fun visitEvidenceStillRejectsOpposingExitDirection() {
        val incoming = junctionRoad("approach", 1_000_000L,
            -74.096758 to 39.967769, -74.096682 to 39.967903, -74.096632 to 39.967990)
        val opposite = junctionRoad("wrong exit", 1_120_000L,
            -74.096525 to 39.968062, -74.096604 to 39.968090, -74.096680 to 39.968117)
        val roads = listOf(incoming, opposite)
        val visits = mapOf(
            incoming.segmentId to listOf(2_000_000L to 2_005_000L),
            opposite.segmentId to listOf(2_010_000L to 2_015_000L)
        )
        assertEquals(3, OverlayRoads.prepare(roads, visits).starts.size)
    }

    @Test fun unrelatedVisitWindowsDoNotCreateNewTurns() {
        val incoming = junctionRoad("approach", 1_000_000L,
            -74.096758 to 39.967769, -74.096682 to 39.967903, -74.096632 to 39.967990)
        val outgoing = junctionRoad("exit", 1_120_000L,
            -74.096525 to 39.968062, -74.096446 to 39.968034, -74.096312 to 39.967987)
        val roads = listOf(incoming, outgoing)
        val visits = mapOf(
            incoming.segmentId to listOf(2_000_000L to 2_005_000L),
            outgoing.segmentId to listOf(2_060_000L to 2_065_000L)
        )
        assertEquals(3, OverlayRoads.prepare(roads, visits).starts.size)
    }

    @Test fun distantTangentEvidenceCannotCreateAShortcut() {
        val approach = junctionRoad("long unsupported approach", 1_000_000L,
            -74.099800 to 40.861300, -74.099301 to 40.861521,
            -74.099294 to 40.861523)
        val exit = junctionRoad("boulevard", 1_006_000L,
            -74.099167 to 40.861733, -74.099198 to 40.861876,
            -74.099234 to 40.862039)
        assertEquals("Direction evidence more than 25m behind endpoint is insufficient",
            3, OverlayRoads.prepare(listOf(approach, exit)).starts.size)
    }

    @Test fun microEdgeCannotAuthorizeTurnWithoutARealApproachDirection() {
        val tooShort = junctionRoad("no direction", 1_000_000L,
            -74.099301 to 40.861521, -74.099294 to 40.861523)
        val exit = junctionRoad("boulevard", 1_006_000L,
            -74.099167 to 40.861733, -74.099198 to 40.861876,
            -74.099234 to 40.862039)
        assertEquals("No invented junction without a 4m approach tangent",
            3, OverlayRoads.prepare(listOf(tooShort, exit)).starts.size)
    }

    @Test fun distantTimesAndUnrelatedDirectionsNeverCreateAJunctionShortcut() {
        val incoming = junctionRoad("approach", 1_000_000L,
            -74.096758 to 39.967769, -74.096682 to 39.967903, -74.096632 to 39.967990)
        val delayed = junctionRoad("later trip", 1_120_000L,
            -74.096525 to 39.968062, -74.096446 to 39.968034, -74.096312 to 39.967987)
        assertEquals(3, OverlayRoads.prepare(listOf(incoming, delayed)).starts.size)
        val wrongDirection = junctionRoad("wrong direction", 1_008_000L,
            -74.096525 to 39.968062, -74.096604 to 39.968090, -74.096680 to 39.968117)
        assertEquals(3, OverlayRoads.prepare(listOf(incoming, wrongDirection)).starts.size)
    }

    @Test fun worldAnchoredCloudsHaveVisibleDarkGrayVariationWithoutAlphaFlicker() {
        val raster = FogGeoRaster.around(40.0, -74.0, 13.0, 640)
        val tile = request(metersPerPixel = raster.metersPerPixel).copy(
            gridMode = true,
            textureMatrix = raster.textureMatrix(FogBitmapRenderer.CLOUD_DETAIL_METERS),
            mediumCloudMatrix = raster.textureMatrix(FogBitmapRenderer.CLOUD_MEDIUM_METERS),
            broadCloudMatrix = raster.textureMatrix(FogBitmapRenderer.CLOUD_BROAD_METERS)
        )
        val bitmap = FogBitmapRenderer.render(tile)
        val colors = (50 until 590 step 20).flatMap { y ->
            (50 until 590 step 20).map { x -> bitmap.getPixel(x, y) }
        }
        assertTrue("Dark clouds are textured, not a solid gray sheet",
            colors.map(Color::red).distinct().size > 12)
        assertTrue("Cloud layers remain visually dark gray",
            colors.all { kotlin.math.abs(Color.red(it) - Color.blue(it)) < 24 })
        assertTrue("Every unexplored pixel retains an exact 80% alpha",
            colors.all { Color.alpha(it) == 204 })
        // Only the explored square changes alpha; the cloud pattern outside it
        // keeps identical ARGB regardless of whether visits are drawn.
        val saved = tile.copy(
            gridCoordinates = doubleArrayOf(
                40.0, -74.01, 40.0, -74.0, 39.99, -74.0, 39.99, -74.01),
            gridScreen = raster.project(doubleArrayOf(
                40.0, -74.01, 40.0, -74.0, 39.99, -74.0, 39.99, -74.01))
        )
        val withReveals = FogBitmapRenderer.render(saved)
        for ((x, y) in listOf(20 to 20, 620 to 20, 20 to 620, 620 to 620)) {
            assertEquals("Revealing a square never changes unrelated cloud colors",
                bitmap.getPixel(x, y), withReveals.getPixel(x, y))
        }
    }

    @Test fun remoteExplorationCellsSkipFogMaskButEdgeCellsKeepTheirFade() {
        val base = request(metersPerPixel = 5.0).copy(gridMode = true)
        val coords = doubleArrayOf(0.01, -0.01, 0.01, 0.01, -0.01, 0.01, -0.01, -0.01)
        val far = base.copy(
            gridCoordinates = coords,
            gridScreen = doubleArrayOf(2000.0, 2000.0, 2100.0, 2000.0,
                2100.0, 2100.0, 2000.0, 2100.0)
        )
        assertFalse(FogBitmapRenderer.gridCellMayAffectBitmap(far, 0))
        val original = FogBitmapRenderer.render(base)
        val outside = FogBitmapRenderer.render(far)
        for (y in listOf(0, 200, 639)) for (x in listOf(0, 200, 639)) {
            assertEquals("Distant recorded tiles cannot change fog", original.getPixel(x, y),
                outside.getPixel(x, y))
        }
        val edge = base.copy(
            gridCoordinates = coords,
            gridScreen = doubleArrayOf(600.0, 280.0, 700.0, 280.0,
                700.0, 380.0, 600.0, 380.0)
        )
        assertTrue(FogBitmapRenderer.gridCellMayAffectBitmap(edge, 0))
        assertEquals(0, Color.alpha(FogBitmapRenderer.render(edge).getPixel(620, 320)))
        assertEquals(204, Color.alpha(FogBitmapRenderer.render(edge).getPixel(100, 100)))
    }

    @Test fun subpixelMileCellsAtWorldZoomCannotMakeEnormousClearSquare() {
        // One visited mile is much smaller than a 78-km Mercator-world texel
        // at a 512px overview. Anti-aliased fractional coverage must remain
        // fractional instead of turning the entire giant pixel transparent.
        val distant = request(metersPerPixel = 78_000.0).copy(
            bitmapWidth = 128, bitmapHeight = 128, gridMode = true,
            gridCoordinates = doubleArrayOf(
                0.01, -0.01, 0.01, 0.01, -0.01, 0.01, -0.01, -0.01
            ),
            gridScreen = doubleArrayOf(
                50.25, 50.25, 50.75, 50.25, 50.75, 50.75, 50.25, 50.75
            )
        )
        val bitmap = FogBitmapRenderer.render(distant)
        val smallestAlpha = (49..52).minOf { x -> (49..52).minOf { y ->
            Color.alpha(bitmap.getPixel(x, y))
        } }
        assertTrue("Subpixel world cells never punch out a 78-km map pixel", smallestAlpha > 100)
        assertEquals(204, Color.alpha(bitmap.getPixel(90, 90)))
    }

    @Test fun visitedMileTileClearsEntireInteriorWithOriginal1500FootFade() {
        val tile = request(metersPerPixel = 5.0).copy(
            gridMode = true,
            gridCoordinates = doubleArrayOf(
                0.01, -0.01, 0.01, 0.01, -0.01, 0.01, -0.01, -0.01
            ),
            gridScreen = doubleArrayOf(
                200.0, 200.0, 400.0, 200.0, 400.0, 400.0, 200.0, 400.0
            )
        )
        val rendered = FogBitmapRenderer.render(tile)
        assertEquals(0, Color.alpha(rendered.getPixel(300, 300)))
        assertEquals(0, Color.alpha(rendered.getPixel(300, 205)))
        assertTrue(Color.alpha(rendered.getPixel(300, 160)) in 1..203)
        assertEquals(204, Color.alpha(rendered.getPixel(300, 90)))
        assertTrue(Color.alpha(rendered.getPixel(165, 165)) in 1..203)
        assertEquals(204, Color.alpha(rendered.getPixel(90, 90)))
    }

    @Test fun neighboringCellsFadeAsOneRegionWithoutInternalGraySquareBands() {
        val cells = request(metersPerPixel = 5.0).copy(
            gridMode = true,
            gridCoordinates = doubleArrayOf(
                0.01, -0.02, 0.01, 0.0, -0.01, 0.0, -0.01, -0.02,
                0.01, 0.0, 0.01, 0.02, -0.01, 0.02, -0.01, 0.0
            ),
            gridScreen = doubleArrayOf(
                160.0, 220.0, 360.0, 220.0, 360.0, 420.0, 160.0, 420.0,
                360.0, 220.0, 560.0, 220.0, 560.0, 420.0, 360.0, 420.0
            )
        )
        val fog = FogBitmapRenderer.render(cells)
        for (x in listOf(250, 350, 360, 370, 470)) {
            assertEquals("No fog seam inside the explored union", 0, Color.alpha(fog.getPixel(x, 320)))
        }
        val outer = listOf(250, 350, 360, 370, 470).map { Color.alpha(fog.getPixel(it, 180)) }
        assertTrue("The outer fade must not show blocky bands above individual cells",
            outer.max() - outer.min() <= 3)
        assertTrue(outer.first() in 1..203)
        assertEquals(204, Color.alpha(fog.getPixel(360, 100)))
    }

    @Test fun mileGridIgnoresRoadOnlyClearingsAndAdjacentTilesHaveNoSeam() {
        val road = OverlayRoads(doubleArrayOf(0.0, -1.0, 0.0, 1.0), intArrayOf(0, 4))
        val roadScreen = doubleArrayOf(0.0, 320.0, 640.0, 320.0)
        val withoutTiles = request(road, roadScreen, metersPerPixel = 5.0).copy(gridMode = true)
        assertEquals(204, alpha(320, 320, withoutTiles))
        val withTiles = withoutTiles.copy(
            gridCoordinates = doubleArrayOf(
                0.01, -0.02, 0.01, 0.0, -0.01, 0.0, -0.01, -0.02,
                0.01, 0.0, 0.01, 0.02, -0.01, 0.02, -0.01, 0.0
            ),
            gridScreen = doubleArrayOf(
                200.0, 200.0, 400.0, 200.0, 400.0, 400.0, 200.0, 400.0,
                400.0, 200.0, 600.0, 200.0, 600.0, 400.0, 400.0, 400.0
            )
        )
        assertEquals(0, alpha(400, 300, withTiles))
        assertEquals(0, alpha(500, 300, withTiles))
        assertEquals(204, alpha(60, 100, withTiles))
    }

    @Test fun roadFadeKeepsClearCoreButAvoidsARegionalGlow() {
        val roads = OverlayRoads(doubleArrayOf(0.0, -1.0, 0.0, 1.0), intArrayOf(0, 4))
        val screen = doubleArrayOf(-200.0, 300.0, 900.0, 300.0)
        val rendered = FogBitmapRenderer.render(request(roads, screen, 6.0 * 0.3048))

        assertEquals(0, Color.alpha(rendered.getPixel(320, 305))) // 30 feet: fully clear.
        assertEquals(0, Color.alpha(rendered.getPixel(320, 307))) // 42 feet: same clear core.
        assertTrue(Color.alpha(rendered.getPixel(320, 315)) in 8..24) // Fade starts after 50 feet.
        assertTrue(Color.alpha(rendered.getPixel(320, 350)) in 82..102) // ~300 feet.
        assertTrue(Color.alpha(rendered.getPixel(320, 425)) in 154..172) // ~750 feet.
        assertTrue(Color.alpha(rendered.getPixel(320, 500)) in 186..198) // ~1200 feet.
        assertTrue(Color.alpha(rendered.getPixel(320, 525)) < 204)
        assertEquals(204, Color.alpha(rendered.getPixel(320, 551)))

        val samples = (310..545).map { Color.alpha(rendered.getPixel(320, it)) }
        assertTrue(samples.zipWithNext().all { (a, b) -> b >= a && b - a <= 5 })
        assertTrue(samples.distinct().size > 100)
    }

    @Test fun liveLocationClearingUsesPhysicalMetersAndFogCanRemainOpaqueElsewhere() {
        val rendered = FogBitmapRenderer.render(request(
            metersPerPixel = 2.0,
            liveLatitude = 0.0,
            liveScreen = doubleArrayOf(300.0, 300.0)
        ))
        assertEquals(0, Color.alpha(rendered.getPixel(305, 300)))
        assertTrue(Color.alpha(rendered.getPixel(320, 300)) > 0)
        assertTrue(Color.alpha(rendered.getPixel(430, 300)) in 160..180)
        assertEquals(204, Color.alpha(rendered.getPixel(600, 300)))
        assertEquals(204, Color.alpha(rendered.getPixel(20, 20)))
    }

    @Test fun bitmapScalingPreservesRoadAndLiveClearings() {
        val roads = OverlayRoads(doubleArrayOf(0.0, -1.0, 0.0, 1.0), intArrayOf(0, 4))
        val rendered = FogBitmapRenderer.render(FogBitmapRenderer.Request(
            bitmapWidth = 320,
            bitmapHeight = 320,
            screenLeft = 0f,
            screenTop = 0f,
            screenScale = 0.5f,
            roads = roads,
            roadScreen = doubleArrayOf(-200.0, 300.0, 900.0, 300.0),
            centerLatitude = 0.0,
            metersPerScreenPixelAtCenter = 3.0,
            liveLatitude = 0.0,
            liveScreen = doubleArrayOf(300.0, 300.0)
        ))
        assertEquals(0, Color.alpha(rendered.getPixel(150, 150)))
        assertEquals(204, Color.alpha(rendered.getPixel(10, 10)))
    }

    @Test fun savedVisitedPlacesStayClearWithoutLiveLocationOrRoads() {
        val rendered = FogBitmapRenderer.render(request(metersPerPixel = 2.0).copy(
            exploredCoordinates = doubleArrayOf(0.0, 0.0),
            exploredScreen = doubleArrayOf(300.0, 300.0)
        ))
        assertEquals(0, Color.alpha(rendered.getPixel(300, 300)))
        assertEquals(0, Color.alpha(rendered.getPixel(305, 300)))
        assertTrue(Color.alpha(rendered.getPixel(320, 300)) > 0)
        assertTrue(Color.alpha(rendered.getPixel(430, 300)) in 160..180)
        assertEquals(204, Color.alpha(rendered.getPixel(600, 300)))
    }

    @Test fun overlappingRoadsAndVisitedPlacesCannotPushTheFadeFartherOut() {
        val roads = OverlayRoads(doubleArrayOf(0.0, -1.0, 0.0, 1.0), intArrayOf(0, 4))
        val screen = doubleArrayOf(-200.0, 300.0, 900.0, 300.0)
        val single = FogBitmapRenderer.render(request(roads, screen, 2.0))
        val repeated = FogBitmapRenderer.render(request(
            OverlayRoads(DoubleArray(40) { roads.coordinates[it % 4] }, IntArray(11) { it * 4 }),
            DoubleArray(40) { screen[it % 4] }, 2.0
        ).copy(
            exploredCoordinates = DoubleArray(20),
            exploredScreen = DoubleArray(20) { if (it % 2 == 0) 320.0 else 300.0 },
            liveLatitude = 0.0,
            liveScreen = doubleArrayOf(320.0, 300.0)
        ))
        for (y in listOf(305, 320, 380, 450, 520, 560)) {
            assertEquals("Overlapping reveals keep the same physical fade at y=$y",
                Color.alpha(single.getPixel(320, y)), Color.alpha(repeated.getPixel(320, y)))
        }
    }

    @Test fun malformedLegacyRoadsCannotBridgeValidEndsOrCrossTheWorld() {
        for (geometry in listOf(
            "invalid",
            "[[-74,40],null,[-73,40]]",
            "[[-74,40],[181,40],[-73,40]]"
        )) {
            val road = RoadRecord("bad", "bad", geometry, 0, 0, 40.0, 40.0, -180.0, 180.0)
            assertTrue(OverlayRoads.prepare(listOf(road)).coordinates.isEmpty())
        }
        val valid = RoadRecord("valid", "valid", "[[-74,40,5],[-73,41,6]]", 0, 0, 40.0, 41.0, -74.0, -73.0)
        assertArrayEquals(
            doubleArrayOf(40.0, -74.0, 41.0, -73.0),
            OverlayRoads.prepare(listOf(valid)).coordinates,
            0.0
        )
        val normalized = requireNotNull(GeoJsonUtil.validRoadCoordinates("[[\"-74\",\"40\",5],[-73,41]]"))
        assertEquals(2, normalized.getJSONArray(0).length())
        assertTrue(normalized.getJSONArray(0).get(0) is Number)
        assertTrue(normalized.getJSONArray(0).get(1) is Number)
    }

    @Test fun dateLineRoadSplitsIntoShortWorldEdgeSegmentsInsteadOfDisappearing() {
        val road = RoadRecord(
            "date-line", "Date Line Road", "[[179.5,10],[-179.5,10.2]]",
            0, 0, 10.0, 10.2, -179.5, 179.5
        )
        val overlay = OverlayRoads.prepare(listOf(road))
        assertEquals(3, overlay.starts.size)
        assertEquals(8, overlay.coordinates.size)
        val longitudes = overlay.coordinates.filterIndexed { index, _ -> index % 2 == 1 }
        assertTrue(longitudes.contains(180.0))
        assertTrue(longitudes.contains(-180.0))
        for (part in 0 until overlay.starts.size - 1) {
            val start = overlay.starts[part]
            val end = overlay.starts[part + 1] - 2
            assertTrue(abs(overlay.coordinates[end + 1] - overlay.coordinates[start + 1]) <= 1.0)
        }
    }

    @Test fun wideZoomFogUsesSmallerUploadBitmaps() {
        assertEquals(512, FogBitmapRenderer.bitmapDimensionForZoom(3.4))
        assertEquals(896, FogBitmapRenderer.bitmapDimensionForZoom(3.5))
        assertEquals(896, FogBitmapRenderer.bitmapDimensionForZoom(11.9))
        assertEquals(1024, FogBitmapRenderer.bitmapDimensionForZoom(12.0))
        assertTrue(FogBitmapRenderer.MIN_ROAD_ZOOM > FogBitmapRenderer.MIN_FOG_REVEAL_ZOOM)
    }

    @Test fun fogAlphaIsExactlyEightyPercentAwayFromClearings() {
        assertEquals(204, alpha(320, 320, request()))
        assertEquals((FogBitmapRenderer.MAX_FOG_ALPHA * 255).toInt(), alpha(20, 20, request()))
    }

    @Test fun renderedFogRetainsVisibleCloudTextureWithUniformOpacity() {
        val rendered = FogBitmapRenderer.render(request())
        val shades = (0 until 640 step 8).flatMap { y ->
            (0 until 640 step 8).map { x ->
                assertEquals(204, Color.alpha(rendered.getPixel(x, y)))
                Color.red(rendered.getPixel(x, y))
            }
        }
        assertTrue("The native fog bitmap must contain cloud detail", shades.max() - shades.min() > 50)
    }
    @Test fun legacyEdgeFragmentsBecomeOneContinuousOverlayChain() {
        val roads = listOf(
            RoadRecord("1", "Main", "[[-74.0000,40.0000],[-74.0001,40.0000]]", 0, 0, 40.0, 40.0, -74.0001, -74.0),
            RoadRecord("2", "Main", "[[-74.0001,40.0000],[-74.0002,40.0000]]", 0, 0, 40.0, 40.0, -74.0002, -74.0001),
            RoadRecord("3", "Main", "[[-74.0002,40.0000],[-74.0003,40.0000]]", 0, 0, 40.0, 40.0, -74.0003, -74.0002)
        )
        val overlay = OverlayRoads.prepare(roads)
        assertArrayEquals(intArrayOf(0, 8), overlay.starts)
        assertEquals(8, overlay.coordinates.size)
        val forward = doubleArrayOf(
            40.0, -74.0000,
            40.0, -74.0001,
            40.0, -74.0002,
            40.0, -74.0003
        )
        assertTrue(
            overlay.coordinates.contentEquals(forward) ||
                overlay.coordinates.contentEquals(forward.toList().chunked(2).reversed().flatten().toDoubleArray())
        )
    }

    @Test fun closedLegacyFragmentsRemainOneContinuousLoop() {
        val roads = listOf(
            RoadRecord("1", "Loop", "[[-74.0000,40.0000],[-74.0001,40.0000]]", 0, 0, 40.0, 40.0, -74.0001, -74.0),
            RoadRecord("2", "Loop", "[[-74.0001,40.0000],[-74.0001,40.0001]]", 0, 0, 40.0, 40.0001, -74.0001, -74.0001),
            RoadRecord("3", "Loop", "[[-74.0001,40.0001],[-74.0000,40.0001]]", 0, 0, 40.0001, 40.0001, -74.0001, -74.0),
            RoadRecord("4", "Loop", "[[-74.0000,40.0001],[-74.0000,40.0000]]", 0, 0, 40.0, 40.0001, -74.0, -74.0)
        )
        val overlay = OverlayRoads.prepare(roads)
        assertEquals(2, overlay.starts.size)
        assertEquals(10, overlay.coordinates.size)
        assertEquals(overlay.coordinates[0], overlay.coordinates[overlay.coordinates.size - 2], 0.0)
        assertEquals(overlay.coordinates[1], overlay.coordinates[overlay.coordinates.size - 1], 0.0)
    }

    @Test fun realJunctionsRemainSeparateOverlayChains() {
        val roads = listOf(
            RoadRecord("1", "A", "[[-74.0000,40.0000],[-74.0001,40.0000]]", 0, 0, 40.0, 40.0, -74.0001, -74.0),
            RoadRecord("2", "B", "[[-74.0001,40.0000],[-74.0002,40.0001]]", 0, 0, 40.0, 40.0001, -74.0002, -74.0001),
            RoadRecord("3", "C", "[[-74.0001,40.0000],[-74.0002,39.9999]]", 0, 0, 39.9999, 40.0, -74.0002, -74.0001)
        )
        val overlay = OverlayRoads.prepare(roads)
        assertEquals(4, overlay.starts.size)
        assertEquals(12, overlay.coordinates.size)
    }

    @Test fun legacySubMeterTurnSeamIsSnappedIntoOneContinuousChain() {
        val roads = listOf(
            RoadRecord("1", "Approach", "[[-74.0000,40.0000],[-74.0001,40.0000]]", 0, 0, 40.0, 40.0, -74.0001, -74.0),
            RoadRecord("2", "Turn", "[[-74.000095,40.0000],[-74.0002,40.0001]]", 0, 0, 40.0, 40.0001, -74.0002, -74.000095)
        )
        val overlay = OverlayRoads.prepare(roads)
        assertEquals(2, overlay.starts.size)
        assertEquals(6, overlay.coordinates.size)
    }

    @Test fun legacyJunctionBranchesSnapToOneNodeWithoutBeingJoinedThroughEachOther() {
        val roads = listOf(
            RoadRecord("1", "Approach", "[[-74.0000,40.0000],[-74.0001,40.0000]]", 0, 0, 40.0, 40.0, -74.0001, -74.0),
            RoadRecord("2", "Left", "[[-74.000095,40.0000],[-74.0002,40.0001]]", 0, 0, 40.0, 40.0001, -74.0002, -74.000095),
            RoadRecord("3", "Right", "[[-74.000105,40.0000],[-74.0002,39.9999]]", 0, 0, 39.9999, 40.0, -74.0002, -74.000105)
        )
        val overlay = OverlayRoads.prepare(roads)
        assertEquals(4, overlay.starts.size)
        val junctions = (0 until overlay.starts.size - 1).map { road ->
            val start = overlay.starts[road]
            val end = overlay.starts[road + 1] - 2
            listOf(
                overlay.coordinates[start] to overlay.coordinates[start + 1],
                overlay.coordinates[end] to overlay.coordinates[end + 1]
            )
        }.flatten()
        val grouped = junctions.groupingBy { it }.eachCount()
        assertTrue("All three branches must meet at one snapped junction", grouped.values.any { it == 3 })
    }

    @Test fun legacyGapBeyondMatcherToleranceIsNotFabricatedIntoAConnection() {
        val roads = listOf(
            RoadRecord("1", "A", "[[-74.0000,40.0000],[-74.0001,40.0000]]", 0, 0, 40.0, 40.0, -74.0001, -74.0),
            RoadRecord("2", "B", "[[-74.000115,40.0000],[-74.0002,40.0001]]", 0, 0, 40.0, 40.0001, -74.0002, -74.000115)
        )
        val overlay = OverlayRoads.prepare(roads)
        assertEquals("A gap larger than the accepted matcher seam must remain two chains", 3, overlay.starts.size)
        assertEquals(8, overlay.coordinates.size)
    }

}
