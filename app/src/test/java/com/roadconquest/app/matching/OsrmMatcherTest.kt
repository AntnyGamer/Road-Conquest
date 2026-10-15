package com.roadconquest.app.matching

import com.roadconquest.app.data.TrackPoint
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class OsrmMatcherTest {
    @Test fun sharpTurnDoesNotRelaxGpsUncertainty() {
        val turn = listOf(
            TrackPoint(1L, 40.0, -74.00030, 3.8f, 8f, 0f, 100_000L, false),
            TrackPoint(2L, 40.0, -74.00000, 3.8f, 8f, 0f, 103_000L, false),
            TrackPoint(3L, 40.00030, -74.00000, 3.8f, 8f, 0f, 106_000L, false)
        )
        val straight = listOf(turn[0], turn[1],
            turn[2].copy(latitude = 40.0, longitude = -73.99970))
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("""{"code":"NoMatch"}""")) }
            server.start()
            val matcher = OsrmMatcher(server.url("/").toString().trimEnd('/'))
            assertNull(matcher.match(turn))
            assertEquals("10;10;10", server.takeRequest().requestUrl?.queryParameter("radiuses"))
            assertNull(matcher.match(straight))
            assertEquals("10;10;10", server.takeRequest().requestUrl?.queryParameter("radiuses"))
        }
    }

    /**
     * Synthetic translations of two observed corner patterns: a six-fix arterial turn
     * and an eight-fix parking-access turn. No user's exact GPS position is in the test.
     */
    @Test fun sixAndEightFixTurnsCanRetryWithoutBearingsOnlyWithConfirmedRoadEnds() {
        val sixFixTurn = listOf(
            -74.00085 to 40.00065, -74.00065 to 40.000525,
            -74.00047 to 40.0004, -74.00038 to 40.000305,
            -74.00045 to 40.000175, -74.00046 to 40.0000,
            -74.00047 to 39.9998, -74.00041 to 39.99955,
            -74.00032 to 39.99932, -74.00018 to 39.9991
        )
        val eightFixTurn = listOf(
            -74.0006 to 40.00000, -74.0006 to 40.00009,
            -74.0006 to 40.00018, -74.0006 to 40.00027,
            -74.0006 to 40.00036, -74.0006 to 40.00045,
            -74.0006 to 40.00054, -74.00045 to 40.00057,
            -74.00025 to 40.00057, -74.00005 to 40.00057
        )
        for ((coords, before, gap) in listOf(
            Triple(sixFixTurn, 2, 6), Triple(eightFixTurn, 1, 8)
        )) {
            val points = coords.mapIndexed { index, coordinate ->
                TrackPoint(
                    10_000L + index, coordinate.second, coordinate.first,
                    3.8f, 9f, 0f, 100_000L + 2_000L * index,
                    index < before || index >= before + gap
                )
            }
            val waypoints = coords.map { (lon, lat) -> "[$lon,$lat]" }
            val traces = waypoints.mapIndexed { i, coord ->
                """{"matchings_index":0,"waypoint_index":$i,"alternatives_count":0,"location":$coord}"""
            }
            val legs = waypoints.zipWithNext().map { (start, end) ->
                """{"steps":[{"name":"Confirmed road","distance":18,"geometry":{"type":"LineString","coordinates":[$start,$end]}}]}"""
            }
            val completeResponse = """{"code":"Ok","tracepoints":[${traces.joinToString(",")}],
              "matchings":[{"confidence":0.97,"legs":[${legs.joinToString(",")}]}]}"""
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"code":"NoMatch"}"""))
                server.enqueue(MockResponse().setBody(completeResponse))
                server.start()
                val result = requireNotNull(OsrmMatcher(server.url("/").toString().trimEnd('/')).match(points))
                assertEquals("Long corner must receive a second attempt", 2, server.requestCount)
                assertNotNull(server.takeRequest().requestUrl?.queryParameter("bearings"))
                val retried = server.takeRequest().requestUrl
                assertNull("Only the retry omits bearing restrictions",
                    retried?.queryParameter("bearings"))
                assertEquals("ignore", retried?.queryParameter("gaps"))
                assertTrue((before until before + gap).all {
                    (result.matchedPointConfidences[points[it].id] ?: 0.0) >=
                        OsrmMatcher.MIN_ACCEPTABLE_CONFIDENCE
                })
                assertTrue(result.roads.isNotEmpty())
            }
        }
    }

    @Test fun recordedSixFixTurnRetriesDespiteAdjacentSeparateUnmatchedFix() {
        // Shifted synthetic reconstruction of an observed road-to-road corner. One
        // previously unresolved fix precedes a matched approach anchor, then six
        // consecutive unresolved turn fixes with matched exit anchors. The first
        // unrelated hole must not suppress the safe retry of the actual turn.
        val coords = listOf(
            -74.00013 to 40.00011, -74.00028 to 40.00013,
            -74.00043 to 40.00014, -74.00058 to 40.00015,
            -74.00071 to 40.00017, -74.00082 to 40.00016,
            -74.00090 to 40.00010, -74.00095 to 40.00001,
            -74.00098 to 39.99991, -74.00101 to 39.99978
        )
        val points = coords.mapIndexed { index, (lon, lat) ->
            TrackPoint(20_000L + index, lat, lon, 3.8f, 6f, 0f,
                100_000L + index * 2_000L, index == 1 || index >= 8)
        }
        val locations = coords.map { (lon, lat) -> "[$lon,$lat]" }
        val traces = locations.mapIndexed { index, point ->
            """{"matchings_index":0,"waypoint_index":$index,"alternatives_count":0,"location":$point}"""
        }
        val legs = locations.zipWithNext().mapIndexed { index, (from, to) ->
            val name = if (index < 5) "Approach Street" else "Exit Street"
            """{"steps":[{"name":"$name","distance":15,"geometry":{"type":"LineString","coordinates":[$from,$to]}}]}"""
        }
        val fullResponse = """{"code":"Ok","tracepoints":[${traces.joinToString(",")}],
            "matchings":[{"confidence":0.97,"legs":[${legs.joinToString(",")}]}]}"""

        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":"NoMatch"}"""))
            server.enqueue(MockResponse().setBody(fullResponse))
            server.start()
            val result = requireNotNull(OsrmMatcher(server.url("/").toString().trimEnd('/')).match(points))
            assertEquals("The unrelated hole must not block the supported turn retry", 2, server.requestCount)
            assertNotNull(server.takeRequest().requestUrl?.queryParameter("bearings"))
            assertNull(server.takeRequest().requestUrl?.queryParameter("bearings"))
            assertTrue((2..7).all {
                (result.matchedPointConfidences[points[it].id] ?: 0.0) >=
                    OsrmMatcher.MIN_ACCEPTABLE_CONFIDENCE
            })
            assertTrue(result.roads.isNotEmpty())
        }
    }

    @Test fun preciseAnchoredTurnCanUseHigherConfidenceAlternativeSnapWithoutDroppingExistingRoads() {
        val coords = listOf(
            -74.00060 to 40.0, -74.00030 to 40.0, -74.0 to 40.0,
            -74.0 to 40.00030, -74.0 to 40.00060
        )
        val fixes = coords.mapIndexed { i, (lon, lat) ->
            TrackPoint(i + 101L, lat, lon, 4f, 9f, 0f,
                100_000L + 3_000L * i, i != 2)
        }
        fun trace(group: Int, waypoint: Int, i: Int) =
            """{"matchings_index":$group,"waypoint_index":$waypoint,"alternatives_count":0,"location":[${coords[i].first},${coords[i].second}]}"""
        fun leg(a: Int, b: Int) =
            """{"steps":[{"name":"Known road","distance":30,"geometry":{"type":"LineString","coordinates":[[${coords[a].first},${coords[a].second}],[${coords[b].first},${coords[b].second}]]}}]}"""
        val incomplete = """{"code":"Ok","tracepoints":[${trace(0,0,0)},${trace(0,1,1)},null,
            ${trace(1,0,3)},${trace(1,1,4)}],
            "matchings":[{"confidence":0.95,"legs":[${leg(0,1)}]},
            {"confidence":0.95,"legs":[${leg(3,4)}]}]}"""
        fun complete(confidence: Double) = """{"code":"Ok",
            "tracepoints":[${coords.indices.joinToString(",") { trace(0,it,it) }}],
            "matchings":[{"confidence":$confidence,
            "legs":[${(0 until coords.lastIndex).joinToString(",") { leg(it,it+1) }}]}]}"""

        for ((confidence, shouldAccept) in listOf(0.95 to true, 0.65 to false)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(incomplete))
                server.enqueue(MockResponse().setBody(incomplete))
                server.enqueue(MockResponse().setBody(complete(confidence)))
                server.start()
                val result = requireNotNull(OsrmMatcher(server.url("/").toString().trimEnd('/')).match(fixes))
                assertEquals(3, server.requestCount)
                assertEquals("ignore", server.takeRequest().requestUrl?.queryParameter("gaps"))
                assertNull(server.takeRequest().requestUrl?.queryParameter("snapping"))
                val fallback = server.takeRequest().requestUrl
                assertEquals("any", fallback?.queryParameter("snapping"))
                assertNull(fallback?.queryParameter("bearings"))
                assertEquals(shouldAccept,
                    (result.matchedPointConfidences[fixes[2].id] ?: 0.0) >=
                        OsrmMatcher.MIN_ACCEPTABLE_CONFIDENCE)
            }
        }
    }

    @Test fun separateUnmatchedStraightIslandDoesNotTriggerUnconstrainedRetry() {
        val points = (0..9).map { i ->
            TrackPoint(i + 30_000L, 40.0, -74.0 + i * 0.00013,
                3.8f, 8f, 0f, 100_000L + 2_000L * i, i == 1 || i >= 8)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":"NoMatch"}"""))
            server.start()
            assertNull(OsrmMatcher(server.url("/").toString().trimEnd('/')).match(points))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun longStraightOrLowAccuracyHolesDoNotTriggerExtraTurnRequest() {
        val points = (0..9).map { i ->
            TrackPoint(i + 1L, 40.0, -74.0 + i * 0.00013,
                3.8f, 8f, 0f, 100_000L + 2_000L * i, i == 0 || i == 9)
        }
        for (attempt in listOf(points, points.mapIndexed { i, point ->
            if (i == 3) point.copy(accuracyMeters = 24f) else point
        })) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"code":"NoMatch"}"""))
                server.start()
                assertNull(OsrmMatcher(server.url("/").toString().trimEnd('/')).match(attempt))
                assertEquals("No additional unconstrained attempt on unproven corners",
                    1, server.requestCount)
            }
        }
    }

    @Test fun normalGpsUncertaintyNeverOverridesPermanentSnapPlausibility() {
        // A candidate 30+ m from the recorded route remains rejected despite high OSRM confidence.
        val recorded = listOf(
            TrackPoint(1L, 40.0, -74.00030, 3.8f, 8f, 0f, 100_000L, false),
            TrackPoint(2L, 40.0, -74.00000, 3.8f, 8f, 0f, 103_000L, false),
            TrackPoint(3L, 40.00030, -74.00000, 3.8f, 8f, 0f, 106_000L, false)
        )
        val response = """{"code":"Ok","tracepoints":[
          {"matchings_index":0,"waypoint_index":0,"alternatives_count":0,"location":[-74.00030,40.00030]},
          {"matchings_index":0,"waypoint_index":1,"alternatives_count":0,"location":[-74.00000,40.00030]},
          {"matchings_index":0,"waypoint_index":2,"alternatives_count":0,"location":[-74.00000,40.00060]}],
          "matchings":[{"confidence":0.99,"legs":[
          {"steps":[{"name":"Wrong road","distance":25,"geometry":{"type":"LineString","coordinates":[[-74.00030,40.00030],[-74.00000,40.00030]]}}]},
          {"steps":[{"name":"Wrong road","distance":33,"geometry":{"type":"LineString","coordinates":[[-74.00000,40.00030],[-74.00000,40.00060]]}}]}
          ]}]}"""
        assertNull(OsrmMatcher().parse(response, recorded))
    }

    @Test fun boundedUnmatchedCornerRetriesWithoutBearingConstraints() {
        val coords = listOf(
            "-74.0006,40.0", "-74.0003,40.0", "-74.0,40.0",
            "-74.0,40.0003", "-74.0,40.0006"
        )
        val fixes = coords.mapIndexed { index, value ->
            val (lon, lat) = value.split(',').map(String::toDouble)
            TrackPoint(index + 101L, lat, lon, 3.8f, 9f, 0f,
                100_000L + index * 3_000L, index != 2)
        }
        fun trace(group: Int, waypoint: Int, value: String) =
            """{"matchings_index":$group,"waypoint_index":$waypoint,"alternatives_count":0,"location":[$value]}"""
        fun leg(from: String, to: String) =
            """{"steps":[{"name":"Known road","distance":30,"geometry":{"type":"LineString","coordinates":[[$from],[$to]]}}]}"""
        val first = """{"code":"Ok","tracepoints":[
            ${trace(0, 0, coords[0])},${trace(0, 1, coords[1])},null,
            ${trace(1, 0, coords[3])},${trace(1, 1, coords[4])}],
            "matchings":[{"confidence":0.95,"legs":[${leg(coords[0], coords[1])}]},
            {"confidence":0.95,"legs":[${leg(coords[3], coords[4])}]}]}"""
        val second = """{"code":"Ok","tracepoints":[
            ${coords.mapIndexed { i, c -> trace(0, i, c) }.joinToString(",")}],
            "matchings":[{"confidence":0.95,"legs":[
            ${coords.zipWithNext().joinToString(",") { (a, b) -> leg(a, b) }}
            ]}]}"""
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(first))
            server.enqueue(MockResponse().setBody(second))
            server.start()
            val matched = requireNotNull(
                OsrmMatcher(server.url("/").toString().trimEnd('/')).match(fixes)
            )
            assertEquals(2, server.requestCount)
            assertEquals("10;10;10;10;10", server.takeRequest().requestUrl?.queryParameter("radiuses"))
            assertNull("Only the retry should omit bearings",
                server.takeRequest().requestUrl?.queryParameter("bearings"))
            assertTrue(matched.matchedPointConfidences.containsKey(fixes[2].id))
            assertTrue(matched.roads.isNotEmpty())
        }
    }

    @Test fun recordedTurnNeedsExitContextToMeetExistingConfidenceGate() {
        val fixture = org.json.JSONObject(requireNotNull(javaClass.classLoader)
            .getResourceAsStream("matching/turn17-replay.json")!!.bufferedReader().use { it.readText() })
        val raw = fixture.getJSONArray("points")
        val recorded = (0 until raw.length()).map { index ->
            val p = raw.getJSONObject(index)
            TrackPoint(p.getLong("id"), p.getDouble("latitude"), p.getDouble("longitude"),
                p.getDouble("accuracy_m").toFloat(), p.getDouble("speed_mps").toFloat(),
                p.getDouble("bearing_deg").toFloat(), p.getLong("timestamp_ms"),
                p.getInt("matched") != 0)
        }
        val turnIds = setOf(116L, 117L)
        val current = requireNotNull(OsrmMatcher().parse(
            fixture.getJSONObject("current_response").toString(), recorded.subList(2, 8)))
        assertTrue(current.matchedPointConfidences.none {
            it.key in turnIds && it.value >= OsrmMatcher.MIN_ACCEPTABLE_CONFIDENCE
        })

        val expanded = requireNotNull(OsrmMatcher().parse(
            fixture.getJSONObject("expanded_response").toString(), recorded))
        assertTrue(turnIds.all { (expanded.matchedPointConfidences[it] ?: 0.0) >=
            OsrmMatcher.MIN_ACCEPTABLE_CONFIDENCE })
        assertTrue(expanded.roads.any { it.name == "Clover Street" })
        assertTrue(expanded.roads.any { it.name == "South 13th Street" })
        // The final, ambiguous endpoint must still wait for evidence from a future drive.
        assertFalse(expanded.matchedPointConfidences.containsKey(120L))
    }

    private val points = listOf(
        TrackPoint(10, 40.0, -74.0, 5f, 5f, 0f, 100_000, false),
        TrackPoint(11, 40.0, -74.001, 5f, 5f, 0f, 110_000, false),
        TrackPoint(12, 40.0, -74.002, 5f, 5f, 0f, 120_000, false)
    )
    private val response = """
        {"code":"Ok","tracepoints":[{"matchings_index":0,"waypoint_index":0,"alternatives_count":0,"location":[-74,40]},null,{"matchings_index":0,"waypoint_index":1,"alternatives_count":0,"location":[-74.002,40]}],
         "matchings":[{"confidence":0.9,"legs":[{"steps":[
          {"name":"Main Street","distance":200,"geometry":{"type":"LineString",
           "coordinates":[[-74,40],[-74.001,40],[-74.002,40]]}}
         ]}]}]}
    """.trimIndent()

    @Test fun unmatchedTracepointIsNotMarkedResolved() {
        val result = requireNotNull(OsrmMatcher().parse(response, points))
        assertEquals(setOf(10L, 12L), result.matchedPointConfidences.keys)
        assertEquals(1, result.roads.size)
        assertEquals(3, org.json.JSONArray(result.roads.single().coordinatesJson).length())
        assertEquals(0.9, result.roads.single().confidence, 0.0)
    }

    @Test fun continuousStepKeepsTheWholeGeometryAndTimeRange() {
        val road = requireNotNull(OsrmMatcher().parse(response, points)).roads.single()
        assertEquals(100_000L, road.firstTimestamp)
        assertEquals(120_000L, road.lastTimestamp)
        assertEquals(3, org.json.JSONArray(road.coordinatesJson).length())
    }

    @Test fun adjacentLegsOnTheSameNamedRoadBecomeOneContinuousPolyline() {
        val json = """
            {"code":"Ok","tracepoints":[
              {"matchings_index":0,"waypoint_index":0,"alternatives_count":0,"location":[-74,40]},
              {"matchings_index":0,"waypoint_index":1,"alternatives_count":0,"location":[-74.001,40]},
              {"matchings_index":0,"waypoint_index":2,"alternatives_count":0,"location":[-74.002,40]}],
             "matchings":[{"confidence":0.9,"legs":[
              {"steps":[{"name":"Main Street","distance":100,"geometry":{"type":"LineString","coordinates":[[-74,40],[-74.001,40]]}}]},
              {"steps":[{"name":"Main Street","distance":100,"geometry":{"type":"LineString","coordinates":[[-74.001,40],[-74.002,40]]}}]}
             ]}]}
        """.trimIndent()
        val result = requireNotNull(OsrmMatcher().parse(json, points))
        assertEquals(1, result.roads.size)
        val coordinates = org.json.JSONArray(result.roads.single().coordinatesJson)
        assertEquals(3, coordinates.length())
        assertEquals("-74", coordinates.getJSONArray(0).get(0).toString())
        assertEquals("-74.002", coordinates.getJSONArray(2).get(0).toString())
    }

    @Test fun noMatchAndEmptyMatchingResponsesRemainPending() {
        assertNull(OsrmMatcher().parse("""{"code":"NoMatch"}""", points))
        assertNull(OsrmMatcher().parse("""{"code":"Ok","matchings":[]}""", points))
    }

    @Test fun matchingWithoutUsableGeometryCannotResolveItsPoints() {
        val root = org.json.JSONObject(response)
        root.getJSONArray("tracepoints").put(1, org.json.JSONObject().put("matchings_index", 1).put("waypoint_index", 0)
            .put("alternatives_count", 0).put("location", org.json.JSONArray("[-74.001,40]")))
        root.getJSONArray("matchings").put(org.json.JSONObject()
            .put("confidence", 0.95).put("legs", org.json.JSONArray()))
        val result = requireNotNull(OsrmMatcher().parse(root.toString(), points))
        assertFalse("A successful sibling matching cannot resolve the empty matching", 11L in result.matchedPointConfidences)
    }

    @Test fun shortenedTracepointArrayCannotResolveTheWrongInputFixes() {
        val root = org.json.JSONObject(response)
        root.put("tracepoints", org.json.JSONArray().put(org.json.JSONObject().put("matchings_index", 0))
            .put(org.json.JSONObject().put("matchings_index", 0)))
        assertNull(OsrmMatcher().parse(root.toString(), points))
    }
    @Test fun adjacentDifferentNamedTurnStepsShareTheExactSameBoundary() {
        val turnPoints = listOf(
            TrackPoint(20, 40.0, -74.0, 5f, 5f, 0f, 200_000, false),
            TrackPoint(21, 40.0001, -73.99998, 5f, 5f, 0f, 210_000, false)
        )
        val json = """
            {"code":"Ok","tracepoints":[
              {"matchings_index":0,"waypoint_index":0,"alternatives_count":0,"location":[-74,40]},
              {"matchings_index":0,"waypoint_index":1,"alternatives_count":0,"location":[-73.99998,40.0001]}],
             "matchings":[{"confidence":0.95,"legs":[{"steps":[
              {"name":"Approach","distance":6,"geometry":{"type":"LineString","coordinates":[[-74,40],[-74,40.00005]]}},
              {"name":"Turn","distance":6,"geometry":{"type":"LineString","coordinates":[[-73.999995,40.00005],[-73.99998,40.0001]]}}
             ]}]}]}
        """.trimIndent()
        val roads = requireNotNull(OsrmMatcher().parse(json, turnPoints)).roads
        assertEquals(2, roads.size)
        val approach = org.json.JSONArray(roads[0].coordinatesJson)
        val turn = org.json.JSONArray(roads[1].coordinatesJson)
        assertEquals(
            approach.getJSONArray(approach.length() - 1).toString(),
            turn.getJSONArray(0).toString()
        )
    }

    @Test fun adjacentLegsMeetOnTheExactSharedWaypointEvenWhenOsrmEndsDifferSlightly() {
        val turnPoints = listOf(
            TrackPoint(30, 40.0, -74.0, 5f, 5f, 0f, 300_000, false),
            TrackPoint(31, 40.00005, -73.99999, 5f, 5f, 0f, 310_000, false),
            TrackPoint(32, 40.0001, -73.99990, 5f, 5f, 0f, 320_000, false)
        )
        val json = """
            {"code":"Ok","tracepoints":[
              {"matchings_index":0,"waypoint_index":0,"alternatives_count":0,"location":[-74,40]},
              {"matchings_index":0,"waypoint_index":1,"alternatives_count":0,"location":[-73.99999,40.00005]},
              {"matchings_index":0,"waypoint_index":2,"alternatives_count":0,"location":[-73.99990,40.0001]}],
             "matchings":[{"confidence":0.95,"legs":[
              {"steps":[{"name":"Approach","distance":6,"geometry":{"type":"LineString","coordinates":[[-74,40],[-73.999995,40.00005]]}}]},
              {"steps":[{"name":"Exit","distance":9,"geometry":{"type":"LineString","coordinates":[[-73.999985,40.00005],[-73.99990,40.0001]]}}]}
             ]}]}
        """.trimIndent()
        val roads = requireNotNull(OsrmMatcher().parse(json, turnPoints)).roads
        assertEquals(2, roads.size)
        val approach = org.json.JSONArray(roads[0].coordinatesJson)
        val exit = org.json.JSONArray(roads[1].coordinatesJson)
        val waypoint = "[-73.99999,40.00005]"
        assertEquals(waypoint, approach.getJSONArray(approach.length() - 1).toString())
        assertEquals(waypoint, exit.getJSONArray(0).toString())
    }


    @Test fun namedRotaryCountsButUnnamedRoundaboutDoesNot() {
        val routePoints = listOf(
            TrackPoint(50, 40.0, -74.0, 5f, 5f, 0f, 500_000, false),
            TrackPoint(51, 40.0001, -73.9998, 5f, 5f, 0f, 510_000, false)
        )
        fun response(rotaryName: String) = """
            {"code":"Ok","tracepoints":[
              {"matchings_index":0,"waypoint_index":0,"alternatives_count":0,"location":[-74,40]},
              {"matchings_index":0,"waypoint_index":1,"alternatives_count":0,"location":[-73.9998,40.0001]}],
             "matchings":[{"confidence":0.95,"legs":[{"steps":[
              {"name":"","rotary_name":"$rotaryName","distance":24,"maneuver":{"type":"rotary"},
               "geometry":{"type":"LineString","coordinates":[[-74,40],[-73.9998,40.0001]]}}
             ]}]}]}
        """.trimIndent()

        val named = requireNotNull(OsrmMatcher().parse(response("Victory Circle"), routePoints)).roads.single()
        assertEquals("Victory Circle", named.name)
        assertTrue(named.countTowardsRoads)

        val unnamed = requireNotNull(OsrmMatcher().parse(response(""), routePoints)).roads.single()
        assertEquals("Unnamed road", unnamed.name)
        assertFalse(unnamed.countTowardsRoads)
    }

    @Test fun unnamedRoundaboutTurnDoesNotCountAsASeparateRoad() {
        val routePoints = listOf(
            TrackPoint(60, 40.0, -74.0, 5f, 5f, 0f, 600_000, false),
            TrackPoint(61, 40.0001, -73.9998, 5f, 5f, 0f, 610_000, false)
        )
        val json = """
            {"code":"Ok","tracepoints":[
              {"matchings_index":0,"waypoint_index":0,"alternatives_count":0,"location":[-74,40]},
              {"matchings_index":0,"waypoint_index":1,"alternatives_count":0,"location":[-73.9998,40.0001]}],
             "matchings":[{"confidence":0.95,"legs":[{"steps":[
              {"name":"","distance":24,"maneuver":{"type":"roundabout turn"},
               "geometry":{"type":"LineString","coordinates":[[-74,40],[-73.9998,40.0001]]}}
             ]}]}]}
        """.trimIndent()

        val road = requireNotNull(OsrmMatcher().parse(json, routePoints)).roads.single()
        assertFalse(road.countTowardsRoads)
    }

    @Test fun osrmRouteRefsAndRampManeuversFeedHumanRoadCounting() {
        val routePoints = listOf(
            TrackPoint(40, 40.0, -74.0, 5f, 8f, 0f, 400_000, false),
            TrackPoint(41, 40.0001, -73.9998, 5f, 8f, 0f, 410_000, false)
        )
        val json = """
            {"code":"Ok","tracepoints":[
              {"matchings_index":0,"waypoint_index":0,"alternatives_count":0,"location":[-74,40]},
              {"matchings_index":0,"waypoint_index":1,"alternatives_count":0,"location":[-73.9998,40.0001]}],
             "matchings":[{"confidence":0.95,"legs":[{"steps":[
              {"name":"","ref":"I-295","distance":12,"maneuver":{"type":"continue"},
               "geometry":{"type":"LineString","coordinates":[[-74,40],[-73.9999,40.00005]]}},
              {"name":"","ref":"","distance":12,"maneuver":{"type":"off ramp"},
               "geometry":{"type":"LineString","coordinates":[[-73.9999,40.00005],[-73.9998,40.0001]]}}
             ]}]}]}
        """.trimIndent()

        val roads = requireNotNull(OsrmMatcher().parse(json, routePoints)).roads
        assertEquals(2, roads.size)
        assertEquals("I-295", roads[0].name)
        assertEquals("I-295", roads[0].reference)
        assertTrue(roads[0].countTowardsRoads)
        assertEquals("Unnamed road", roads[1].name)
        assertFalse(roads[1].countTowardsRoads)
    }

}
