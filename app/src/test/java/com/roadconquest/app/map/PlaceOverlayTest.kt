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

    @Test fun malformedWorldwideResponseTriggersRetryButEmptyArrayDoesNot() {
        val place = PlaceDiscovery(
            PlaceKind.COUNTRY, "us", "United States",
            visitedAt = 1L, latitude = 39.5, longitude = -74.5
        )
        for (body in listOf("<html>Error</html>", "{\"error\":\"temporary\"}", "[broken")) {
            org.junit.Assert.assertThrows(IOException::class.java) {
                PlaceOverlayClient.parseResponse(place, body)
            }
        }
        assertNull(PlaceOverlayClient.parseResponse(place, "[]"))
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

    @Test fun verificationFlagIsReadWithTheCachedMunicipalPolygon() {
        val context = RuntimeEnvironment.getApplication()
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|verified-cache-test", "Verified Cache Town",
            "New Jersey", "United States", 1234L, 40.0, -74.0
        )
        val data = PlaceOverlayData(
            place.key, place.displayName, place.kind, null, 1.0,
            """{"type":"Polygon","coordinates":[[
                [-74.02,39.99],[-73.98,39.99],[-73.98,40.01],
                [-74.02,40.01],[-74.02,39.99]
            ]]}"""
        )
        PlaceOverlayCache.clear(context)
        try {
            assertTrue(PlaceOverlayCache.write(context, place, data))
            assertNotNull(PlaceOverlayCache.read(context, place).data)
            assertNull(PlaceOverlayCache.readVerifiedForDiscovery(context, place))

            assertTrue(PlaceOverlayCache.write(
                context, place, data, verifiedForDiscovery = true
            ))
            val verified = PlaceOverlayCache.readVerifiedForDiscovery(context, place)
            assertEquals(JSONObject(data.geometryJson).toString(), verified?.geometryJson)
        } finally {
            PlaceOverlayCache.clear(context)
        }
    }

    @Test fun lateNegativeCensusBoundaryResultCannotEraseVerifiedTownGeometry() {
        val context = RuntimeEnvironment.getApplication()
        val town = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|census-4-3401234", "Sample borough",
            "New Jersey", "United States", 1_000L, 39.9, -75.1
        )
        val geometry = """{"type":"Polygon","coordinates":[[
            [-75.2,39.8],[-75.0,39.8],[-75.0,40.0],
            [-75.2,40.0],[-75.2,39.8]
        ]]}"""
        val verified = PlaceOverlayData(town.key, town.displayName, town.kind, null, 3.0, geometry)
        PlaceOverlayCache.clear(context)
        try {
            val generation = PlaceOverlayCache.generation()
            // In production, an overlay fetch begins before discovery resolves
            // the town; its eventual null response must not replace this success.
            assertTrue(PlaceOverlayCache.writeOfficialUs(context, town, verified))
            assertTrue(PlaceOverlayCache.writeOfficialUs(context, town, null, generation))
            assertEquals(JSONObject(verified.geometryJson).toString(),
                PlaceOverlayCache.readOfficialUs(context, town).data?.geometryJson)
            // A negative response still caches normally if there was no polygon.
            val absent = town.copy(key = "us|new jersey|census-4-3405678")
            assertTrue(PlaceOverlayCache.writeOfficialUs(context, absent, null, generation))
            val negative = PlaceOverlayCache.readOfficialUs(context, absent)
            assertTrue(negative.cached)
            assertNull(negative.data)
        } finally {
            PlaceOverlayCache.clear(context)
        }
    }

    @Test fun staleWorldwideOverlayCannotDowngradeVerifiedDiscoveryPolygon() {
        val context = RuntimeEnvironment.getApplication()
        val town = PlaceDiscovery(
            PlaceKind.TOWN, "gb|region|verified-borough", "Verified borough",
            "Region", "United Kingdom", 1_000L, 51.5, -0.1
        )
        val verified = PlaceOverlayData(town.key, town.displayName, town.kind, null, 2.0,
            """{"type":"Polygon","coordinates":[[
                [-0.2,51.4],[0.0,51.4],[0.0,51.6],
                [-0.2,51.6],[-0.2,51.4]
            ]]}""")
        val unverified = verified.copy(areaSquareKilometers = 50.0)
        PlaceOverlayCache.clear(context)
        try {
            assertTrue(PlaceOverlayCache.write(
                context, town, verified, verifiedForDiscovery = true
            ))
            assertTrue(PlaceOverlayCache.write(context, town, unverified))
            assertTrue(PlaceOverlayCache.write(context, town, null))
            assertEquals(2.0,
                PlaceOverlayCache.readVerifiedForDiscovery(context, town)?.areaSquareKilometers
                    ?: -1.0, 0.0)
        } finally {
            PlaceOverlayCache.clear(context)
        }
    }

    @Test fun legacyV3CacheCannotReintroduceBadTownOutlines() {
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
            assertFalse(cached.cached)
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
        // The foreground Census discovery worker must honor the same reset
        // generation as the map renderer. A pre-deletion network response is
        // not allowed to recreate the user's erased location-related cache.
        val official = place.copy(key = "us|new jersey|census-4-3401234")
        val boundary = PlaceOverlayData(
            official.key, official.displayName, official.kind, null, 1.0,
            """{"type":"Polygon","coordinates":[[
                [-75,39],[-74,39],[-74,40],[-75,39]
            ]]}"""
        )
        assertFalse(PlaceOverlayCache.writeOfficialUs(context, official, boundary, generation))
        assertFalse(PlaceOverlayCache.readOfficialUs(context, official).cached)
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
                "category":"boundary","type":"administrative",
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
                "category":"boundary","type":"administrative",
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

    @Test fun townshipBoundaryNeverMatchesAdjacentCityWithSameBaseName() {
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|gloucester township",
            "Gloucester Township", "New Jersey", "United States",
            1L, 39.86, -75.10
        )
        val wrongCity = """
            [{
              "name":"Gloucester City",
              "category":"boundary","type":"administrative",
              "addresstype":"administrative",
              "address":{"city":"Gloucester City","state":"New Jersey","country":"United States"},
              "lat":"39.86","lon":"-75.10",
              "geojson":{"type":"Polygon","coordinates":[[
                [-75.2,39.8],[-75.0,39.8],[-75.0,39.9],[-75.2,39.9],[-75.2,39.8]
              ]]}
            }]
        """.trimIndent()
        assertNull(PlaceOverlayClient.parseResponse(place, wrongCity))
        assertNull(PlaceOverlayClient.parseResponse(place, wrongCity, requireContainment = false))
    }

    @Test fun townCannotUseCountyAdministrativeBoundary() {
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|gloucester",
            "Gloucester", "New Jersey", "United States",
            1L, 39.86, -75.10
        )
        val county = """
            [{
              "name":"Gloucester",
              "category":"boundary","type":"administrative",
              "addresstype":"administrative",
              "extratags":{"admin_level":"6"},
              "address":{"county":"Gloucester","state":"New Jersey","country":"United States"},
              "lat":"39.86","lon":"-75.10",
              "geojson":{"type":"Polygon","coordinates":[[
                [-75.2,39.8],[-75.0,39.8],[-75.0,39.9],[-75.2,39.9],[-75.2,39.8]
              ]]}
            }]
        """.trimIndent()
        assertNull(PlaceOverlayClient.parseResponse(place, county))
    }

    @Test fun boroughPrefixAndSuffixRemainEquivalent() {
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|pine hill borough",
            "Pine Hill Borough", "New Jersey", "United States",
            1L, 39.79, -75.03
        )
        val borough = """
            [{
              "name":"Borough of Pine Hill",
              "category":"boundary","type":"administrative",
              "addresstype":"administrative",
              "address":{"town":"Pine Hill","state":"New Jersey","country":"United States"},
              "lat":"39.79","lon":"-75.03",
              "geojson":{"type":"Polygon","coordinates":[[
                [-75.1,39.7],[-75.0,39.7],[-75.0,39.9],
                [-75.1,39.9],[-75.1,39.7]
              ]]}
            }]
        """.trimIndent()
        assertNotNull(PlaceOverlayClient.parseResponse(place, borough))
    }

    @Test fun redundantCityOfGloucesterCityNameStillMatchesGloucesterCity() {
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|gloucester city",
            "Gloucester City", "New Jersey", "United States",
            1L, 39.89, -75.11
        )
        val polygon = """
            [{
              "name":"City of Gloucester City",
              "category":"boundary","type":"administrative",
              "addresstype":"administrative",
              "address":{"city":"Gloucester City","state":"New Jersey","country":"United States"},
              "lat":"39.89","lon":"-75.11",
              "geojson":{"type":"Polygon","coordinates":[[
                [-75.2,39.8],[-75.0,39.8],[-75.0,39.9],[-75.2,39.9],[-75.2,39.8]
              ]]}
            }]
        """.trimIndent()
        assertNotNull(PlaceOverlayClient.parseResponse(place, polygon))
    }

    @Test fun worldwideTownOverlayAcceptsOnlyAppropriateAdministrativeLevel() {
        fun town(name: String, country: String) = PlaceDiscovery(
            PlaceKind.TOWN, "global|state|${name.lowercase()}", name,
            "Region", country, 1L, 21.0, -101.0
        )
        fun record(name: String, level: Int, relation: String = "relation") = """
            [{
              "name":"$name","category":"boundary","type":"administrative",
              "osm_type":"$relation",
              "addresstype":"administrative",
              "extratags":{"admin_level":"$level"},
              "address":{"state":"Region","country":"Mexico","town":"$name"},
              "lat":"21.0","lon":"-101.0",
              "geojson":{"type":"Polygon","coordinates":[[
                [-101.5,20.5],[-100.5,20.5],[-100.5,21.5],
                [-101.5,21.5],[-101.5,20.5]
              ]]}
            }]
        """.trimIndent()
        assertNotNull(PlaceOverlayClient.parseResponse(town("Guanajuato", "Mexico"),
            record("Guanajuato", 6)))
        assertNull(PlaceOverlayClient.parseResponse(town("Guanajuato", "Mexico"),
            record("Guanajuato", 4)))
        assertNull(PlaceOverlayClient.parseResponse(town("Guanajuato", "Mexico"),
            record("Guanajuato", 8, "node")))
    }

    @Test fun worldwideOsmDoesNotAcceptPostalNeighborhoodAsMunicipalBoundary() {
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "gb|england|sample", "Sample",
            "England", "United Kingdom", 1L, 52.0, -1.0
        )
        val body = """
            [{"name":"Sample", "category":"place", "type":"neighbourhood",
              "addresstype":"locality","address":{"country":"United Kingdom"},
              "geojson":{"type":"Polygon","coordinates":[[
                [-1.5,51.5],[-0.5,51.5],[-0.5,52.5],
                [-1.5,52.5],[-1.5,51.5]
              ]]}}]
        """.trimIndent()
        assertNull(PlaceOverlayClient.parseResponse(place, body))
    }

}
