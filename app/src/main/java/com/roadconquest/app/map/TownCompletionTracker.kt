package com.roadconquest.app.map

import android.content.Context
import com.roadconquest.app.data.AppDatabase
import com.roadconquest.app.data.PlaceDiscovery
import com.roadconquest.app.data.PlaceKind
import com.roadconquest.app.data.ProgressionRepository
import com.roadconquest.app.data.ProgressionSnapshot
import com.roadconquest.app.data.TrackingRepository

/** Bounded, offline backfill also earns medals when the town layer is not open. */
internal object TownCompletionTracker {
    private data class Revision(val database: AppDatabase, val history: Long, val tiles: Long,
        val boundaries: Long, val towns: Long)
    private var revision: Revision? = null
    private var places: List<PlaceDiscovery> = emptyList()
    private var cursor = 0
    private var remaining = 0
    private var engine = TownCompletion()

    @Synchronized fun reset() {
        revision = null
        places = emptyList()
        cursor = 0
        remaining = 0
        engine = TownCompletion()
    }

    @Synchronized fun sync(context: Context, snapshot: ProgressionSnapshot, expectedHistory: Long?): Boolean {
        if (snapshot.tilesUnlocked == 0L) return false
        val database = AppDatabase.get(context)
        val history = database.historyGeneration
        if (expectedHistory != null && expectedHistory != history) return false
        // snapshot() already counted persisted tiles under historyLock. New tiles
        // can only append within this generation; the next snapshot catches them.
        // Reuse that count instead of issuing another COUNT on every UI refresh.
        val state = history to snapshot.tilesUnlocked
        val tracking = TrackingRepository(context)
        val next = Revision(database, state.first, state.second,
            PlaceOverlayCache.contentRevision(), snapshot.towns)
        val progression = ProgressionRepository(context)
        if (next != revision) {
            if (revision?.database !== next.database) engine = TownCompletion()
            places = progression.visitedPlaces(PlaceKind.TOWN)
            // Keep rotating even during a long drive: distant towns cannot starve.
            cursor = if (places.isEmpty()) 0 else cursor % places.size
            remaining = places.size
            revision = next
        }
        var changed = false
        repeat(minOf(8, remaining)) {
            if (Thread.currentThread().isInterrupted || !progression.isHistoryGenerationCurrent(state.first)) return changed
            val place = places[cursor]
            cursor = (cursor + 1) % places.size
            remaining--
            val cached = if (UnitedStatesMunicipalBoundaries.applies(place)) {
                PlaceOverlayCache.readOfficialUs(context, place).data
            } else PlaceOverlayCache.read(context, place).data
            if (cached != null && cached.containsRecordedVisit(place)) {
                engine.calculate(cached.geometryJson, state, tracking::getExploredGridInBounds)?.let {
                    val update = progression.recordTownCompletion(place.key, it.medal.ordinal, state.first)
                    changed = update.changed || changed
                }
            }
        }
        return changed
    }
}
