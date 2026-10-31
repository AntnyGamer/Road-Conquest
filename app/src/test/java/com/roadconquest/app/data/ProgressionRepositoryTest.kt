package com.roadconquest.app.data

import com.roadconquest.app.achievements.Achievements
import com.roadconquest.app.progression.AdRewardBridge
import com.roadconquest.app.progression.ProgressionManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class ProgressionRepositoryTest {
    private val context: android.content.Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        AppDatabase::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
        ProgressionRepository(context).clearProgression()
        Achievements.reset(context)
    }

    @Test fun resetLocalProgressionAlsoClearsAchievementPreferenceState() {
        com.roadconquest.app.util.Prefs.setCarStyle(context, "sport")
        com.roadconquest.app.util.Prefs.setCarColor(context, "red")
        com.roadconquest.app.util.Prefs.setRoadColor(context, "gold")
        com.roadconquest.app.util.Prefs.setGoldUiEnabled(context, true)
        com.roadconquest.app.util.Prefs.setPlaceOverlayMode(context, com.roadconquest.app.map.PlaceOverlayMode.TOWN)
        Achievements.progress(context, DataSummary(0, 0, null, null, 0.0, 100))
        assertTrue(
            Achievements.progress(context, DataSummary(0, 0, null, null, 0.0, 0))
                .first { it.id == "roads_10" }.unlocked
        )

        ProgressionManager.resetLocalProgression(context)

        assertFalse(
            Achievements.progress(context, DataSummary(0, 0, null, null, 0.0, 0))
                .first { it.id == "roads_10" }.unlocked
        )
        assertEquals("classic", com.roadconquest.app.util.Prefs.carStyle(context))
        assertEquals("blue", com.roadconquest.app.util.Prefs.carColor(context))
        assertEquals("blue", com.roadconquest.app.util.Prefs.roadColor(context))
        assertFalse(com.roadconquest.app.util.Prefs.isGoldUiEnabled(context))
        assertEquals(com.roadconquest.app.map.PlaceOverlayMode.NONE, com.roadconquest.app.util.Prefs.placeOverlayMode(context))
    }

    @Test fun roadAndAchievementPointsAreIdempotentAndNeverRetract() {
        val progression = ProgressionRepository(context)
        progression.syncRoadRewards(10)
        assertEquals(20L, progression.snapshot().balance)

        progression.syncRoadRewards(10)
        progression.syncRoadRewards(8)
        assertEquals(20L, progression.snapshot().balance)
        assertEquals(10L, progression.snapshot().rewardedRoads)

        assertTrue(progression.awardAchievement("test", 100))
        assertFalse(progression.awardAchievement("test", 100))
        assertEquals(120L, progression.snapshot().balance)
    }

    @Test fun municipalityNameAliasesCannotRewardTheSameTownTwice() {
        val progression = ProgressionRepository(context)
        val first = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|pine hill", "Pine Hill",
            "New Jersey", "United States", 1000L, 39.791, -75.032
        )
        val alias = first.copy(
            key = "us|new jersey|pine hill borough",
            displayName = "Borough of Pine Hill",
            visitedAt = 2000L,
            latitude = 39.793
        )
        assertTrue(progression.recordPlace(first))
        assertFalse(progression.recordPlace(alias))
        assertEquals(1L, progression.snapshot().towns)
        assertEquals(35L, progression.snapshot().balance)
        assertEquals(1, progression.visitedPlaces(PlaceKind.TOWN).size)
    }

    @Test fun missingGeocoderCountryFieldsDoNotDuplicateHistoricalUsRewards() {
        val repository = ProgressionRepository(context)
        val oldCountry = PlaceDiscovery(
            PlaceKind.COUNTRY, "united states", "United States",
            visitedAt = 1000L, latitude = 39.80, longitude = -75.1
        )
        assertTrue(repository.recordPlace(oldCountry))
        assertFalse(repository.recordPlace(oldCountry.copy(
            key = "us", visitedAt = 2000L
        )))
        assertEquals(1L, repository.snapshot().countries)

        val oldState = PlaceDiscovery(
            PlaceKind.STATE, "usa|nj", "NJ", "USA", "USA",
            1000L, 39.80, -75.1
        )
        assertTrue(repository.recordPlace(oldState))
        assertFalse(repository.recordPlace(oldState.copy(
            key = "us|new jersey", displayName = "New Jersey",
            parentName = "United States", countryName = "United States",
            visitedAt = 2000L
        )))
        assertEquals(1L, repository.snapshot().states)

        val oldTown = PlaceDiscovery(
            PlaceKind.TOWN, "usa|nj|census-4-3401234", "Sample borough",
            "NJ", "USA", 1000L, 39.80, -75.1
        )
        assertTrue(repository.recordPlace(oldTown))
        assertFalse(repository.recordPlace(oldTown.copy(
            key = "us|new jersey|census-4-3401234",
            parentName = "New Jersey", countryName = "United States",
            visitedAt = 2000L
        )))
        assertEquals(1L, repository.snapshot().towns)
    }

    @Test fun legacyPostalStateAndOfficialTownKeysDoNotEarnDuplicateVisitPoints() {
        val repository = ProgressionRepository(context)
        val legacyState = PlaceDiscovery(
            PlaceKind.STATE, "us|nj", "NJ", "United States", "United States",
            1_000L, 39.8, -75.1
        )
        val normalizedState = legacyState.copy(
            key = "us|new jersey", displayName = "New Jersey", visitedAt = 2_000L
        )
        assertTrue(repository.recordPlace(legacyState))
        assertFalse(repository.recordPlace(normalizedState))
        assertEquals(1L, repository.snapshot().states)

        val old = PlaceDiscovery(
            PlaceKind.TOWN, "us|nj|census-4-3401234", "Sample borough",
            "NJ", "United States", 1_000L, 39.80, -75.1
        )
        val normalized = old.copy(
            key = "us|new jersey|census-4-3401234",
            parentName = "New Jersey", visitedAt = 2_000L, latitude = 39.81
        )
        assertTrue(repository.recordPlace(old))
        assertFalse(repository.recordPlace(normalized))
        assertEquals(1L, repository.snapshot().towns)
        assertEquals(2, repository.visitedPlaces(PlaceKind.TOWN).count { it.key.contains("census-4-3401234") } +
            repository.visitedPlaces(PlaceKind.STATE).count { it.displayName == "NJ" })
    }

    @Test fun officialIdsPreventNearbySameNameMunicipalitiesBeingMerged() {
        val progression = ProgressionRepository(context)
        val first = PlaceDiscovery(
            PlaceKind.TOWN, "us|pennsylvania|census-1-4200300123",
            "Washington township", "Pennsylvania", "United States",
            1_000L, 40.00, -75.10
        )
        val second = first.copy(
            key = "us|pennsylvania|census-1-4200500456",
            visitedAt = 2_000L, latitude = 40.003, longitude = -75.102
        )
        assertTrue(progression.recordPlace(first))
        assertTrue(progression.recordPlace(second))
        assertEquals(70L, progression.snapshot().balance)
        assertEquals(2L, progression.snapshot().towns)
        assertEquals(2, progression.visitedPlaces(PlaceKind.TOWN).size)
        assertFalse(progression.recordPlace(first.copy(visitedAt = 3_000L)))
        assertEquals(70L, progression.snapshot().balance)
    }

    @Test fun separateCensusGeoidsStaySeparateEvenWhenParentStateSpellingChanges() {
        val repository = ProgressionRepository(context)
        val first = PlaceDiscovery(
            PlaceKind.TOWN, "us|nj|census-1-3400300012", "Washington township",
            "NJ", "United States", 1_000L, 39.80, -75.1
        )
        val second = first.copy(
            key = "us|new jersey|census-1-3400500034",
            parentName = "New Jersey", visitedAt = 2_000L,
            latitude = 39.805
        )
        assertTrue(repository.recordPlace(first))
        assertTrue(repository.recordPlace(second))
        assertEquals(2L, repository.snapshot().towns)
        assertEquals(2, repository.visitedPlaces(PlaceKind.TOWN).size)
    }

    @Test fun legacyNameBasedVisitIsReusedOnlyIfInsideNewOfficialPolygon() {
        val progression = ProgressionRepository(context)
        val legacy = PlaceDiscovery(
            PlaceKind.TOWN, "us|pennsylvania|sample borough",
            "Sample borough", "Pennsylvania", "United States",
            1_000L, 40.000, -75.100
        )
        val sameTown = legacy.copy(
            key = "us|pennsylvania|census-4-4201234",
            visitedAt = 2_000L, latitude = 40.003, longitude = -75.102
        )
        fun cache(place: PlaceDiscovery, coordinates: String) {
            assertTrue(com.roadconquest.app.map.PlaceOverlayCache.writeOfficialUs(
                context, place, com.roadconquest.app.map.PlaceOverlayData(
                    place.key, place.displayName, place.kind, null, 1.0, coordinates
                )
            ))
        }
        assertTrue(progression.recordPlace(legacy))
        val enclosing = """{"type":"Polygon","coordinates":[[
            [-75.12,39.99],[-75.08,39.99],[-75.08,40.02],
            [-75.12,40.02],[-75.12,39.99]
        ]]}"""
        cache(sameTown, enclosing)
        assertFalse("Same verified town must not earn a second reward",
            progression.recordPlace(sameTown))
        assertEquals(listOf(legacy.key),
            progression.visitedPlaces(PlaceKind.TOWN).map { it.key })
        assertEquals(35L, progression.snapshot().balance)

        val differentTown = sameTown.copy(
            key = "us|pennsylvania|census-4-4205678",
            visitedAt = 3_000L, latitude = 40.003, longitude = -75.102
        )
        val disjoint = """{"type":"Polygon","coordinates":[[
            [-75.11,40.002],[-75.09,40.002],[-75.09,40.02],
            [-75.11,40.02],[-75.11,40.002]
        ]]}"""
        cache(differentTown, disjoint)
        assertTrue("New municipality must not be folded into nearby old same-name visit",
            progression.recordPlace(differentTown))
        assertEquals(70L, progression.snapshot().balance)
        assertEquals(2L, progression.snapshot().towns)
    }

    @Test fun newPlacesPayOnceAndPurchasesCannotOverspend() {
        val progression = ProgressionRepository(context)
        val town = PlaceDiscovery(
            PlaceKind.TOWN, "us|nj|glassboro", "Glassboro", "New Jersey", "United States",
            1_000L, 39.7, -75.1
        )
        assertTrue(progression.recordPlace(town))
        assertFalse(progression.recordPlace(town))
        assertEquals(35L, progression.snapshot().balance)
        assertEquals(1L, progression.snapshot().towns)

        assertEquals(PurchaseResult.INSUFFICIENT_POINTS, progression.purchase("too_expensive", 36))
        assertEquals(PurchaseResult.PURCHASED, progression.purchase("affordable", 20))
        assertEquals(PurchaseResult.OWNED, progression.purchase("affordable", 20))
        assertEquals(15L, progression.snapshot().balance)
        assertEquals(20L, progression.snapshot().pointsSpent)
    }

    @Test fun townsStatesAndCountriesKeepFirstAndLastVisitsWithoutRepeatPoints() {
        val progression = ProgressionRepository(context)
        val places = listOf(
            PlaceDiscovery(PlaceKind.TOWN, "us|nj|paulsboro", "Paulsboro", "New Jersey", "United States",
                2_000L, 39.83, -75.24),
            PlaceDiscovery(PlaceKind.STATE, "us|nj", "New Jersey", "United States", "United States",
                2_000L, 39.83, -75.24),
            PlaceDiscovery(PlaceKind.COUNTRY, "us", "United States", "", "United States",
                2_000L, 39.83, -75.24)
        )
        for (place in places) {
            assertNull(progression.placeVisitTimes(place.kind, place.key))
            assertTrue(progression.recordPlace(place))
            assertEquals(PlaceVisitTimes(2_000L, 2_000L),
                progression.placeVisitTimes(place.kind, place.key))
        }
        val balance = progression.snapshot().balance
        for (place in places) {
            assertFalse(progression.recordPlace(place.copy(visitedAt = 6_000L)))
            assertEquals(PlaceVisitTimes(2_000L, 6_000L),
                progression.placeVisitTimes(place.kind, place.key))
            assertFalse(progression.recordPlace(place.copy(visitedAt = 3_000L)))
            assertEquals(PlaceVisitTimes(2_000L, 6_000L),
                progression.placeVisitTimes(place.kind, place.key))
            // Historical candidate may be resolved after more recent visits.
            assertFalse(progression.recordPlace(place.copy(visitedAt = 1_500L)))
            assertEquals(PlaceVisitTimes(1_500L, 6_000L),
                progression.placeVisitTimes(place.kind, place.key))
        }
        assertEquals(balance, progression.snapshot().balance)
        assertEquals(3, places.size)
        progression.clearProgression()
        assertTrue(places.all { progression.placeVisitTimes(it.kind, it.key) == null })
    }

    @Test fun resolvingSameTownStateAndCountryTwiceRefreshesTheirLastVisit() {
        val progression = ProgressionRepository(context)
        val initial = listOf(
            PlaceDiscovery(PlaceKind.TOWN, "us|nj|town", "Town", "New Jersey", "United States", 1_000L, 39.8, -75.2),
            PlaceDiscovery(PlaceKind.STATE, "us|nj", "New Jersey", "United States", "United States", 1_000L, 39.8, -75.2),
            PlaceDiscovery(PlaceKind.COUNTRY, "us", "United States", "", "United States", 1_000L, 39.8, -75.2)
        )
        val baseline = android.location.Location("gps").apply {
            latitude = 39.8; longitude = -75.2; accuracy = 3f; time = 1_000L
        }
        assertTrue(progression.recordBaselineCandidate(baseline))
        val firstCandidate = progression.pendingPlaceCandidates(nowMillis = 2_000L).single()
        assertEquals(0, progression.resolveCandidate(firstCandidate, initial))
        val followUp = firstCandidate.copy(cellX = 123L, cellY = 456L, visitedAt = 7_000L)
        assertEquals(0, progression.resolveCandidate(followUp, initial.map { it.copy(visitedAt = 7_000L) }))
        for (place in initial) {
            assertEquals(PlaceVisitTimes(1_000L, 7_000L),
                progression.placeVisitTimes(place.kind, place.key))
        }
        progression.snapshot().let {
            assertEquals(0L, it.balance)
            assertEquals(0L, it.towns)
            assertEquals(0L, it.states)
            assertEquals(0L, it.countries)
            assertEquals(1L, it.exploredTowns)
            assertEquals(1L, it.exploredStates)
            assertEquals(1L, it.exploredCountries)
        }
        assertEquals(1_000L, progression.startingLocation()?.recordedAt)
    }

    @Test fun firstResolvedPlacesAfterResetBecomeUnrewardedBaseline() {
        val progression = ProgressionRepository(context)
        val fix = android.location.Location("gps").apply {
            latitude = 39.7
            longitude = -75.1
            accuracy = 5f
            time = 1_000L
        }
        assertTrue(progression.recordBaselineCandidate(fix))
        progression.startingLocation()!!.let {
            assertEquals(39.7, it.latitude, 0.0)
            assertEquals(-75.1, it.longitude, 0.0)
            assertEquals(1_000L, it.recordedAt)
        }
        val candidate = progression.pendingPlaceCandidates(nowMillis = 2_000L).single()
        val baseline = listOf(
            PlaceDiscovery(PlaceKind.COUNTRY, "us", "United States", visitedAt = 1_000L, latitude = 39.7, longitude = -75.1),
            PlaceDiscovery(PlaceKind.STATE, "us|new jersey", "New Jersey", "United States", "United States", 1_000L, 39.7, -75.1),
            PlaceDiscovery(PlaceKind.TOWN, "us|new jersey|glassboro", "Glassboro", "New Jersey", "United States", 1_000L, 39.7, -75.1)
        )

        assertEquals(0, progression.resolveCandidate(candidate, baseline))
        progression.snapshot().let {
            assertEquals(0L, it.balance)
            assertEquals(0L, it.towns)
            assertEquals(0L, it.states)
            assertEquals(0L, it.countries)
        }
        assertEquals(listOf("United States"), progression.visitedPlaces(PlaceKind.COUNTRY).map { it.displayName })
        assertEquals(listOf("New Jersey"), progression.visitedPlaces(PlaceKind.STATE).map { it.displayName })
        assertEquals(listOf("Glassboro"), progression.visitedPlaces(PlaceKind.TOWN).map { it.displayName })
        progression.startingLocation()!!.let {
            assertEquals(39.7, it.latitude, 0.0)
            assertEquals(-75.1, it.longitude, 0.0)
            assertEquals(1_000L, it.recordedAt)
        }

        val nextTown = baseline.take(2) + PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|pitman", "Pitman", "New Jersey", "United States",
            2_000L, 39.73, -75.13
        )
        assertEquals(
            1,
            progression.resolveCandidate(
                candidate.copy(cellX = 11, visitedAt = 2_000L, latitude = 39.73, longitude = -75.13),
                nextTown
            )
        )
        progression.snapshot().let {
            assertEquals(35L, it.balance)
            assertEquals(1L, it.towns)
            assertEquals(0L, it.states)
            assertEquals(0L, it.countries)
        }
    }

    @Test fun stalePreResetWorkCannotRestoreProgressAfterDeletion() {
        val tracking = TrackingRepository(context)
        tracking.upsertRoads(
            listOf(
                MatchedRoad(
                    "Before reset",
                    "[[-74.0,40.0],[-74.001,40.0]]",
                    1_000L,
                    2_000L,
                    1.0
                )
            )
        )
        val staleSummary = tracking.getSummary()
        assertEquals(1L, staleSummary.roadsUnlockedCount)

        // Simulate a long-lived/queued progression writer that existed before deletion.
        val staleProgression = ProgressionRepository(context)
        tracking.clearHistory()

        ProgressionManager.sync(context, staleSummary)
        // MainActivity performs announcement calculation after progression sync. That second
        // stale-summary consumer must also be unable to recreate preference-backed progress.
        assertTrue(Achievements.newlyUnlocked(context, staleSummary).isEmpty())
        assertEquals(0L, ProgressionRepository(context).snapshot().balance)
        assertEquals(0L, ProgressionRepository(context).snapshot().rewardedRoads)
        assertEquals(
            0L,
            context.getSharedPreferences("roadconquest_achievements", 0)
                .getLong("max_roads_seen", 0L)
        )

        val queuedLocation = android.location.Location("gps").apply {
            latitude = 39.9
            longitude = -75.0
            accuracy = 5f
            time = 3_000L
        }
        assertFalse(staleProgression.recordBaselineCandidate(queuedLocation))
        assertTrue(ProgressionRepository(context).pendingPlaceCandidates(nowMillis = 4_000L).isEmpty())
    }

    @Test fun emptyBaselineResolutionsCannotEraseTheFirstStartingFix() {
        val progression = ProgressionRepository(context)
        val first = android.location.Location("gps").apply {
            latitude = 39.7
            longitude = -75.1
            accuracy = 5f
            time = 1_000L
        }
        assertTrue(progression.recordBaselineCandidate(first))
        repeat(4) {
            val pending = progression.pendingPlaceCandidates(nowMillis = Long.MAX_VALUE).single()
            assertEquals(Long.MIN_VALUE, pending.cellX)
            assertEquals(0, progression.resolveCandidate(pending, emptyList()))
        }

        // Unresolved geocoding must never let a restart redefine the initial fix.
        val later = android.location.Location("gps").apply {
            latitude = 40.0
            longitude = -75.0
            accuracy = 5f
            time = 2_000L
        }
        assertFalse(progression.recordBaselineCandidate(later))
        assertTrue(progression.recordPlaceCandidate(later))
        assertEquals(first.latitude, progression.startingLocation()!!.latitude, 0.0)
        assertEquals(first.longitude, progression.startingLocation()!!.longitude, 0.0)
        assertEquals(first.time, progression.startingLocation()!!.recordedAt)
        assertEquals(Long.MIN_VALUE,
            progression.pendingPlaceCandidates(nowMillis = Long.MAX_VALUE).single().cellX)

        // Once at least one place is resolved, the bounded partial-baseline
        // fallback may release queued discoveries without losing their visits.
        val baseline = progression.pendingPlaceCandidates(nowMillis = Long.MAX_VALUE).single()
        assertEquals(0, progression.resolveCandidate(
            baseline,
            listOf(PlaceDiscovery(
                PlaceKind.COUNTRY, "us", "United States",
                visitedAt = baseline.visitedAt,
                latitude = baseline.latitude, longitude = baseline.longitude
            ))
        ))
        val queued = progression.pendingPlaceCandidates(nowMillis = Long.MAX_VALUE).single()
        assertEquals(later.time, queued.visitedAt)
        assertEquals(later.latitude, queued.latitude, 0.0)
        assertEquals(first.time, progression.startingLocation()!!.recordedAt)
        assertEquals(0L, progression.snapshot().balance)
    }

    @Test fun verifiedStartingTownResolvedLateCannotEarnItsOwnDiscoveryReward() {
        val progression = ProgressionRepository(context)
        val start = android.location.Location("gps").apply {
            latitude = 39.7; longitude = -75.1; accuracy = 5f; time = 1_000L
        }
        assertTrue(progression.recordBaselineCandidate(start))
        repeat(3) {
            val baseline = progression.pendingPlaceCandidates(nowMillis = Long.MAX_VALUE).single()
            val partial = listOf(
                PlaceDiscovery(PlaceKind.COUNTRY, "us", "United States",
                    visitedAt = start.time, latitude = start.latitude, longitude = start.longitude),
                PlaceDiscovery(PlaceKind.STATE, "us|new jersey", "New Jersey",
                    "United States", "United States", start.time, start.latitude, start.longitude)
            )
            assertEquals(0, progression.resolveCandidate(baseline, partial))
        }
        assertTrue(progression.hasMissingStartingTown())
        val later = android.location.Location("gps").apply {
            latitude = 39.705; longitude = -75.095; accuracy = 5f; time = 5_000L
        }
        assertTrue(progression.recordPlaceCandidate(later))
        val candidate = progression.pendingPlaceCandidates(nowMillis = Long.MAX_VALUE).single()
        val town = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|starting borough", "Starting borough",
            "New Jersey", "United States", later.time, later.latitude, later.longitude
        )
        fun cachedPolygon(place: PlaceDiscovery, json: String) {
            val overlay = com.roadconquest.app.map.PlaceOverlayData(
                place.key, place.displayName, place.kind, null, 1.0, json
            )
            assertTrue(com.roadconquest.app.map.PlaceOverlayCache.writeOfficialUs(context, place, overlay))
        }
        // A different verified municipality must NOT be mistaken for the start town.
        val unrelated = town.copy(key = "us|new jersey|unrelated borough",
            displayName = "Unrelated borough")
        cachedPolygon(unrelated, """{"type":"Polygon","coordinates":[[
            [-75.01,39.99],[-74.99,39.99],[-74.99,40.01],
            [-75.01,40.01],[-75.01,39.99]
        ]]}""")
        ProgressionManager.reconcileMissingStartingTown(context, progression, listOf(unrelated))
        assertTrue(progression.hasMissingStartingTown())
        assertTrue(progression.visitedPlaces(PlaceKind.TOWN).isEmpty())

        // This municipality really includes the original first live fix.
        cachedPolygon(town, """{"type":"Polygon","coordinates":[[
            [-75.11,39.69],[-75.09,39.69],[-75.09,39.71],
            [-75.11,39.71],[-75.11,39.69]
        ]]}""")
        // Even containing geometry is insufficient when an incorporated
        // place takes precedence over an overlapping county subdivision.
        ProgressionManager.reconcileMissingStartingTown(
            context, progression, listOf(town)
        ) { unrelated }
        assertTrue(progression.hasMissingStartingTown())
        ProgressionManager.reconcileMissingStartingTown(
            context, progression, listOf(town)
        ) { pointAtStart ->
            assertEquals(start.latitude, pointAtStart.latitude, 0.0)
            assertEquals(start.longitude, pointAtStart.longitude, 0.0)
            town
        }
        assertFalse(progression.hasMissingStartingTown())
        assertEquals(0, progression.resolveCandidate(candidate, listOf(town)))
        assertEquals(0L, progression.snapshot().balance)
        assertEquals(0L, progression.snapshot().towns)
        assertEquals(listOf(town.key),
            progression.visitedPlaces(PlaceKind.TOWN).map { it.key })
        assertEquals(PlaceVisitTimes(start.time, later.time),
            progression.placeVisitTimes(PlaceKind.TOWN, town.key))
        assertEquals(start.time, progression.startingLocation()!!.recordedAt)
    }

    @Test fun failedBaselineGeocodingRetriesPromptly() {
        val progression = ProgressionRepository(context)
        val fix = android.location.Location("gps").apply {
            latitude = 39.9
            longitude = -75.0
            accuracy = 5f
            time = 1_000L
        }
        assertTrue(progression.recordBaselineCandidate(fix))
        val baseline = progression.pendingPlaceCandidates(nowMillis = 2_000L).single()

        progression.deferCandidate(baseline, nowMillis = 10_000L)
        assertEquals(70_000L, progression.nextPlaceCandidateAttemptMillis())

        assertTrue(progression.pendingPlaceCandidates(nowMillis = 69_999L).isEmpty())
        val retry = progression.pendingPlaceCandidates(nowMillis = 70_000L).single()
        assertEquals(1, retry.attempts)
        assertEquals(baseline.latitude, retry.latitude, 0.0)
        assertEquals(baseline.longitude, retry.longitude, 0.0)
    }

    @Test fun freshBaselineUsesExactFirstLiveFixAndBlocksOrdinaryCandidatesUntilThen() {
        val progression = ProgressionRepository(context)

        // Ordinary sparse discoveries may already exist, but they cannot decide a fresh baseline.
        val later = android.location.Location("gps").apply {
            latitude = 40.1234
            longitude = -75.1234
            time = 2_000L
            accuracy = 5f
        }
        assertTrue(progression.recordPlaceCandidate(later))
        assertNull(progression.nextPlaceCandidateAttemptMillis())
        assertTrue(progression.pendingPlaceCandidates(nowMillis = 3_000L).isEmpty())

        // The first good live fix is stored exactly rather than snapped to the 2 km candidate grid.
        val startFix = android.location.Location("gps").apply {
            latitude = 39.987654
            longitude = -74.876543
            time = 3_000L
            accuracy = 5f
        }
        assertTrue(progression.recordBaselineCandidate(startFix))
        assertEquals(0L, progression.nextPlaceCandidateAttemptMillis())
        val baseline = progression.pendingPlaceCandidates(nowMillis = 4_000L).single()
        assertEquals(startFix.latitude, baseline.latitude, 0.0)
        assertEquals(startFix.longitude, baseline.longitude, 0.0)
        assertEquals(startFix.time, baseline.visitedAt)

        assertEquals(
            0,
            progression.resolveCandidate(
                baseline,
                listOf(
                    PlaceDiscovery(PlaceKind.COUNTRY, "us", "United States",
                        visitedAt = baseline.visitedAt, latitude = baseline.latitude, longitude = baseline.longitude),
                    PlaceDiscovery(PlaceKind.STATE, "us|new jersey", "New Jersey", "United States", "United States",
                        baseline.visitedAt, baseline.latitude, baseline.longitude),
                    PlaceDiscovery(PlaceKind.TOWN, "us|new jersey|start", "Start", "New Jersey", "United States",
                        baseline.visitedAt, baseline.latitude, baseline.longitude)
                )
            )
        )
        progression.snapshot().let {
            assertEquals(0L, it.balance)
            assertEquals(0L, it.towns)
            assertEquals(0L, it.states)
            assertEquals(0L, it.countries)
        }

        // The ordinary candidate from before the first live baseline is deliberately removed:
        // it cannot define or inherit the zero-point starting location.
        assertTrue(progression.pendingPlaceCandidates(nowMillis = 4_000L).isEmpty())
        assertNull(progression.nextPlaceCandidateAttemptMillis())
        later.time = 5_000L
        assertTrue(progression.recordPlaceCandidate(later))
        assertEquals(0L, progression.nextPlaceCandidateAttemptMillis())
        val next = progression.pendingPlaceCandidates(nowMillis = 6_000L).single()
        assertEquals(
            1,
            progression.resolveCandidate(
                next,
                listOf(
                    PlaceDiscovery(PlaceKind.TOWN, "us|new jersey|later", "Later", "New Jersey", "United States",
                        next.visitedAt, next.latitude, next.longitude)
                )
            )
        )
        assertEquals(35L, progression.snapshot().balance)
        assertEquals(1L, progression.snapshot().towns)
        progression.startingLocation()!!.let {
            assertEquals(startFix.latitude, it.latitude, 0.0)
            assertEquals(startFix.longitude, it.longitude, 0.0)
            assertEquals(startFix.time, it.recordedAt)
        }
    }

    @Test fun unresolvedBaselineSurvivesRestartWithoutDroppingQueuedVisits() {
        val progression = ProgressionRepository(context)
        val first = android.location.Location("gps").apply {
            latitude = 39.7
            longitude = -75.1
            accuracy = 5f
            time = 1_000L
        }
        val later = android.location.Location("gps").apply {
            latitude = 40.0
            longitude = -75.0
            accuracy = 5f
            time = 2_000L
        }
        val restartFix = android.location.Location("gps").apply {
            latitude = 39.8
            longitude = -75.2
            accuracy = 5f
            time = 3_000L
        }

        assertTrue(progression.recordBaselineCandidate(first))
        assertTrue(progression.recordPlaceCandidate(later))
        assertFalse("A restart must not replace the first exact baseline", progression.recordBaselineCandidate(restartFix))

        val baseline = progression.pendingPlaceCandidates(nowMillis = 4_000L).single()
        assertEquals(Long.MIN_VALUE, baseline.cellX)
        assertEquals(first.latitude, baseline.latitude, 0.0)
        assertEquals(first.longitude, baseline.longitude, 0.0)
        progression.resolveCandidate(
            baseline,
            listOf(
                PlaceDiscovery(PlaceKind.COUNTRY, "us", "United States",
                    visitedAt = baseline.visitedAt, latitude = baseline.latitude, longitude = baseline.longitude),
                PlaceDiscovery(PlaceKind.STATE, "us|new jersey", "New Jersey", "United States", "United States",
                    baseline.visitedAt, baseline.latitude, baseline.longitude),
                PlaceDiscovery(PlaceKind.TOWN, "us|new jersey|start", "Start", "New Jersey", "United States",
                    baseline.visitedAt, baseline.latitude, baseline.longitude)
            )
        )

        val queued = progression.pendingPlaceCandidates(nowMillis = 5_000L).single()
        assertEquals(later.time, queued.visitedAt)
        assertEquals(later.latitude, queued.latitude, 0.01)
        assertEquals(later.longitude, queued.longitude, 0.01)
    }

    @Test fun retiredBatteryAchievementsNeverGrantNewPointsOrEraseRecordedMinimum() {
        assertTrue(ProgressionManager.recordBatteryPercent(context, 5))
        assertEquals(0L, ProgressionRepository(context).snapshot().balance)
        assertFalse(ProgressionManager.recordBatteryPercent(context, 5))
        assertTrue(ProgressionManager.recordBatteryPercent(context, 1))
        assertTrue(ProgressionManager.recordBatteryPercent(context, 0))
        assertEquals(0L, ProgressionRepository(context).snapshot().balance)
        assertEquals(0, ProgressionRepository(context).snapshot().lowestBatteryPercent)
        assertTrue(ProgressionRepository(context).snapshot().rewardedAchievements.isEmpty())
    }

    @Test fun completedAdHookUnlocksRequestedFiveAndTenViewMilestones() {
        repeat(4) { assertTrue(AdRewardBridge(context, 10L).onCompletedAd()) }
        assertEquals(40L, ProgressionRepository(context).snapshot().balance)
        val fifth = AdRewardBridge(context, 10L)
        assertTrue(fifth.onCompletedAd())
        assertFalse(fifth.onCompletedAd())
        assertEquals(100L, ProgressionRepository(context).snapshot().balance)

        repeat(5) { assertTrue(AdRewardBridge(context, 10L).onCompletedAd()) }
        val snapshot = ProgressionRepository(context).snapshot()
        assertEquals(10L, snapshot.adsWatched)
        assertEquals(250L, snapshot.balance)

        val pending = AdRewardBridge(context, 10L)
        TrackingRepository(context).clearHistory()
        ProgressionRepository(context).clearProgression()
        assertFalse(pending.onCompletedAd())
        assertFalse(fifth.onCompletedAd())
        val reset = ProgressionRepository(context).snapshot()
        assertEquals(0L, reset.adsWatched)
        assertEquals(0L, reset.balance)
    }

    @Test fun existingRewardLedgerKeepsHistoricAchievementPayoutAfterRebalance() {
        val repository = ProgressionRepository(context)
        // Previously claimed achievements keep their ledger totals, even after milestone values change.
        assertTrue(repository.awardAchievement("battery_1", 1_000))
        assertTrue(repository.awardAchievement("ads_5", 100))
        ProgressionManager.recordBatteryPercent(context, 1)
        repeat(5) { assertTrue(AdRewardBridge(context, 10L).onCompletedAd()) }
        // A pre-existing battery award stays intact; no NEW 5% award is minted.
        assertEquals(1_100L + 5L * 10L, repository.snapshot().balance)
        assertTrue("battery_1" in repository.snapshot().rewardedAchievements)
        assertTrue("ads_5" in repository.snapshot().rewardedAchievements)
        val earned = Achievements.progress(
            DataSummary(0, 0, null, null),
            ProgressionManager.metrics(repository.snapshot())
        )
        assertEquals(1_000L, earned.first { it.id == "battery_1" }.rewardPoints)
        assertEquals(100L, earned.first { it.id == "ads_5" }.rewardPoints)
        // New players must not see or be awarded the retired battery milestone.
        assertFalse(Achievements.progress(DataSummary(0, 0, null, null))
            .any { it.id == "battery_1" || it.id == "battery_5" })
    }

    @Test fun partialBaselineKeepsRetryingMissingKindsWithoutAwardingThem() {
        val progression = ProgressionRepository(context)
        val fix = android.location.Location("gps").apply {
            latitude = 39.7
            longitude = -75.1
            accuracy = 5f
            time = 1_000L
        }
        assertTrue(progression.recordBaselineCandidate(fix))
        val baseline = progression.pendingPlaceCandidates(nowMillis = 2_000L).single()

        assertEquals(
            0,
            progression.resolveCandidate(
                baseline,
                listOf(
                    PlaceDiscovery(PlaceKind.COUNTRY, "us", "United States",
                        visitedAt = baseline.visitedAt, latitude = baseline.latitude, longitude = baseline.longitude),
                    PlaceDiscovery(PlaceKind.STATE, "us|new jersey", "New Jersey", "United States", "United States",
                        baseline.visitedAt, baseline.latitude, baseline.longitude)
                )
            )
        )
        val retryTime = System.currentTimeMillis() + 61_000L
        val retry = progression.pendingPlaceCandidates(nowMillis = retryTime)
            .first { it.cellX == Long.MIN_VALUE && it.cellY == Long.MIN_VALUE }
        assertEquals(
            0,
            progression.resolveCandidate(
                retry,
                listOf(
                    PlaceDiscovery(PlaceKind.TOWN, "us|new jersey|glassboro", "Glassboro", "New Jersey", "United States",
                        retry.visitedAt, retry.latitude, retry.longitude)
                )
            )
        )
        assertEquals(listOf("Glassboro"), progression.visitedPlaces(PlaceKind.TOWN).map { it.displayName })
        assertEquals(0L, progression.snapshot().towns)
        assertEquals(0L, progression.snapshot().balance)
    }

    @Test fun partialBaselineTemporarilyBlocksRewardCandidatesThenReleasesAfterRepeatedPartialResults() {
        val progression = ProgressionRepository(context)
        val start = android.location.Location("gps").apply {
            latitude = 39.7
            longitude = -75.1
            accuracy = 5f
            time = 1_000L
        }
        assertTrue(progression.recordBaselineCandidate(start))
        val later = android.location.Location("gps").apply {
            latitude = 40.0
            longitude = -75.0
            accuracy = 5f
            time = 2_000L
        }
        assertTrue(progression.recordPlaceCandidate(later))
        // The queued reward candidate exists but the baseline sentinel is the only candidate
        // allowed to leave the repository while zero-point resolution is incomplete.
        assertEquals(
            Long.MIN_VALUE,
            progression.pendingPlaceCandidates(nowMillis = 2_000L).single().cellX
        )

        var candidate = progression.pendingPlaceCandidates(nowMillis = 2_000L).single()
        val partial = listOf(
            PlaceDiscovery(
                PlaceKind.COUNTRY, "us", "United States",
                visitedAt = candidate.visitedAt, latitude = candidate.latitude, longitude = candidate.longitude
            ),
            PlaceDiscovery(
                PlaceKind.STATE, "us|new jersey", "New Jersey", "United States", "United States",
                candidate.visitedAt, candidate.latitude, candidate.longitude
            )
        )
        progression.resolveCandidate(candidate, partial)
        candidate = progression.pendingPlaceCandidates(nowMillis = System.currentTimeMillis() + 61_000L)
            .first { it.cellX == Long.MIN_VALUE }
        progression.resolveCandidate(candidate, partial)
        candidate = progression.pendingPlaceCandidates(nowMillis = System.currentTimeMillis() + 3 * 60_000L)
            .first { it.cellX == Long.MIN_VALUE }
        progression.resolveCandidate(candidate, partial)

        val released = progression.pendingPlaceCandidates(nowMillis = Long.MAX_VALUE)
        assertTrue(released.none { it.cellX == Long.MIN_VALUE })
        assertEquals(1, released.size)
        assertEquals(later.time, released.single().visitedAt)
        assertEquals(
            1,
            progression.resolveCandidate(
                released.single(),
                listOf(
                    PlaceDiscovery(
                        PlaceKind.TOWN, "us|pennsylvania|queued", "Queued Town",
                        "Pennsylvania", "United States", released.single().visitedAt,
                        released.single().latitude, released.single().longitude
                    )
                )
            )
        )
        assertEquals(35L, progression.snapshot().balance)
        assertEquals(1L, progression.snapshot().towns)
    }

    @Test fun mockLocationCannotQueuePlaceProgression() {
        val progression = ProgressionRepository(context)
        val mock = android.location.Location("gps").apply {
            latitude = 40.0
            longitude = -75.0
            accuracy = 5f
            time = 2_000L
            isMock = true
        }
        assertFalse(progression.recordPlaceCandidate(mock))
        assertFalse(progression.recordBaselineCandidate(mock))
        assertTrue(progression.pendingPlaceCandidates(nowMillis = Long.MAX_VALUE).isEmpty())
    }


    @Test fun precisePlaceCandidateStaysInsideLongitudeRangeAtDateLine() {
        val progression = ProgressionRepository(context)
        val start = android.location.Location("gps").apply {
            latitude = 0.0
            longitude = 0.0
            accuracy = 5f
            time = 1_000L
        }
        assertTrue(progression.recordBaselineCandidate(start))
        val baseline = progression.pendingPlaceCandidates(nowMillis = 2_000L).single()
        progression.resolveCandidate(
            baseline,
            listOf(
                PlaceDiscovery(PlaceKind.COUNTRY, "xx", "Example Country",
                    visitedAt = baseline.visitedAt, latitude = baseline.latitude, longitude = baseline.longitude),
                PlaceDiscovery(PlaceKind.STATE, "xx|region", "Example Region", "Example Country", "Example Country",
                    baseline.visitedAt, baseline.latitude, baseline.longitude),
                PlaceDiscovery(PlaceKind.TOWN, "xx|region|start", "Start", "Example Region", "Example Country",
                    baseline.visitedAt, baseline.latitude, baseline.longitude)
            )
        )

        val nearDateLine = android.location.Location("gps").apply {
            latitude = 0.0
            longitude = 179.9999
            accuracy = 5f
            time = 3_000L
        }
        assertTrue(progression.recordPlaceCandidate(nearDateLine))
        val candidate = progression.pendingPlaceCandidates(nowMillis = 4_000L).single()
        assertTrue(candidate.longitude in -180.0..180.0)
        assertTrue(kotlin.math.abs(kotlin.math.abs(candidate.longitude) - 180.0) < 0.05)
        assertEquals("Resolve the actual driven coordinate, not a nearby grid center",
            nearDateLine.latitude, candidate.latitude, 0.0)
        assertEquals(nearDateLine.longitude, candidate.longitude, 1e-8)
    }


    @Test fun oldDrivenFixesBackfillInBoundedPagesWithoutGrantingUnverifiedTownPoints() {
        val progression = ProgressionRepository(context)
        val database = AppDatabase.get(context).writableDatabase
        database.execSQL("DELETE FROM track_points")
        assertEquals("Never replay before the exact first-location baseline is established",
            0, progression.backfillPlaceCandidates(2))

        val start = android.location.Location("gps").apply {
            latitude = 39.987654
            longitude = -74.876543
            accuracy = 5f
            time = 1_000L
        }
        assertTrue(progression.recordBaselineCandidate(start))
        val baseline = progression.pendingPlaceCandidates(nowMillis = 2_000L).single()
        progression.resolveCandidate(
            baseline, listOf(
                PlaceDiscovery(PlaceKind.COUNTRY, "us", "United States",
                    visitedAt = 1_000L, latitude = start.latitude, longitude = start.longitude),
                PlaceDiscovery(PlaceKind.STATE, "us|nj", "New Jersey",
                    "United States", "United States", 1_000L, start.latitude, start.longitude),
                PlaceDiscovery(PlaceKind.TOWN, "us|nj|start", "Start",
                    "New Jersey", "United States", 1_000L, start.latitude, start.longitude)
            )
        )
        fun storedFix(latitude: Double, longitude: Double, accuracy: Int, whenMs: Long) {
            database.execSQL(
                """INSERT INTO track_points(latitude, longitude, accuracy_m, speed_mps,
                   bearing_deg, timestamp_ms) VALUES (?, ?, ?, 10, 0, ?)""",
                arrayOf(latitude, longitude, accuracy, whenMs)
            )
        }
        // Earlier driving history survives a progression-only reset. It must not
        // earn old towns after a newer zero-point baseline.
        storedFix(39.97, -75.045, 5, 500L)
        storedFix(39.96, -75.015, 5, 3_000L)
        storedFix(39.96, -75.006, 75, 4_000L) // Not a trustworthy visit.
        storedFix(39.96, -75.002, 5, 5_000L)
        try {
            assertEquals(1, progression.backfillPlaceCandidates(1))
            assertEquals(1, progression.backfillPlaceCandidates(1))
            assertEquals("A durable cursor must prevent rerecording old drives",
                0, progression.backfillPlaceCandidates(1))
            assertEquals("GPS data alone must NEVER grant points before place resolution",
                0L, progression.snapshot().balance)

            val candidates = progression.pendingPlaceCandidates(limit = 8, nowMillis = 6_000L)
            assertEquals(2, candidates.size)
            assertTrue(candidates.any {
                it.latitude == 39.96 && kotlin.math.abs(it.longitude + 75.002) < 1e-8
            })
            assertTrue(candidates.any {
                it.latitude == 39.96 && kotlin.math.abs(it.longitude + 75.015) < 1e-8
            })
            assertFalse("Never recover places driven before the new baseline",
                candidates.any { it.latitude == 39.97 })
            assertEquals(1, progression.resolveCandidate(
                candidates.first(),
                listOf(PlaceDiscovery(PlaceKind.TOWN, "us|nj|newtown", "Newtown",
                    "New Jersey", "United States", 5_000L, 39.96, -75.002))
            ))
            assertEquals(35L, progression.snapshot().balance)
            assertEquals(0, progression.resolveCandidate(
                candidates.last(),
                listOf(PlaceDiscovery(PlaceKind.TOWN, "us|nj|newtown", "Newtown",
                    "New Jersey", "United States", 3_000L, 39.96, -75.015))
            ))
            assertEquals(35L, progression.snapshot().balance)
        } finally {
            database.execSQL("DELETE FROM track_points")
        }
    }

    @Test fun duplicateHistoricalCellsDoNotStopOlderTownBackfill() {
        val progression = ProgressionRepository(context)
        val database = AppDatabase.get(context).writableDatabase
        database.execSQL("DELETE FROM track_points")
        assertFalse(progression.hasUnscannedPlaceHistory())
        val start = android.location.Location("gps").apply {
            latitude = 39.98; longitude = -74.88; accuracy = 4f; time = 1_000L
        }
        assertTrue(progression.recordBaselineCandidate(start))
        val baseline = progression.pendingPlaceCandidates(nowMillis = 2_000L).single()
        progression.resolveCandidate(baseline, listOf(
            PlaceDiscovery(PlaceKind.COUNTRY, "us", "United States",
                visitedAt = 1_000L, latitude = start.latitude, longitude = start.longitude),
            PlaceDiscovery(PlaceKind.STATE, "us|new jersey", "New Jersey",
                "United States", "United States", 1_000L, start.latitude, start.longitude),
            PlaceDiscovery(PlaceKind.TOWN, "us|new jersey|start", "Start",
                "New Jersey", "United States", 1_000L, start.latitude, start.longitude)
        ))
        fun fix(lon: Double, time: Long) {
            database.execSQL(
                """INSERT INTO track_points(latitude, longitude, accuracy_m, speed_mps,
                   bearing_deg, timestamp_ms) VALUES (39.96, ?, 5, 10, 0, ?)""",
                arrayOf(lon, time)
            )
        }
        try {
            fix(-75.1, 2_000L)
            fix(-75.2, 3_000L)
            fix(-75.200001, 4_000L)
            assertTrue(progression.hasUnscannedPlaceHistory())
            assertEquals(1, progression.backfillPlaceCandidates(1))
            assertEquals("Duplicate cells do not mean older history is finished",
                0, progression.backfillPlaceCandidates(1))
            assertTrue(progression.hasUnscannedPlaceHistory())
            assertEquals("Older unique municipality must still be scanned",
                1, progression.backfillPlaceCandidates(1))
            assertEquals(0, progression.backfillPlaceCandidates(1))
            assertFalse(progression.hasUnscannedPlaceHistory())
        } finally {
            database.execSQL("DELETE FROM track_points")
        }
    }

    @Test fun oldCompressedDrivingFixesAlsoBackfillWithoutInventingVisits() {
        val progression = ProgressionRepository(context)
        val db = AppDatabase.get(context).writableDatabase
        db.execSQL("DELETE FROM track_points")
        db.execSQL("DELETE FROM skipped_driving_fixes")
        val baseline = android.location.Location("gps").apply {
            latitude = 39.95; longitude = -75.0; accuracy = 5f; time = 1_000L
        }
        assertTrue(progression.recordBaselineCandidate(baseline))
        progression.resolveCandidate(progression.pendingPlaceCandidates(nowMillis = 2_000L).single(), listOf(
            PlaceDiscovery(PlaceKind.COUNTRY, "us", "United States",
                visitedAt = 1_000L, latitude = baseline.latitude, longitude = baseline.longitude),
            PlaceDiscovery(PlaceKind.STATE, "us|pennsylvania", "Pennsylvania",
                "United States", "United States", 1_000L, baseline.latitude, baseline.longitude),
            PlaceDiscovery(PlaceKind.TOWN, "us|pennsylvania|start", "Start",
                "Pennsylvania", "United States", 1_000L, baseline.latitude, baseline.longitude)
        ))
        fun skipped(longitude: Double, time: Long, accuracy: Int) {
            db.execSQL(
                """INSERT INTO skipped_driving_fixes(latitude, longitude, accuracy_m, speed_mps,
                    bearing_deg, timestamp_ms) VALUES (39.95, ?, ?, 10, 0, ?)""",
                arrayOf(longitude, accuracy, time)
            )
        }
        try {
            skipped(-75.01, 500L, 5) // From an erased prior progression baseline.
            skipped(-75.02, 2_000L, 5)
            skipped(-75.03, 3_000L, 75) // Poor accuracy must not reveal a town.
            skipped(-75.04, 4_000L, 5)
            assertTrue(progression.hasUnscannedPlaceHistory())
            assertEquals(2, progression.backfillPlaceCandidates(10))
            val candidates = progression.pendingPlaceCandidates(nowMillis = 5_000L)
            assertEquals(2, candidates.size)
            assertTrue(candidates.all { it.visitedAt >= 2_000L })
            assertEquals(0L, progression.snapshot().balance)
            assertEquals(0, progression.backfillPlaceCandidates(10))
            assertFalse(progression.hasUnscannedPlaceHistory())
        } finally {
            db.execSQL("DELETE FROM skipped_driving_fixes")
        }
    }

    @Test fun mixedHistoricSourcesKeepTheirIndependentCursorsAndSharedPageLimit() {
        val progression = ProgressionRepository(context)
        val db = AppDatabase.get(context).writableDatabase
        db.execSQL("DELETE FROM track_points")
        db.execSQL("DELETE FROM skipped_driving_fixes")
        val start = android.location.Location("gps").apply {
            latitude = 40.0; longitude = -75.0; accuracy = 5f; time = 1_000L
        }
        progression.recordBaselineCandidate(start)
        progression.resolveCandidate(progression.pendingPlaceCandidates(nowMillis = 2_000L).single(), listOf(
            PlaceDiscovery(PlaceKind.COUNTRY, "us", "United States",
                visitedAt = 1_000L, latitude = start.latitude, longitude = start.longitude)
        ))
        // A partially resolved baseline must not allow ordinary candidates yet.
        val baselineStillPending = progression.pendingPlaceCandidates(nowMillis = Long.MAX_VALUE)
        if (baselineStillPending.isNotEmpty()) {
            progression.resolveCandidate(baselineStillPending.single(), listOf(
                PlaceDiscovery(PlaceKind.STATE, "us|pennsylvania", "Pennsylvania",
                    "United States", "United States", 1_000L, start.latitude, start.longitude),
                PlaceDiscovery(PlaceKind.TOWN, "us|pennsylvania|home", "Home",
                    "Pennsylvania", "United States", 1_000L, start.latitude, start.longitude)
            ))
        }
        try {
            db.execSQL("""INSERT INTO track_points(latitude,longitude,accuracy_m,
                speed_mps,bearing_deg,timestamp_ms) VALUES (40.0,-75.1,5,10,0,2000)""")
            db.execSQL("""INSERT INTO skipped_driving_fixes(latitude,longitude,accuracy_m,
                speed_mps,bearing_deg,timestamp_ms) VALUES (40.0,-75.2,5,10,0,3000)""")
            assertEquals(1, progression.backfillPlaceCandidates(1))
            assertTrue(progression.hasUnscannedPlaceHistory())
            assertEquals(1, progression.backfillPlaceCandidates(1))
            assertEquals(2, progression.pendingPlaceCandidates(nowMillis = Long.MAX_VALUE).size)
            assertEquals(0, progression.backfillPlaceCandidates(1))
            assertFalse(progression.hasUnscannedPlaceHistory())
            assertEquals(0L, progression.snapshot().balance)
        } finally {
            db.execSQL("DELETE FROM track_points")
            db.execSQL("DELETE FROM skipped_driving_fixes")
        }
    }

    @Test fun separatedFixesInOneCellCanDiscoverTwoTownsWithoutUnboundedRequests() {
        val progression = ProgressionRepository(context)
        assertTrue(progression.recordPlace(PlaceDiscovery(
            PlaceKind.TOWN, "us|nj|initial", "Initial",
            "New Jersey", "United States", 1_000L, 39.95, -75.02
        )))
        fun fix(lat: Double, time: Long) = android.location.Location("gps").apply {
            latitude = lat
            longitude = 0.0001
            accuracy = 4f
            this.time = time
        }
        // Both fixes fall in the same 150 m Mercator cell; the old unique
        // (cell_x, cell_y) key silently discarded the second crossing.
        val first = fix(0.0001, 2_000L)
        val second = fix(0.0008, 3_000L)
        assertTrue(progression.recordPlaceCandidate(first))
        assertTrue(progression.recordPlaceCandidate(second))
        assertFalse(progression.recordPlaceCandidate(fix(0.00081, 4_000L)))
        assertFalse(progression.recordPlaceCandidate(fix(0.0011, 5_000L)))
        val pending = progression.pendingPlaceCandidates(limit = 10, nowMillis = 6_000L)
        assertEquals(2, pending.size)
        assertEquals(setOf(first.latitude, second.latitude), pending.map { it.latitude }.toSet())
        val originalPoints = progression.snapshot().balance
        pending.forEachIndexed { index, candidate ->
            assertEquals(1, progression.resolveCandidate(candidate, listOf(
                PlaceDiscovery(PlaceKind.TOWN, "us|nj|crossing-$index", "Crossing $index",
                    "New Jersey", "United States", candidate.visitedAt,
                    candidate.latitude, candidate.longitude)
            )))
        }
        assertEquals(originalPoints + 2L * PlaceKind.TOWN.points, progression.snapshot().balance)
    }

    @Test fun aFreshTownCandidateRetainsTheRealFixAtMunicipalBoundaries() {
        val progression = ProgressionRepository(context)
        assertTrue(progression.recordPlace(PlaceDiscovery(
            PlaceKind.TOWN, "us|nj|initial", "Initial",
            "New Jersey", "United States", 1_000L, 39.95, -75.02
        )))
        val first = android.location.Location("gps").apply {
            latitude = 39.967921; longitude = -75.240047; accuracy = 4f; time = 2_000L
        }
        val second = android.location.Location("gps").apply {
            latitude = 39.968435; longitude = -75.229471; accuracy = 4f; time = 3_000L
        }
        assertTrue(progression.recordPlaceCandidate(first))
        assertTrue(progression.recordPlaceCandidate(second))
        val candidates = progression.pendingPlaceCandidates(limit = 10, nowMillis = 4_000L)
        assertEquals(2, candidates.size)
        assertTrue(candidates.any { it.latitude == first.latitude && kotlin.math.abs(it.longitude - first.longitude) < 1e-8 })
        assertTrue(candidates.any { it.latitude == second.latitude && kotlin.math.abs(it.longitude - second.longitude) < 1e-8 })
    }
}
