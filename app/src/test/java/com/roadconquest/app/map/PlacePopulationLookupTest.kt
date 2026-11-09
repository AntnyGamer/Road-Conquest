package com.roadconquest.app.map

import com.roadconquest.app.data.PlaceKind
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class PlacePopulationLookupTest {
    @Test fun waitingLookupKeepsSameMonitorUntilAllCallersFinish() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val thirdEntered = CountDownLatch(1)
        val otherPlaceEntered = CountDownLatch(1)
        val concurrent = AtomicInteger()
        val peak = AtomicInteger()
        fun enter() {
            val count = concurrent.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
        }
        val first = Thread {
            PlacePopulationLookup.withLookupLock("same-place") {
                enter()
                firstEntered.countDown()
                try { releaseFirst.await(5, TimeUnit.SECONDS) }
                finally { concurrent.decrementAndGet() }
            }
        }
        val second = Thread {
            PlacePopulationLookup.withLookupLock("same-place") {
                enter()
                secondEntered.countDown()
                try { releaseSecond.await(5, TimeUnit.SECONDS) }
                finally { concurrent.decrementAndGet() }
            }
        }
        val third = Thread {
            PlacePopulationLookup.withLookupLock("same-place") {
                enter()
                thirdEntered.countDown()
                concurrent.decrementAndGet()
            }
        }
        val other = Thread {
            PlacePopulationLookup.withLookupLock("another-place") {
                otherPlaceEntered.countDown()
            }
        }
        val threads = listOf(first, second, third, other)
        first.start()
        try {
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            second.start()
            other.start()
            assertTrue("Unrelated places remain independent",
                otherPlaceEntered.await(5, TimeUnit.SECONDS))
            assertFalse("Second lookup must wait", secondEntered.await(100, TimeUnit.MILLISECONDS))
            // The first monitor is still reserved by the second caller when
            // first exits, even though the first request is already complete.
            releaseFirst.countDown()
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
            first.join(5_000)
            assertFalse(first.isAlive)
            third.start()
            assertFalse("Third lookup must not bypass waiting second",
                thirdEntered.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseFirst.countDown()
            releaseSecond.countDown()
            threads.filter { it.state != Thread.State.NEW }.forEach { it.join(5_000) }
        }
        assertEquals(1, peak.get())
        assertEquals(0L, thirdEntered.count)
        assertTrue(threads.none(Thread::isAlive))
    }

    @Test fun officialPlaceUsesExactGeoidFrom2020Census() {
        val info = PlaceOverlayInfo("us|new jersey|census-4-3428800", "Haddon Heights borough",
            PlaceKind.TOWN, null, 3.0)
        val request = requireNotNull(PlacePopulationLookup.requestFor(info))
        assertTrue(request.url.contains("Census2020/Places_CouSub_ConCity_SubMCD/MapServer/4/query"))
        val expected = """{"features":[{"attributes":{"STATE":"34","GEOID":"3428800","POP100":7495}}]}"""
        assertEquals(7495L, PlacePopulationLookup.parseResponse(request, expected)?.count)
        val wrong = """{"features":[{"attributes":{"STATE":"34","GEOID":"3428801","POP100":7495}}]}"""
        assertNull(PlacePopulationLookup.parseResponse(request, wrong))
    }

    @Test fun stateUsesOfficialStateGeoidAndDoesNotAcceptAnotherState() {
        val request = requireNotNull(PlacePopulationLookup.requestFor(
            PlaceOverlayInfo("us|new jersey", "New Jersey", PlaceKind.STATE, null, 22000.0)))
        assertTrue(request.url.contains("State_County/MapServer/0/query"))
        assertNull(PlacePopulationLookup.parseResponse(request,
            """{"features":[{"attributes":{"STATE":"42","GEOID":"42","POP100":13000000}}]}"""))
        assertEquals(9000000L, PlacePopulationLookup.parseResponse(request,
            """{"features":[{"attributes":{"STATE":"34","GEOID":"34","POP100":9000000}}]}""")?.count)
    }

    @Test fun foreignCountryCodeMustMatchReturnedIdentity() {
        val info = PlaceOverlayInfo("se", "Sweden", PlaceKind.COUNTRY, null, 1.0)
        val request = requireNotNull(PlacePopulationLookup.requestFor(info))
        assertEquals(10500000L, PlacePopulationLookup.parseResponse(request,
            """{"cca2":"SE","cca3":"SWE","population":10500000}""")?.count)
        assertNull(PlacePopulationLookup.parseResponse(request,
            """{"cca2":"NO","cca3":"NOR","population":5500000}"""))
    }

    @Test fun foreignPopulationRequiresAnActualWholeNumber() {
        val request = requireNotNull(PlacePopulationLookup.requestFor(
            PlaceOverlayInfo("se", "Sweden", PlaceKind.COUNTRY, null, 1.0)))
        val common = "\"cca2\":\"SE\",\"cca3\":\"SWE\""
        fun population(value: String) = "{$common,\"population\":$value}"
        assertEquals(10_500_000L, PlacePopulationLookup.parseResponse(
            request, population("10500000"))?.count)
        assertNull("A decimal must not be rounded down", PlacePopulationLookup.parseResponse(
            request, population("10500000.5")))
        assertNull("A quoted count is not a JSON number", PlacePopulationLookup.parseResponse(
            request, population("\"10500000\"")))
        assertNull(PlacePopulationLookup.parseResponse(request, population("-1")))
        assertNull(PlacePopulationLookup.parseResponse(request, population("null")))
    }

    @Test fun validatedHistoricalCensusCountCanBeCachedAcrossSessions() {
        val context = RuntimeEnvironment.getApplication()
        val info = PlaceOverlayInfo("us|new jersey|census-4-3428800", "Haddon Heights",
            PlaceKind.TOWN, null, 3.0)
        PlacePopulationLookup.clearCache(context)
        try {
            val result = requireNotNull(PlacePopulationLookup.parseResponse(
                requireNotNull(PlacePopulationLookup.requestFor(info)),
                """{"features":[{"attributes":{"STATE":"34","GEOID":"3428800","POP100":7495}}]}"""
            ))
            PlacePopulationLookup.rememberCensus(context, info, result)
            assertEquals(result, PlacePopulationLookup.savedCensus(context, info))
            val oldGeneration = PlacePopulationLookup.generation()
            PlacePopulationLookup.clearCache(context)
            PlacePopulationLookup.rememberCensus(context, info, result, oldGeneration)
            assertNull(PlacePopulationLookup.savedCensus(context, info))
            // A successful public country population must never enter the static cache.
            PlacePopulationLookup.rememberCensus(context, PlaceOverlayInfo(
                "se", "Sweden", PlaceKind.COUNTRY, null, 1.0),
                PlacePopulationLookup.Value(10_000_000L, "REST Countries"))
            assertNull(PlacePopulationLookup.savedCensus(context,
                PlaceOverlayInfo("se", "Sweden", PlaceKind.COUNTRY, null, 1.0)))
        } finally {
            PlacePopulationLookup.clearCache(context)
        }
    }

    @Test fun clearingDataDiscardsAnOlderSuccessfulPopulationResponse() {
        val context = RuntimeEnvironment.getApplication()
        val town = PlaceOverlayInfo("us|new jersey|census-4-3428800", "Haddon Heights",
            PlaceKind.TOWN, null, 3.0)
        val identity = town.kind.name + "|" + town.key
        val value = PlacePopulationLookup.Value(7495, "2020 Census")
        PlacePopulationLookup.clearCache(context)
        try {
            val requestGeneration = PlacePopulationLookup.generation()
            PlacePopulationLookup.clearCache(context)
            assertNull("A pre-reset network response must not reach the old place card",
                PlacePopulationLookup.acceptLookupResult(context, identity, true, value, requestGeneration))
            assertNull("A pre-reset response must not repopulate persistent history",
                PlacePopulationLookup.savedCensus(context, town))
            assertEquals(value, PlacePopulationLookup.acceptLookupResult(
                context, identity, true, value, PlacePopulationLookup.generation()))
            assertEquals(value, PlacePopulationLookup.savedCensus(context, town))
        } finally {
            PlacePopulationLookup.clearCache(context)
        }
    }

    @Test fun oldCachedPopulationCannotCrossADataResetEvenAfterNewResultsArrive() {
        val app = RuntimeEnvironment.getApplication()
        val identity = "COUNTRY|se"
        PlacePopulationLookup.clearCache(app)
        try {
            val oldGeneration = PlacePopulationLookup.generation()
            val before = PlacePopulationLookup.Value(10_000_000, "REST Countries")
            assertEquals(before, PlacePopulationLookup.acceptLookupResult(
                app, identity, false, before, oldGeneration))
            assertEquals(before, PlacePopulationLookup.cachedIfCurrent(identity, oldGeneration))

            // Simulate deleting local progress between the initial generation
            // check and the in-memory read, then another valid request.
            PlacePopulationLookup.clearCache(app)
            val newGeneration = PlacePopulationLookup.generation()
            val after = PlacePopulationLookup.Value(10_100_000, "REST Countries")
            assertEquals(after, PlacePopulationLookup.acceptLookupResult(
                app, identity, false, after, newGeneration))
            assertNull("A lookup from before deletion must not read the new cache",
                PlacePopulationLookup.cachedIfCurrent(identity, oldGeneration))
            assertEquals(after, PlacePopulationLookup.cachedIfCurrent(identity, newGeneration))
        } finally {
            PlacePopulationLookup.clearCache(app)
        }
    }

    @Test fun nonCensusTownNeverGetsAnotherTownPopulation() {
        assertNull(PlacePopulationLookup.requestFor(PlaceOverlayInfo(
            "us|new jersey|haddon heights", "Haddon Heights", PlaceKind.TOWN, null, 3.0)))
    }
}
