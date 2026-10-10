package com.roadconquest.app.map

import com.roadconquest.app.data.PlaceDiscovery
import com.roadconquest.app.data.PlaceKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.security.MessageDigest
import java.util.Locale
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class PlaceOverlayTest {
    @Test fun parserKeepsPolygonPopulationAndComputesArea() {
        val place = PlaceDiscovery(
            PlaceKind.COUNTRY, "us", "United States",
            visitedAt = 1L, latitude = 39.5, longitude = -74.5
        )
        val body = """
            [{
              "name":"United States",
              "addresstype":"country",
              "address":{"country":"United States"},
              "extratags":{"population":"331,000,000"},
              "geojson":{"type":"Polygon","coordinates":[[
                [-75.0,39.0],[-74.0,39.0],[-74.0,40.0],[-75.0,40.0],[-75.0,39.0]
              ]]}
            }]
        """.trimIndent()

        val data = PlaceOverlayClient.parseResponse(place, body)
        assertNotNull(data)
        data!!
        assertEquals("United States", data.name)
        assertEquals(331_000_000L, data.population)
        assertTrue(data.areaSquareKilometers > 8_000.0)
        assertEquals("Polygon", JSONObject(data.geometryJson).getString("type"))
    }

    @Test fun providerConfigReadIsStrictlyBounded() {
        assertEquals(
            "https://example.com",
            PlaceOverlayClient.readConfigEndpoint(StringReader("  https://example.com/  \nignored"))
        )
        org.junit.Assert.assertThrows(IOException::class.java) {
            PlaceOverlayClient.readConfigEndpoint(
                StringReader("x".repeat(2_049))
            )
        }
    }

    @Test fun previouslySavedOverlayCacheRetainsItsExactFilename() {
        val context = RuntimeEnvironment.getApplication()
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|cache-compatibility", "Cached Town",
            "New Jersey", "United States", 1L, 40.0, -74.0
        )
        PlaceOverlayCache.clear(context)
        try {
            // Use the original formatter to create the name written by earlier releases.
            val oldHash = MessageDigest.getInstance("SHA-256")
                .digest("${place.kind.name}|${place.key}".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
            val directory = File(context.filesDir, "place-overlays-v3")
            assertTrue(directory.mkdirs() || directory.isDirectory)
            File(directory, "$oldHash.json").writeText(
                JSONObject().put("found", false)
                    .put("fetched_at", System.currentTimeMillis()).toString()
            )
            val cached = PlaceOverlayCache.read(context, place)
            assertTrue(cached.cached)
            assertNull(cached.data)
        } finally {
            PlaceOverlayCache.clear(context)
        }
    }

    @Test fun staleFetchCannotRecreateOverlayCacheAfterDeletion() {
        val context = RuntimeEnvironment.getApplication()
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|old", "Old Town", "New Jersey", "United States",
            1L, 40.0, -74.5
        )
        PlaceOverlayCache.clear(context)
        val generation = PlaceOverlayCache.generation()
        PlaceOverlayCache.clear(context)

        assertFalse(
            PlaceOverlayCache.write(
                context,
                place,
                PlaceOverlayData(
                    place.key,
                    place.displayName,
                    place.kind,
                    1_000L,
                    1.0,
                    """{"type":"Polygon","coordinates":[[[-75,39],[-74,39],[-74,40],[-75,39]]]}"""
                ),
                generation
            )
        )
        assertFalse(PlaceOverlayCache.read(context, place).cached)
    }

    @Test fun sameNamedTownUsesTheBoundaryNearestTheDiscoveredLocation() {
        val place = PlaceDiscovery(
            PlaceKind.TOWN,
            "us|new jersey|washington township",
            "Washington Township",
            "New Jersey",
            "United States",
            1L,
            40.0000,
            -75.0000
        )
        val body = """
            [
              {
                "name":"Washington Township",
                "lat":"41.0000",
                "lon":"-74.0000",
                "addresstype":"administrative",
                "address":{
                  "township":"Washington Township",
                  "state":"New Jersey",
                  "country":"United States"
                },
                "extratags":{"population":"111"},
                "geojson":{"type":"Polygon","coordinates":[[
                  [-74.01,40.99],[-73.99,40.99],[-73.99,41.01],[-74.01,41.01],[-74.01,40.99]
                ]]}
              },
              {
                "name":"Washington Township",
                "lat":"40.0100",
                "lon":"-75.0100",
                "addresstype":"administrative",
                "address":{
                  "township":"Washington Township",
                  "state":"New Jersey",
                  "country":"United States"
                },
                "extratags":{"population":"222"},
                "geojson":{"type":"Polygon","coordinates":[[
                  [-75.02,40.00],[-75.00,40.00],[-75.00,40.02],[-75.02,40.02],[-75.02,40.00]
                ]]}
              }
            ]
        """.trimIndent()

        val data = PlaceOverlayClient.parseResponse(place, body)
        assertNotNull(data)
        assertEquals(222L, data!!.population)
    }

    @Test fun wrongAdministrativeResultIsRejected() {
        val place = PlaceDiscovery(
            PlaceKind.STATE, "us|new jersey", "New Jersey", "United States", "United States",
            1L, 40.0, -74.5
        )
        val body = """
            [{
              "name":"New Jersey",
              "addresstype":"shop",
              "address":{"country":"Canada","state":"Ontario"},
              "geojson":{"type":"Polygon","coordinates":[[
                [-75.0,39.0],[-74.0,39.0],[-74.0,40.0],[-75.0,40.0],[-75.0,39.0]
              ]]}
            }]
        """.trimIndent()
        assertEquals(null, PlaceOverlayClient.parseResponse(place, body))
    }
    @Test fun municipalityPolygonRejectsNearbyPostalTown() {
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|woodbury", "Woodbury",
            "New Jersey", "United States", 1L, 39.83, -75.11
        )
        val body = """
            [{
              "name":"Woodbury", "category":"boundary", "type":"administrative",
              "addresstype":"town",
              "address":{"town":"Woodbury","state":"New Jersey","country":"United States"},
              "lat":"39.84","lon":"-75.11",
              "geojson":{"type":"Polygon","coordinates":[[
                [-75.15,39.84],[-75.10,39.84],[-75.10,39.86],
                [-75.15,39.86],[-75.15,39.84]
              ]]}
            }]
        """.trimIndent()
        assertNull(PlaceOverlayClient.parseResponse(place, body))
        // An outside candidate may cache the correctly named administrative
        // boundary, but must NOT earn a visit until its own point is inside.
        val trustedShape = PlaceOverlayClient.parseResponse(place, body, requireContainment = false)
        assertNotNull(trustedShape)
        assertFalse(PlaceGeometry.contains(
            JSONObject(trustedShape!!.geometryJson), place.latitude, place.longitude
        ))
    }

    @Test fun neighborhoodPolygonNeverQualifiesForTownReward() {
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|fake", "Fake",
            "New Jersey", "United States", 1L, 39.5, -75.5
        )
        val body = """
            [{
              "name":"Fake","category":"place","type":"neighbourhood",
              "addresstype":"locality",
              "address":{"locality":"Fake","state":"New Jersey","country":"United States"},
              "lat":"39.5","lon":"-75.5",
              "geojson":{"type":"Polygon","coordinates":[[
                [-76,39],[-75,39],[-75,40],[-76,40],[-76,39]
              ]]}
            }]
        """.trimIndent()
        assertNull(PlaceOverlayClient.parseResponse(place, body))
    }

    @Test fun polygonContainmentHandlesHolesAndDisjointMunicipalities() {
        val geometry = JSONObject(
            """{"type":"MultiPolygon","coordinates":[
                [[[-75.2,39.8],[-75,39.8],[-75,40],[-75.2,40],[-75.2,39.8]],
                 [[-75.15,39.85],[-75.05,39.85],[-75.05,39.95],[-75.15,39.95],[-75.15,39.85]]],
                [[[-74.8,39.8],[-74.7,39.8],[-74.7,39.9],[-74.8,39.9],[-74.8,39.8]]]
            ]}"""
        )
        assertTrue(PlaceGeometry.contains(geometry, 39.82, -75.18))
        assertFalse(PlaceGeometry.contains(geometry, 39.90, -75.10))
        assertTrue(PlaceGeometry.contains(geometry, 39.85, -74.75))
        assertFalse(PlaceGeometry.contains(geometry, 39.85, -74.60))
    }

    @Test fun municipalityBoundaryEdgeIsRecognized() {
        val geometry = JSONObject(
            """{"type":"Polygon","coordinates":[[
                [-75.0,40.0],[-74.0,40.0],[-74.0,41.0],[-75.0,41.0],[-75.0,40.0]
            ]]}"""
        )
        assertTrue(PlaceGeometry.contains(geometry, 40.0, -75.0))
        assertTrue(PlaceGeometry.contains(geometry, 40.5, -74.5))
        assertFalse(PlaceGeometry.contains(geometry, 39.99, -74.5))
    }

    @Test fun wrongNamedBoundaryCannotUnlockTownEvenIfItContainsGps() {
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|pine hill", "Pine Hill",
            "New Jersey", "United States", 1L, 39.79, -75.03
        )
        val body = """
            [{
              "name":"Gloucester County",
              "category":"boundary",
              "type":"administrative",
              "addresstype":"administrative",
              "address":{"locality":"Pine Hill","state":"New Jersey","country":"United States"},
              "lat":"39.79","lon":"-75.03",
              "geojson":{"type":"Polygon","coordinates":[[
                [-75.1,39.7],[-75.0,39.7],[-75.0,39.9],
                [-75.1,39.9],[-75.1,39.7]
              ]]}
            }]
        """.trimIndent()
        assertNull(PlaceOverlayClient.parseResponse(place, body))
    }

    @Test fun namedBoroughBoundaryContainsActualTownGps() {
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|pine hill", "Pine Hill",
            "New Jersey", "United States", 1L, 39.79, -75.03
        )
        val body = """
            [{
              "name":"Borough of Pine Hill",
              "category":"boundary",
              "type":"administrative",
              "addresstype":"administrative",
              "address":{"town":"Pine Hill","state":"New Jersey","country":"United States"},
              "lat":"39.79","lon":"-75.03",
              "geojson":{"type":"Polygon","coordinates":[[
                [-75.1,39.7],[-75.0,39.7],[-75.0,39.9],
                [-75.1,39.9],[-75.1,39.7]
              ]]}
            }]
        """.trimIndent()
        assertNotNull(PlaceOverlayClient.parseResponse(place, body))
    }

    @Test fun legacyOverlayCacheNeverAuthorizesNewTownRewards() {
        val context = RuntimeEnvironment.getApplication()
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|legacy", "Legacy Borough",
            "New Jersey", "United States", 1L, 39.5, -75.5
        )
        val polygon = PlaceOverlayData(
            place.key, place.displayName, place.kind, null, 1.0,
            """{"type":"Polygon","coordinates":[[
                [-75.6,39.4],[-75.4,39.4],[-75.4,39.6],[-75.6,39.6],[-75.6,39.4]
            ]]}"""
        )
        PlaceOverlayCache.clear(context)
        try {
            assertTrue(PlaceOverlayCache.write(context, place, polygon))
            assertNotNull(PlaceOverlayCache.read(context, place).data)
            assertNull(PlaceOverlayCache.readVerifiedForDiscovery(context, place))
            assertTrue(PlaceOverlayCache.write(
                context, place, polygon, verifiedForDiscovery = true
            ))
            assertNotNull(PlaceOverlayCache.readVerifiedForDiscovery(context, place))
        } finally {
            PlaceOverlayCache.clear(context)
        }
    }

}
