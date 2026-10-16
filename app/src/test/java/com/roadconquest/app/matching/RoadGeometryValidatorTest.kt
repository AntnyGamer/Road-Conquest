package com.roadconquest.app.matching

import com.roadconquest.app.data.TrackPoint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class RoadGeometryValidatorTest {
    @Test fun normalGpsUncertaintyNeverOverridesPermanentSnapPlausibility() {
        // A candidate 30+ m from the recorded route remains rejected despite a high reported confidence.
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
        assertNull(RoadGeometryValidator().parse(response, recorded))
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
        val current = requireNotNull(RoadGeometryValidator().parse(
            fixture.getJSONObject("current_response").toString(), recorded.subList(2, 8)))
        assertTrue(current.matchedPointConfidences.none {
            it.key in turnIds && it.value >= RoadGeometryValidator.MIN_ACCEPTABLE_CONFIDENCE
        })

        val expanded = requireNotNull(RoadGeometryValidator().parse(
            fixture.getJSONObject("expanded_response").toString(), recorded))
        assertTrue(turnIds.all { (expanded.matchedPointConfidences[it] ?: 0.0) >=
            RoadGeometryValidator.MIN_ACCEPTABLE_CONFIDENCE })
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
        val result = requireNotNull(RoadGeometryValidator().parse(response, points))
        assertEquals(setOf(10L, 12L), result.matchedPointConfidences.keys)
        assertEquals(1, result.roads.size)
        assertEquals(3, org.json.JSONArray(result.roads.single().coordinatesJson).length())
        assertEquals(0.9, result.roads.single().confidence, 0.0)
    }

    @Test fun continuousStepKeepsTheWholeGeometryAndTimeRange() {
        val road = requireNotNull(RoadGeometryValidator().parse(response, points)).roads.single()
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
        val result = requireNotNull(RoadGeometryValidator().parse(json, points))
        assertEquals(1, result.roads.size)
        val coordinates = org.json.JSONArray(result.roads.single().coordinatesJson)
        assertEquals(3, coordinates.length())
        assertEquals("-74", coordinates.getJSONArray(0).get(0).toString())
        assertEquals("-74.002", coordinates.getJSONArray(2).get(0).toString())
    }

    @Test fun noMatchAndEmptyMatchingResponsesRemainPending() {
        assertNull(RoadGeometryValidator().parse("""{"code":"NoMatch"}""", points))
        assertNull(RoadGeometryValidator().parse("""{"code":"Ok","matchings":[]}""", points))
    }

    @Test fun matchingWithoutUsableGeometryCannotResolveItsPoints() {
        val root = org.json.JSONObject(response)
        root.getJSONArray("tracepoints").put(1, org.json.JSONObject().put("matchings_index", 1).put("waypoint_index", 0)
            .put("alternatives_count", 0).put("location", org.json.JSONArray("[-74.001,40]")))
        root.getJSONArray("matchings").put(org.json.JSONObject()
            .put("confidence", 0.95).put("legs", org.json.JSONArray()))
        val result = requireNotNull(RoadGeometryValidator().parse(root.toString(), points))
        assertFalse("A successful sibling matching cannot resolve the empty matching", 11L in result.matchedPointConfidences)
    }

    @Test fun shortenedTracepointArrayCannotResolveTheWrongInputFixes() {
        val root = org.json.JSONObject(response)
        root.put("tracepoints", org.json.JSONArray().put(org.json.JSONObject().put("matchings_index", 0))
            .put(org.json.JSONObject().put("matchings_index", 0)))
        assertNull(RoadGeometryValidator().parse(root.toString(), points))
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
        val roads = requireNotNull(RoadGeometryValidator().parse(json, turnPoints)).roads
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
        val roads = requireNotNull(RoadGeometryValidator().parse(json, turnPoints)).roads
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

        val named = requireNotNull(RoadGeometryValidator().parse(response("Victory Circle"), routePoints)).roads.single()
        assertEquals("Victory Circle", named.name)
        assertTrue(named.countTowardsRoads)

        val unnamed = requireNotNull(RoadGeometryValidator().parse(response(""), routePoints)).roads.single()
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

        val road = requireNotNull(RoadGeometryValidator().parse(json, routePoints)).roads.single()
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

        val roads = requireNotNull(RoadGeometryValidator().parse(json, routePoints)).roads
        assertEquals(2, roads.size)
        assertEquals("I-295", roads[0].name)
        assertEquals("I-295", roads[0].reference)
        assertTrue(roads[0].countTowardsRoads)
        assertEquals("Unnamed road", roads[1].name)
        assertFalse(roads[1].countTowardsRoads)
    }

}
