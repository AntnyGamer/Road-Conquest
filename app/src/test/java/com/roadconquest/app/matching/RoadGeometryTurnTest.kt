package com.roadconquest.app.matching

import android.location.Location
import com.roadconquest.app.data.TrackPoint
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.map.OverlayRoads
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class RoadGeometryTurnTest {
    private fun coord(lon: Double, lat: Double) = JSONArray().put(lon).put(lat)
    private val a = coord(-74.0, 40.001)
    private val b = coord(-74.0, 40.0002)
    private val c = coord(-74.0, 40.00004)
    private val junction = coord(-74.0, 40.0)
    private val d = coord(-73.99994, 40.0)
    private val e = coord(-73.9998, 40.0)
    private val wrongSide = coord(-74.00008, 40.0)
    private fun trace(group: Int, waypoint: Int, location: JSONArray, alternatives: Int = 0) = JSONObject()
        .put("matchings_index", group).put("waypoint_index", waypoint)
        .put("alternatives_count", alternatives).put("location", location)
    private fun leg(name: String, vararg coordinates: JSONArray) = JSONObject().put("steps", JSONArray().put(
        JSONObject().put("name", name).put("distance", 30).put("geometry", JSONObject()
            .put("type", "LineString").put("coordinates", JSONArray(coordinates.toList())))
    ))
    private fun matching(vararg legs: JSONObject) = matchingWithConfidence(0.95, *legs)
    private fun matchingWithConfidence(confidence: Double, vararg legs: JSONObject) =
        JSONObject().put("confidence", confidence).put("legs", JSONArray(legs.toList()))
    private fun response(traces: List<JSONObject>, vararg matchings: JSONObject) = JSONObject()
        .put("code", "Ok").put("tracepoints", JSONArray(traces))
        .put("matchings", JSONArray(matchings.toList())).toString()
    private fun points(vararg coordinates: JSONArray) = coordinates.mapIndexed { index, coordinate ->
        TrackPoint(index + 1L, coordinate.getDouble(1), coordinate.getDouble(0), 5f, 5f, 0f,
            1_000_000L + index * 3_000L, false)
    }

    @Test fun ambiguousTurnHeadIsNotSavedAndRetryFillsTheJunctionFromBothSides() {
        val repo = TrackingRepository(RuntimeEnvironment.getApplication())
        repo.readableDatabase().execSQL("DELETE FROM track_points")
        repo.readableDatabase().execSQL("DELETE FROM roads")
        val ids = listOf(a, b, c, d, e).mapIndexed { i, coordinate ->
            repo.insertLocation(Location("gps").apply {
                longitude = coordinate.getDouble(0); latitude = coordinate.getDouble(1)
                accuracy = 5f; speed = 5f; time = 1_000_000L + i * 3_000L
            })
        }
        val matcher = RoadGeometryValidator()
        val first = requireNotNull(matcher.parse(response(listOf(
            trace(0, 0, a), trace(0, 1, b), trace(1, 0, wrongSide, 2),
            trace(1, 1, d), trace(1, 2, e)
        ), matching(leg("Approach", a, b)), matching(
            leg("Exit", wrongSide, junction, d), leg("Exit", d, e)
        )), repo.loadMatchingWindow(10).points))
        assertEquals(setOf(ids[0], ids[1], ids[4]), first.matchedPointConfidences.keys)
        assertEquals(2, first.roads.size)
        assertFalse(first.roads.any { it.coordinatesJson.contains("-74.00008") })
        repo.completeMatch(first.roads, first.matchedPointConfidences.keys.toList())

        val retry = repo.loadMatchingWindow(10)
        assertEquals(listOf(ids[0], ids[1], ids[2], ids[3], ids[4]), retry.points.map { it.id })
        assertEquals(setOf(ids[2], ids[3]), retry.markableIds)
        val repaired = requireNotNull(matcher.parse(response(listOf(
            trace(0, 0, a), trace(0, 1, b), trace(0, 2, c), trace(0, 3, d), trace(0, 4, e)
        ), matching(leg("Approach", a, b), leg("Approach", b, c),
            leg("Turn", c, junction, d), leg("Exit", d, e))), retry.points))
        repo.completeMatch(repaired.roads, repaired.matchedPointConfidences.keys.filter { it in retry.markableIds })
        assertTrue(repo.loadMatchingWindow(10).points.isEmpty())
        val saved = repo.getRoadsInBounds(40.002, -73.999, 39.999, -74.001)
        assertEquals(4, saved.size)
        assertFalse(saved.any { it.geometryJson.contains("-74.00008") })
        for ((start, end) in listOf(a to b, b to c, c to junction, junction to d, d to e)) {
            assertTrue("The actual turn must have every connected edge", saved.any {
                val line = JSONArray(it.geometryJson)
                (0 until line.length() - 1).any { index ->
                    line.getJSONArray(index).toString() == start.toString() &&
                        line.getJSONArray(index + 1).toString() == end.toString()
                }
            })
        }

        val rendered = OverlayRoads.prepare(saved)
        assertEquals("The repaired turn must render as one continuous blue chain", 2, rendered.starts.size)
    }

    @Test fun ambiguousTailCannotUnlockTheRoadBeyondAnOrdinaryTurn() {
        val result = requireNotNull(RoadGeometryValidator().parse(response(listOf(
            trace(0, 0, a), trace(0, 1, b), trace(0, 2, wrongSide, 1)
        ), matching(leg("Approach", a, b), leg("Wrong exit", b, junction, wrongSide))), points(a, b, c)))
        assertEquals(setOf(1L, 2L), result.matchedPointConfidences.keys)
        assertEquals(1, result.roads.size)
        assertEquals("Approach", result.roads.single().name)
    }

    @Test fun confidentSameRoadSplitGetsASmallContinuousBridge() {
        val p0 = coord(-74.0, 40.00030)
        val p1 = coord(-74.0, 40.00020)
        val p2 = coord(-74.0, 40.00010)
        val p3 = coord(-74.0, 40.00000)
        val result = requireNotNull(
            RoadGeometryValidator().parse(
                response(
                    listOf(
                        trace(0, 0, p0), trace(0, 1, p1),
                        trace(1, 0, p2), trace(1, 1, p3)
                    ),
                    matching(leg("Main Road", p0, p1)),
                    matching(leg("Main Road", p2, p3))
                ),
                points(p0, p1, p2, p3)
            )
        )

        assertEquals(setOf(1L, 2L, 3L, 4L), result.matchedPointConfidences.keys)
        assertEquals(3, result.roads.size)
        val bridge = result.roads.single {
            it.firstTimestamp == 1_003_000L && it.lastTimestamp == 1_006_000L
        }
        val coordinates = JSONArray(bridge.coordinatesJson)
        assertEquals(p1.toString(), coordinates.getJSONArray(0).toString())
        assertEquals(p2.toString(), coordinates.getJSONArray(1).toString())
    }

    @Test fun shortNamedRoadTurnSeamUsesConfirmedEndpointsWithoutRoadCredit() {
        // Translated, synthetic geometry with the same short turn/seam shape as the
        // exported Flanders/Colonial drive. Never embed the user's private track in tests.
        val entry = coord(-74.00044, 40.00020)
        val before = coord(-74.00025, 40.00006)
        val after = coord(-74.00010, 40.00002)
        val exit = coord(-74.00002, 40.00017)
        val snappedBefore = coord(-74.000255, 40.000055)
        val snappedAfter = coord(-74.000000, 39.99998)
        val result = requireNotNull(RoadGeometryValidator().parse(response(listOf(
            trace(0, 0, entry), trace(0, 1, snappedBefore),
            trace(1, 0, snappedAfter), trace(1, 1, exit)
        ), matching(leg("Approach Road", entry, snappedBefore)),
            matching(leg("Exit Road", snappedAfter, exit))), points(entry, before, after, exit)))

        assertEquals(setOf(1L, 2L, 3L, 4L), result.matchedPointConfidences.keys)
        assertEquals(3, result.roads.size)
        val bridge = result.roads.last()
        assertEquals(
            JSONArray().put(snappedBefore).put(snappedAfter).toString(),
            bridge.coordinatesJson
        )
        assertFalse("An inferred junction may not award an extra unlocked road",
            bridge.countTowardsRoads)
        val repo = TrackingRepository(RuntimeEnvironment.getApplication())
        repo.readableDatabase().execSQL("DELETE FROM roads")
        repo.upsertRoads(result.roads)
        assertEquals(2L, repo.getSummary().roadsUnlockedCount)
        val rendered = OverlayRoads.prepare(repo.getRoadsInBounds(
            40.001, -73.999, 39.999, -74.001
        ))
        assertEquals("The two different roads and connector must form one blue line",
            2, rendered.starts.size)
    }

    @Test fun differentRoadTurnSeamStillRejectsWeakConfidenceAndLongJumps() {
        val entry = coord(-74.00044, 40.00020)
        val before = coord(-74.00025, 40.00006)
        val after = coord(-74.00010, 40.00002)
        val exit = coord(-74.00002, 40.00017)
        val snappedBefore = coord(-74.000255, 40.000055)
        val snappedAfter = coord(-74.000000, 39.99998)
        fun parse(firstConfidence: Double, rawAfter: JSONArray = after) =
            requireNotNull(RoadGeometryValidator().parse(response(listOf(
                trace(0, 0, entry), trace(0, 1, snappedBefore),
                trace(1, 0, snappedAfter), trace(1, 1, exit)
            ), matchingWithConfidence(firstConfidence, leg("Approach Road", entry, snappedBefore)),
                matching(leg("Exit Road", snappedAfter, exit))),
                points(entry, before, rawAfter, exit)))

        val weak = parse(0.69)
        assertEquals(2, weak.roads.size)
        assertFalse(3L in weak.matchedPointConfidences)

        // A larger apparent jump must not be bridged simply because two named road
        // fragments and accurate-looking OSRM tracepoints exist around it.
        val far = parse(0.95, coord(-73.99997, 40.00002))
        assertEquals(2, far.roads.size)
        assertFalse(3L in far.matchedPointConfidences)
    }

    @Test fun highConfidenceStraightUnnamedSplitGetsOnlyItsMissingShortSeam() {
        val p0 = coord(-74.0, 40.00030)
        val p1 = coord(-74.0, 40.00020)
        val p2 = coord(-74.0, 40.00010)
        val p3 = coord(-74.0, 40.00000)
        val result = requireNotNull(RoadGeometryValidator().parse(response(listOf(
            trace(0, 0, p0), trace(0, 1, p1), trace(1, 0, p2), trace(1, 1, p3)
        ), matching(leg("Unnamed road", p0, p1)),
            matching(leg("Unnamed road", p2, p3))), points(p0, p1, p2, p3)))
        assertEquals(setOf(1L, 2L, 3L, 4L), result.matchedPointConfidences.keys)
        assertEquals(3, result.roads.size)
        assertEquals(JSONArray().put(p1).put(p2).toString(),
            result.roads.last().coordinatesJson)
        assertFalse("An inferred seam must not count as an unlocked road",
            result.roads.last().countTowardsRoads)
    }

    @Test fun unnamedRoadSplitNeverDrawsAcrossARealCorner() {
        val p0 = coord(-74.0, 40.00030)
        val p1 = coord(-74.0, 40.00020)
        val p2 = coord(-73.99990, 40.00020)
        val p3 = coord(-73.99990, 40.00030)
        val result = requireNotNull(RoadGeometryValidator().parse(response(listOf(
            trace(0, 0, p0), trace(0, 1, p1), trace(1, 0, p2), trace(1, 1, p3)
        ), matching(leg("Unnamed road", p0, p1)),
            matching(leg("Unnamed road", p2, p3))), points(p0, p1, p2, p3)))
        assertEquals(2, result.roads.size)
        assertEquals(setOf(1L, 2L, 4L), result.matchedPointConfidences.keys)
    }

    @Test fun lowerConfidenceUnnamedSplitStaysPending() {
        val p0 = coord(-74.0, 40.00030)
        val p1 = coord(-74.0, 40.00020)
        val p2 = coord(-74.0, 40.00010)
        val p3 = coord(-74.0, 40.00000)
        val result = requireNotNull(RoadGeometryValidator().parse(response(listOf(
            trace(0, 0, p0), trace(0, 1, p1), trace(1, 0, p2), trace(1, 1, p3)
        ), matchingWithConfidence(0.6, leg("Unnamed road", p0, p1)),
            matchingWithConfidence(0.6, leg("Unnamed road", p2, p3))),
            points(p0, p1, p2, p3)))
        assertEquals(2, result.roads.size)
        assertFalse(3L in result.matchedPointConfidences)
    }

    @Test fun sharedSnappedEndpointResolvesWithoutSavingZeroLengthRoad() {
        val p0 = coord(-74.0, 40.00030)
        val p1 = coord(-74.0, 40.00020)
        val p3 = coord(-74.0, 40.00010)
        val result = requireNotNull(RoadGeometryValidator().parse(response(listOf(
            trace(0, 0, p0), trace(0, 1, p1), trace(1, 0, p1), trace(1, 1, p3)
        ), matching(leg("Main Road", p0, p1)),
            matching(leg("Main Road", p1, p3))), points(p0, p1, p1, p3)))
        assertEquals(setOf(1L, 2L, 3L, 4L), result.matchedPointConfidences.keys)
        assertEquals(2, result.roads.size)
    }

    @Test fun sameSnappedJunctionWithSubstantialRawTravelStaysPending() {
        val p0 = coord(-74.0, 40.00050)
        val junctionPoint = coord(-74.0, 40.00020)
        val p3 = coord(-74.0, 39.99990)
        val rawBefore = coord(-74.0, 40.00031)
        val rawAfter = coord(-74.0, 40.00009)
        val result = requireNotNull(RoadGeometryValidator().parse(response(listOf(
            trace(0, 0, p0), trace(0, 1, junctionPoint),
            trace(1, 0, junctionPoint), trace(1, 1, p3)
        ), matching(leg("Main Road", p0, junctionPoint)),
            matching(leg("Main Road", junctionPoint, p3))),
            // Even inaccurate GPS must not erase a 20+ meter recorded movement.
            points(p0, rawBefore, rawAfter, p3).map { it.copy(accuracyMeters = 25f) }))
        assertEquals(2, result.roads.size)
        assertEquals(setOf(1L, 2L, 4L), result.matchedPointConfidences.keys)
    }

    @Test fun splitTraceStartKeepsTheMissingIncomingIntervalPending() {
        val result = requireNotNull(RoadGeometryValidator().parse(response(listOf(
            trace(0, 0, a), trace(0, 1, b), trace(1, 0, d), trace(1, 1, e)
        ), matching(leg("Approach", a, b)), matching(leg("Exit", d, e))), points(a, b, d, e)))
        assertEquals(setOf(1L, 2L, 4L), result.matchedPointConfidences.keys)
        assertFalse(3L in result.matchedPointConfidences)
    }

    @Test fun entirelyAmbiguousResponseIsPartialAndSavesNothing() {
        val result = requireNotNull(RoadGeometryValidator().parse(response(listOf(
            trace(0, 0, a, 1), trace(0, 1, b, 1)
        ), matching(leg("Approach", a, b))), points(a, b)))
        assertTrue(result.roads.isEmpty())
        assertTrue(result.matchedPointConfidences.isEmpty())
    }

    @Test fun whollyDisconnectedStepGeometryKeepsTheLongFailureBackoff() {
        val badLeg = leg("Approach", a, b)
        badLeg.getJSONArray("steps").put(leg("Exit", d, e).getJSONArray("steps").getJSONObject(0))
        assertNull(
            RoadGeometryValidator().parse(response(listOf(trace(0, 0, a), trace(0, 1, e)), matching(badLeg)), points(a, e))
        )
    }

    @Test fun missingAmbiguityOrShiftedWaypointIndexCannotResolveFixes() {
        val valid = JSONObject(response(listOf(trace(0, 0, a), trace(0, 1, b)), matching(leg("Approach", a, b))))
        valid.getJSONArray("tracepoints").getJSONObject(1).remove("alternatives_count")
        assertNull(RoadGeometryValidator().parse(valid.toString(), points(a, b)))
        valid.getJSONArray("tracepoints").getJSONObject(1).put("alternatives_count", 0).put("waypoint_index", 0)
        assertNull(RoadGeometryValidator().parse(valid.toString(), points(a, b)))
    }

    @Test fun malformedIndependentMatchingCannotEraseValidSiblingOrEarnCredit() {
        val good = matching(leg("Verified Approach", a, b))
        val invalid = matching(leg("Invalid", c, d))
        val raw = response(listOf(
            trace(0, 0, a), trace(0, 1, b),
            trace(1, 0, c), trace(1, 3, d)
        ), good, invalid)
        val result = requireNotNull(RoadGeometryValidator().parse(raw, points(a, b, c, d)))
        assertEquals(listOf("Verified Approach"), result.roads.map { it.name })
        assertEquals(setOf(1L, 2L), result.matchedPointConfidences.keys)
        assertFalse(3L in result.matchedPointConfidences)
        assertFalse(4L in result.matchedPointConfidences)
        // Even though the group has two tracepoints, a wrong waypoint sequence
        // in the ONLY matching remains a failed batch rather than a credited road.
        assertNull(RoadGeometryValidator().parse(response(listOf(
            trace(0, 0, c), trace(0, 3, d)
        ), invalid), points(c, d)))
    }

    @Test fun malformedTracepointInvalidatesOnlyItsOwnMatching() {
        val response = JSONObject(response(listOf(
            trace(0, 0, a), trace(0, 1, b),
            trace(1, 0, c), trace(1, 1, d)
        ), matching(leg("Valid approach", a, b)), matching(leg("Untrusted exit", c, d))))
        response.getJSONArray("tracepoints").getJSONObject(3).put("location", "invalid")
        val result = requireNotNull(RoadGeometryValidator().parse(response.toString(), points(a, b, c, d)))
        assertEquals(listOf("Valid approach"), result.roads.map { it.name })
        assertEquals(setOf(1L, 2L), result.matchedPointConfidences.keys)

        // Missing ambiguity metadata must not let the remaining fix in that group
        // masquerade as a valid route (nor earn an invented connector).
        response.getJSONArray("tracepoints").getJSONObject(3)
            .put("location", d).remove("alternatives_count")
        val noCredit = requireNotNull(RoadGeometryValidator().parse(response.toString(), points(a, b, c, d)))
        assertEquals(setOf(1L, 2L), noCredit.matchedPointConfidences.keys)
    }

    @Test fun confidentRouteKeepsAnInternalAlternativeWhenBothSidesProvideContext() {
        val result = requireNotNull(RoadGeometryValidator().parse(response(listOf(
            trace(0, 0, a), trace(0, 1, b), trace(0, 2, c, 2),
            trace(0, 3, d), trace(0, 4, e)
        ), matching(
            leg("Approach", a, b),
            leg("Entry", b, c),
            leg("Turn", c, junction, d),
            leg("Exit", d, e)
        )), points(a, b, c, d, e)))

        assertEquals(listOf("Approach", "Entry", "Turn", "Exit"), result.roads.map { it.name })
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), result.matchedPointConfidences.keys)
        assertTrue(result.roads[2].coordinatesJson.contains(junction.toString()))
    }

    @Test fun contextualInternalAlternativeDoesNotRetryForeverAtAcceptedConfidence() {
        val result = requireNotNull(RoadGeometryValidator().parse(response(listOf(
            trace(0, 0, a), trace(0, 1, b), trace(0, 2, c, 2),
            trace(0, 3, d), trace(0, 4, e)
        ), matchingWithConfidence(
            0.60,
            leg("Approach", a, b),
            leg("Entry", b, c),
            leg("Turn", c, junction, d),
            leg("Exit", d, e)
        )), points(a, b, c, d, e)))

        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), result.matchedPointConfidences.keys)
        assertEquals(4, result.roads.size)
    }

    @Test fun contextualInternalAlternativeStillWaitsBelowAcceptedConfidence() {
        val result = requireNotNull(RoadGeometryValidator().parse(response(listOf(
            trace(0, 0, a), trace(0, 1, b), trace(0, 2, c, 2),
            trace(0, 3, d), trace(0, 4, e)
        ), matchingWithConfidence(
            0.40,
            leg("Approach", a, b),
            leg("Entry", b, c),
            leg("Turn", c, junction, d),
            leg("Exit", d, e)
        )), points(a, b, c, d, e)))

        assertFalse(3L in result.matchedPointConfidences)
    }

    @Test fun unsupportedDetourCannotTurnARecordedStraightDriveIntoAnInventedRoad() {
        val far = coord(-73.99, 40.01)
        assertNull(
            RoadGeometryValidator().parse(response(listOf(trace(0, 0, a), trace(0, 1, b)),
                matching(leg("Detour", a, far, b))), points(a, b))
        )
    }

    @Test fun missingLegStepsPreservesValidSiblingWithoutUnlockingTheMissingInterval() {
        val result = requireNotNull(RoadGeometryValidator().parse(
            response(
                listOf(trace(0, 0, a), trace(0, 1, b), trace(0, 2, c)),
                matching(leg("Approach", a, b), JSONObject())
            ),
            points(a, b, c)
        ))
        assertEquals(listOf("Approach"), result.roads.map { it.name })
        assertEquals(setOf(1L, 2L), result.matchedPointConfidences.keys)
        assertFalse(3L in result.matchedPointConfidences)
    }

    @Test fun missingStepsOnTheOnlyLegLeavesTheWholeTracePending() {
        assertNull(RoadGeometryValidator().parse(
            response(listOf(trace(0, 0, a), trace(0, 1, b)), matching(JSONObject())),
            points(a, b)
        ))
    }

    @Test fun malformedProviderStepPreservesValidatedSiblingWithoutMarkingBrokenLeg() {
        val malformed = JSONObject().put("steps", JSONArray().put("not a road step"))
        val result = requireNotNull(RoadGeometryValidator().parse(
            response(listOf(trace(0, 0, a), trace(0, 1, b), trace(0, 2, c)),
                matching(leg("Approach", a, b), malformed)),
            points(a, b, c)
        ))
        assertEquals(listOf("Approach"), result.roads.map { it.name })
        assertEquals(setOf(1L, 2L), result.matchedPointConfidences.keys)
        assertFalse(3L in result.matchedPointConfidences)
        assertTrue(result.roads.none { it.coordinatesJson.contains("not a road step") })

        // When the malformed provider leg is the only leg, keep the original
        // failure path/backoff; never invent a road to avoid a visible gap.
        assertNull(RoadGeometryValidator().parse(
            response(listOf(trace(0, 0, a), trace(0, 1, b)), matching(malformed)),
            points(a, b)
        ))
    }

    @Test fun oneBadLegKeepsOnlyThatIntervalPendingAndPreservesValidSiblingGeometry() {
        val far = coord(-73.99, 40.01)
        val result = requireNotNull(
            RoadGeometryValidator().parse(
                response(
                    listOf(trace(0, 0, a), trace(0, 1, b), trace(0, 2, c)),
                    matching(
                        leg("Approach", a, b),
                        leg("Bad sibling", b, far, c)
                    )
                ),
                points(a, b, c)
            )
        )

        assertEquals(listOf("Approach"), result.roads.map { it.name })
        assertEquals(setOf(1L, 2L), result.matchedPointConfidences.keys)
        assertFalse(3L in result.matchedPointConfidences)
    }

    @Test fun rejectedMiddleLegCannotBeReintroducedBySameRoadBridge() {
        val p0 = coord(-74.0, 40.00030)
        val p1 = coord(-74.0, 40.00020)
        val p2 = coord(-74.0, 40.00010)
        val p3 = coord(-74.0, 40.00000)
        val far = coord(-73.99, 40.01)

        val result = requireNotNull(
            RoadGeometryValidator().parse(
                response(
                    listOf(
                        trace(0, 0, p0), trace(0, 1, p1),
                        trace(0, 2, p2), trace(0, 3, p3)
                    ),
                    matching(
                        leg("Main Road", p0, p1),
                        leg("Main Road", p1, far, p2),
                        leg("Main Road", p2, p3)
                    )
                ),
                points(p0, p1, p2, p3)
            )
        )

        assertEquals(2, result.roads.size)
        assertEquals(setOf(1L, 2L, 4L), result.matchedPointConfidences.keys)
        assertFalse(3L in result.matchedPointConfidences)
        assertFalse(result.roads.any {
            it.firstTimestamp == 1_003_000L && it.lastTimestamp == 1_006_000L
        })
    }

    @Test fun sameRoadSplitTooLargeForTinySeamRepairStaysPending() {
        val p0 = coord(-74.0, 40.00030)
        val p1 = coord(-74.0, 40.00020)
        val p2 = coord(-73.99890, 40.00020)
        val p3 = coord(-73.99890, 40.00010)

        val result = requireNotNull(
            RoadGeometryValidator().parse(
                response(
                    listOf(
                        trace(0, 0, p0), trace(0, 1, p1),
                        trace(1, 0, p2), trace(1, 1, p3)
                    ),
                    matching(leg("Main Road", p0, p1)),
                    matching(leg("Main Road", p2, p3))
                ),
                points(p0, p1, p2, p3)
            )
        )

        assertEquals(2, result.roads.size)
        assertEquals(setOf(1L, 2L, 4L), result.matchedPointConfidences.keys)
        assertFalse(3L in result.matchedPointConfidences)
    }

    @Test fun legitimateLowSpeedCenterlineOffsetStillMatches() {
        val snappedStart = coord(-73.99993, 40.0)
        val snappedEnd = coord(-73.99993, 40.0001)
        val rawPoints = listOf(
            TrackPoint(1, 40.0, -74.0, 3.8f, 3.3f, 0f, 1_000_000L, false),
            TrackPoint(2, 40.0001, -74.0, 3.8f, 3.3f, 0f, 1_003_000L, false)
        )

        val result = requireNotNull(
            RoadGeometryValidator().parse(
                response(
                    listOf(trace(0, 0, snappedStart), trace(0, 1, snappedEnd)),
                    matching(leg("Main Road", snappedStart, snappedEnd))
                ),
                rawPoints
            )
        )

        assertEquals(1, result.roads.size)
        assertEquals(setOf(1L, 2L), result.matchedPointConfidences.keys)
    }

    @Test fun missingStoredSpeedDoesNotPretendNormalMovementIsStopped() {
        val rawStart = coord(-74.0, 40.0)
        val rawEnd = coord(-74.0, 40.0003)
        val snappedStart = coord(-73.99981, 40.0)
        val snappedEnd = coord(-73.99981, 40.0003)
        val rawPoints = listOf(
            // TrackingRepository stores 0 when Android did not provide a speed measurement.
            TrackPoint(1, 40.0, -74.0, 10f, 0f, 0f, 1_000_000L, false),
            TrackPoint(2, 40.0003, -74.0, 10f, 0f, 0f, 1_003_000L, false)
        )

        val result = requireNotNull(
            RoadGeometryValidator().parse(
                response(
                    listOf(trace(0, 0, snappedStart), trace(0, 1, snappedEnd)),
                    matching(leg("Main Road", snappedStart, snappedEnd))
                ),
                rawPoints
            )
        )

        assertEquals(1, result.roads.size)
        assertEquals(setOf(1L, 2L), result.matchedPointConfidences.keys)
        assertNotEquals(rawStart.toString(), snappedStart.toString())
        assertNotEquals(rawEnd.toString(), snappedEnd.toString())
    }

    @Test fun missingSpeedJitterInsideCombinedAccuracyKeepsStrictSnap() {
        val rawStart = coord(-74.0, 40.0)
        val rawEnd = coord(-74.0, 40.000135)
        val parallelStart = coord(-73.99981, 40.0)
        val parallelEnd = coord(-73.99981, 40.000135)
        val rawPoints = listOf(
            TrackPoint(1, 40.0, -74.0, 10f, 0f, 0f, 1_000_000L, false),
            TrackPoint(2, 40.000135, -74.0, 10f, 0f, 0f, 1_003_000L, false)
        )

        assertNull(
            RoadGeometryValidator().parse(
                response(
                    listOf(trace(0, 0, parallelStart), trace(0, 1, parallelEnd)),
                    matching(leg("Nearby Parallel Road", parallelStart, parallelEnd))
                ),
                rawPoints
            )
        )
        assertNotEquals(rawStart.toString(), parallelStart.toString())
        assertNotEquals(rawEnd.toString(), parallelEnd.toString())
    }

    @Test fun lowSpeedCenterlineOffsetNearElevenMetersStillMatches() {
        val snappedStart = coord(-73.999875, 40.0)
        val snappedEnd = coord(-73.999875, 40.0001)
        val rawPoints = listOf(
            TrackPoint(1, 40.0, -74.0, 10f, 3f, 0f, 1_000_000L, false),
            TrackPoint(2, 40.0001, -74.0, 10f, 3f, 0f, 1_003_000L, false)
        )

        val result = requireNotNull(
            RoadGeometryValidator().parse(
                response(
                    listOf(trace(0, 0, snappedStart), trace(0, 1, snappedEnd)),
                    matching(leg("Main Road", snappedStart, snappedEnd))
                ),
                rawPoints
            )
        )

        assertEquals(1, result.roads.size)
        assertEquals(setOf(1L, 2L), result.matchedPointConfidences.keys)
    }

    @Test fun measuredLowSpeedKeepsStrictSnapDespiteLargePositionDelta() {
        val rawStart = coord(-74.0, 40.0)
        val rawEnd = coord(-74.0, 40.0003)
        val parallelStart = coord(-73.99981, 40.0)
        val parallelEnd = coord(-73.99981, 40.0003)
        val rawPoints = listOf(
            TrackPoint(1, 40.0, -74.0, 10f, 3f, 0f, 1_000_000L, false),
            TrackPoint(2, 40.0003, -74.0, 10f, 3f, 0f, 1_003_000L, false)
        )

        assertNull(
            RoadGeometryValidator().parse(
                response(
                    listOf(trace(0, 0, parallelStart), trace(0, 1, parallelEnd)),
                    matching(leg("Nearby Parallel Road", parallelStart, parallelEnd))
                ),
                rawPoints
            )
        )
        assertNotEquals(rawStart.toString(), parallelStart.toString())
        assertNotEquals(rawEnd.toString(), parallelEnd.toString())
    }

    @Test fun nearbyParallelRoadSnapBeyondReportedAccuracyStaysPending() {
        val rawStart = coord(-74.0, 40.0)
        val rawEnd = coord(-74.0, 40.0001)
        val parallelStart = coord(-73.99981, 40.0)
        val parallelEnd = coord(-73.99981, 40.0001)
        val rawPoints = listOf(
            TrackPoint(1, 40.0, -74.0, 10f, 3f, 0f, 1_000_000L, false),
            TrackPoint(2, 40.0001, -74.0, 10f, 3f, 0f, 1_003_000L, false)
        )

        assertNull(
            RoadGeometryValidator().parse(
                response(
                    listOf(trace(0, 0, parallelStart), trace(0, 1, parallelEnd)),
                    matching(leg("Nearby Parallel Road", parallelStart, parallelEnd))
                ),
                rawPoints
            )
        )
        assertNotEquals(rawStart.toString(), parallelStart.toString())
        assertNotEquals(rawEnd.toString(), parallelEnd.toString())
    }

    @Test fun ordinaryDrivingKeepsThePreviousSnapAllowance() {
        val parallelStart = coord(-73.99981, 40.0)
        val parallelEnd = coord(-73.99981, 40.0001)
        val rawPoints = listOf(
            TrackPoint(1, 40.0, -74.0, 10f, 8f, 0f, 1_000_000L, false),
            TrackPoint(2, 40.0001, -74.0, 10f, 8f, 0f, 1_003_000L, false)
        )

        val result = requireNotNull(
            RoadGeometryValidator().parse(
                response(
                    listOf(trace(0, 0, parallelStart), trace(0, 1, parallelEnd)),
                    matching(leg("Main Road", parallelStart, parallelEnd))
                ),
                rawPoints
            )
        )
        assertEquals(1, result.roads.size)
        assertEquals(setOf(1L, 2L), result.matchedPointConfidences.keys)
    }

    @Test fun shortIntersectionEdgesRemainInTheDrawnRoute() {
        val tiny = coord(-74.0, 40.0000001)
        val result = requireNotNull(RoadGeometryValidator().parse(response(listOf(
            trace(0, 0, b), trace(0, 1, d)
        ), matching(leg("Turn", b, tiny, junction, d))), points(b, d)))
        assertEquals(1, result.roads.size)
        val coordinates = JSONArray(result.roads.single().coordinatesJson)
        assertEquals(4, coordinates.length())
        assertEquals(b.toString(), coordinates.getJSONArray(0).toString())
        assertEquals(tiny.toString(), coordinates.getJSONArray(1).toString())
        assertEquals(junction.toString(), coordinates.getJSONArray(2).toString())
        assertEquals(d.toString(), coordinates.getJSONArray(3).toString())
    }
    @Test fun dateLineCrossingUsesTheShortGeographicDistance() {
        val west = coord(179.999, 0.0)
        val east = coord(-179.999, 0.0)
        val result = requireNotNull(
            RoadGeometryValidator().parse(
                response(
                    listOf(trace(0, 0, west), trace(0, 1, east)),
                    matching(leg("Date Line Road", west, east))
                ),
                points(west, east)
            )
        )
        assertEquals(1, result.roads.size)
        assertEquals(setOf(1L, 2L), result.matchedPointConfidences.keys)
    }

}
