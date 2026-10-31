package com.roadconquest.app.data

import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import java.util.Locale
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class ProgressionResourceTest {
    private val context: android.content.Context get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: ProgressionRepository
    private lateinit var db: SQLiteDatabase

    @Before fun reset() {
        AppDatabase::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
        repository = ProgressionRepository(context)
        repository.clearProgression()
        db = AppDatabase.get(context).writableDatabase
    }

    private fun candidate(): PendingPlaceCandidate {
        assertTrue(repository.recordBaselineCandidate(Location("gps").apply {
            latitude = 43.65; longitude = -79.38; accuracy = 3f; time = 1_000L
        }))
        return repository.pendingPlaceCandidates(nowMillis = Long.MAX_VALUE).single()
    }

    private fun discoveries() = listOf(
        PlaceDiscovery(PlaceKind.COUNTRY, "ca", "Canada", visitedAt = 1_000L, latitude = 43.65, longitude = -79.38),
        PlaceDiscovery(PlaceKind.STATE, "ca|ontario", "Ontario", "Canada", "Canada", 1_000L, 43.65, -79.38),
        PlaceDiscovery(PlaceKind.TOWN, "ca|ontario|toronto", "Toronto", "Ontario", "Canada", 1_000L, 43.65, -79.38)
    )

    private fun completenessQueries(block: () -> Unit): Int {
        val factoryField = SQLiteDatabase::class.java.getDeclaredField("mCursorFactory").apply { isAccessible = true }
        val previous = factoryField.get(db)
        var count = 0
        factoryField.set(db, SQLiteDatabase.CursorFactory { _, driver, editTable, query ->
            if (query.toString().contains("counter_key LIKE ? OR counter_key = ?")) count++
            SQLiteCursor(driver, editTable, query)
        })
        try { block() } finally { factoryField.set(db, previous) }
        return count
    }

    @Test fun completeBaselineUsesOneCompletenessCursorAndKeepsZeroPointStart() {
        val candidate = candidate()
        assertEquals(1, completenessQueries { assertEquals(0, repository.resolveCandidate(candidate, discoveries())) })
        assertTrue(repository.pendingPlaceCandidates(nowMillis = Long.MAX_VALUE).isEmpty())
        assertEquals(0L, repository.snapshot().balance)
        assertEquals(StartingLocation(43.65, -79.38, 1_000L), repository.startingLocation())
        for (kind in PlaceKind.entries) assertEquals(1, repository.visitedPlaces(kind).size)
        assertFalse(repository.hasMissingStartingTown())
    }

    @Test fun incompleteRetryAndPartialReleaseRecheckAfterTheirWrites() {
        val candidate = candidate()
        assertEquals(2, completenessQueries { repository.resolveCandidate(candidate, discoveries().take(1)) })
        assertEquals(1, repository.pendingPlaceCandidates(nowMillis = Long.MAX_VALUE).single().attempts)
        assertEquals(2, completenessQueries { repository.resolveCandidate(candidate.copy(attempts = 2), emptyList()) })
        assertTrue(repository.pendingPlaceCandidates(nowMillis = Long.MAX_VALUE).isEmpty())
        assertTrue(repository.hasMissingStartingTown())
        assertEquals(0L, repository.snapshot().balance)
    }

    @Test fun completenessMatchesOriginalForEveryResolvedAndMissingKindCombination() {
        val method = ProgressionRepository::class.java.getDeclaredMethod("baselineComplete", SQLiteDatabase::class.java)
            .apply { isAccessible = true }
        // Each kind is absent, resolved, marked missing, or both (4^3 states).
        // Keep 64 independent fixture states but avoid 64 separate disk commits.
        db.beginTransaction()
        try {
            for (state in 0 until 64) {
            db.delete("progression_counters", null, null)
            for ((index, kind) in PlaceKind.entries.withIndex()) {
                val flags = (state shr (index * 2)) and 3
                val name = kind.name.lowercase()
                if (flags and 1 != 0) db.execSQL("INSERT INTO progression_counters VALUES (?, 1)", arrayOf("baseline:$name:fixture"))
                if (flags and 2 != 0) db.execSQL("INSERT INTO progression_counters VALUES (?, 1)", arrayOf("baseline_missing:$name"))
            }
            val original = PlaceKind.entries.all { kind ->
                db.rawQuery(
                    "SELECT 1 FROM progression_counters WHERE counter_key LIKE ? OR counter_key = ? LIMIT 1",
                    arrayOf("baseline:${kind.name.lowercase()}:%", "baseline_missing:${kind.name.lowercase()}")
                ).use { it.moveToFirst() }
            }
            assertEquals("state=$state", original, method.invoke(repository, db))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Test fun ordinaryDiscoveryNeverChecksBaselineCompleteness() {
        val candidate = candidate()
        repository.resolveCandidate(candidate, discoveries())
        assertEquals(0, completenessQueries {
            assertEquals(1, repository.resolveCandidate(candidate.copy(cellX = 123, cellY = 456),
                listOf(discoveries().last().copy(key = "ca|ontario|other", displayName = "Other", latitude = 44.0))))
        })
        assertEquals(35L, repository.snapshot().balance)
    }

    @Test fun municipalNamesMatchOriginalIncludingOverlappingAndTruncatedKinds() {
        val method = ProgressionRepository::class.java.getDeclaredMethod("municipalityIdentity", String::class.java)
            .apply { isAccessible = true }
        val kinds = listOf("township", "borough", "municipality", "village", "town", "city")
        val whitespace = Regex("\\s+")
        fun original(value: String): Pair<String, String> {
            val text = value.trim().lowercase(Locale.ROOT).replace(whitespace, " ").removePrefix("the ")
            for (kind in kinds) {
                val prefix = "$kind of "
                if (text.startsWith(prefix)) return text.removePrefix(prefix) to kind
                val suffix = " $kind"
                if (text.endsWith(suffix)) return text.removeSuffix(suffix) to kind
            }
            return text to ""
        }
        val cores = listOf("", " ", "Pine Hill", "İstanbul", "Québec", "city of", "township", "the city", "a\u00a0b", "a\tb\nc")
        val starts = listOf("", "the ", " THE\t") + kinds.map { "$it of " } + kinds.map { "the $it of " }
        val ends = listOf("", " ", "\n") + kinds.map { " $it" } + kinds.map { " $it of" }
        for (start in starts) for (core in cores) for (end in ends) {
            val value = start + core + end
            assertEquals(value, original(value), method.invoke(repository, value))
        }
    }
}
