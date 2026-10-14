package com.roadconquest.app.progression

import android.content.Context
import android.location.Geocoder
import com.roadconquest.app.data.PendingPlaceCandidate
import com.roadconquest.app.data.PlaceDiscovery
import com.roadconquest.app.data.PlaceKind
import com.roadconquest.app.data.ProgressionRepository
import com.roadconquest.app.map.PlaceOverlayCache
import com.roadconquest.app.map.PlaceOverlayClient
import com.roadconquest.app.map.UnitedStatesMunicipalBoundaries
import com.roadconquest.app.map.UnitedStatesMunicipalityAtPoint
import com.roadconquest.app.map.UnitedStatesStateAtPoint
import com.roadconquest.app.map.PlaceGeometry
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

object PlaceResolver {
    /**
     * The foreground worker processes up to six GPS candidates per batch.
     * Parse official cached town geometry once per batch, not once per fix.
     * A new verified town is immediately available to later fixes in the batch.
     * No location, town matching, or reward criteria are changed.
     */
    internal class VerifiedMunicipalCache(
        private val load: () -> MutableList<Pair<PlaceDiscovery, JSONObject>>
    ) {
        constructor(context: Context) : this({
            val appContext = context.applicationContext
            ProgressionRepository(appContext).visitedPlaces(PlaceKind.TOWN)
                .asSequence()
                .filter(UnitedStatesMunicipalBoundaries::applies)
                // Only incorporated (layer 4) keys are safe shortcut candidates.
                // Avoid loading and parsing polygons we must validate online anyway.
                .filter { it.key.substringAfterLast('|').startsWith("census-4-") }
                .mapNotNull { place ->
                    PlaceOverlayCache.readOfficialUs(appContext, place).data?.let { overlay ->
                        try {
                            place to JSONObject(overlay.geometryJson)
                        } catch (_: org.json.JSONException) {
                            null
                        }
                    }
                }
                .toMutableList()
        })

        private val polygons by lazy(load)

        fun find(seed: PlaceDiscovery): PlaceDiscovery? {
            var match: PlaceDiscovery? = null
            val candidateState = UnitedStatesMunicipalBoundaries.stateCode(
                seed.countryName, seed.parentName
            )
            for ((place, polygon) in polygons) {
                // Incorporated places have priority over county subdivisions in
                // the authoritative point lookup. A cached subdivision may extend
                // over an as-yet-unvisited city: never let it bypass that lookup.
                // Legacy name-only keys have no recorded source layer either.
                if (!place.key.substringAfterLast('|').startsWith("census-4-")) continue
                val sameState = place.parentName == seed.parentName ||
                    (candidateState != null && candidateState ==
                        UnitedStatesMunicipalBoundaries.stateCode(
                            place.countryName, place.parentName
                        ))
                if (!sameState ||
                    !PlaceGeometry.contains(polygon, seed.latitude, seed.longitude)
                ) continue
                // County subdivisions can overlap incorporated places. The cache
                // is ordered by visit time, not by Census layer precedence, so
                // different matching towns require a fresh authoritative lookup.
                if (match != null && match.key != place.key) return null
                match = place
            }
            return match?.copy(visitedAt = seed.visitedAt)
        }

        fun remember(result: UnitedStatesMunicipalityAtPoint.Result) {
            polygons += result.place to JSONObject(result.overlay.geometryJson)
        }
    }

    @Suppress("DEPRECATION")
    internal fun resolve(
        context: Context, candidate: PendingPlaceCandidate,
        municipalCache: VerifiedMunicipalCache? = null
    ): List<PlaceDiscovery>? {
        // Capture BEFORE geocoding and network I/O. Clearing local data changes
        // this generation; older in-flight lookups must never recreate erased
        // precise-location boundary caches when their responses finally arrive.
        val cacheGeneration = PlaceOverlayCache.generation()
        val addresses = if (Geocoder.isPresent()) try {
            Geocoder(context.applicationContext, Locale.US)
                .getFromLocation(candidate.latitude, candidate.longitude, MAX_RESULTS)
                .orEmpty()
        } catch (_: IOException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        } else emptyList()
        // Android's Geocoder is optional and can fail even when a device has
        // internet access. Use a verified U.S. Census point query as a fallback,
        // rather than leaving all zero-point and town rewards unresolved.
        if (addresses.isEmpty()) return resolveWithoutGeocoder(context, candidate, cacheGeneration, municipalCache)
        val parsed = resolveAddresses(candidate, addresses)
        // U.S. postal localities can differ from actual incorporated municipalities,
        // particularly on short crossings. Resolve by GPS coordinate using the SAME
        // official Census dataset that draws the green polygon, in every U.S. state.
        val state = parsed.firstOrNull { it.kind == PlaceKind.STATE }
        val country = parsed.firstOrNull { it.kind == PlaceKind.COUNTRY }
        if (state != null && country != null &&
            UnitedStatesMunicipalBoundaries.stateCode(country.displayName, state.displayName) != null
        ) {
            val seed = parsed.firstOrNull { it.kind == PlaceKind.TOWN } ?:
                PlaceDiscovery(
                    PlaceKind.TOWN, state.key + "|gps", "", state.displayName,
                    country.displayName, candidate.visitedAt,
                    candidate.latitude, candidate.longitude
                )
            val known = municipalCache ?: VerifiedMunicipalCache(context)
            known.find(seed)?.let { town ->
                return parsed.filterNot { it.kind == PlaceKind.TOWN } + town
            }
            val result = try {
                UnitedStatesMunicipalityAtPoint.fetch(seed)
            } catch (_: IOException) {
                // Retry an unreachable provider rather than permanently discard a town.
                return null
            }
            if (result != null) {
                if (!PlaceOverlayCache.writeOfficialUs(
                        context, result.place, result.overlay, cacheGeneration
                    )
                ) return null
                known.remember(result)
            }
            return parsed.filterNot { it.kind == PlaceKind.TOWN } +
                listOfNotNull(result?.place)
        }
        val originalTown = parsed.firstOrNull { it.kind == PlaceKind.TOWN }
            ?: return parsed
        // The locality field is frequently a postal city across the town line.
        // Confirm each possible municipality against an actual boundary, never
        // award a town solely because the geocoder returned its name.
        val parentKey = originalTown.key.substringBeforeLast('|', "")
        val townCandidates = buildList {
            add(originalTown)
            for (address in addresses) {
                val alternate = address.locality?.trim().orEmpty()
                if (alternate.isNotBlank()) add(
                    originalTown.copy(
                        key = "${parentKey}|${canonical(alternate)}",
                        displayName = alternate
                    )
                )
            }
        }.distinctBy { it.key }.take(4)
        val client by lazy { PlaceOverlayClient() }
        for (town in townCandidates) {
            // Cache the authenticated municipal polygon by its town identity,
            // even when the current GPS fix falls OUTSIDE it. A nearby postal
            // town must not cause the same polygon to be fetched on every drive.
            val cached = PlaceOverlayCache.readVerifiedForDiscovery(context, town)
            val boundary = if (cached != null) cached else try {
                client.fetch(town, requireContainment = false)?.also {
                    if (!PlaceOverlayCache.write(
                            context, town, it, cacheGeneration, verifiedForDiscovery = true
                        )
                    ) return null
                }
            } catch (_: Exception) {
                // Preserve the candidate for retry when the boundary provider is offline.
                return null
            }
            if (boundary != null &&
                PlaceGeometry.contains(JSONObject(boundary.geometryJson), candidate.latitude, candidate.longitude)
            ) return parsed.filterNot { it.kind == PlaceKind.TOWN } + town
        }
        // No verified town here. State/country remain eligible and a later driven
        // position can confirm the correct municipality when GPS is further inside it.
        return parsed.filterNot { it.kind == PlaceKind.TOWN }
    }

    private fun resolveWithoutGeocoder(
        context: Context,
        candidate: PendingPlaceCandidate,
        cacheGeneration: Long,
        municipalCache: VerifiedMunicipalCache?
    ): List<PlaceDiscovery>? {
        val state = try {
            UnitedStatesStateAtPoint.fetch(candidate.latitude, candidate.longitude)
        } catch (_: IOException) {
            return null
        } ?: return null // Not a verified U.S. state: do not guess non-U.S. country/region.
        val country = "United States"
        val countryPlace = discovery(candidate, PlaceKind.COUNTRY, "us", country, "", country)
        val statePlace = discovery(
            candidate, PlaceKind.STATE, "us|${canonical(state)}", state, country, country
        )
        val seed = discovery(
            candidate, PlaceKind.TOWN, "us|${canonical(state)}|gps", "", state, country
        )
        val known = municipalCache ?: VerifiedMunicipalCache(context)
        val town = known.find(seed) ?: try {
            UnitedStatesMunicipalityAtPoint.fetch(seed)?.let { verified ->
                if (!PlaceOverlayCache.writeOfficialUs(
                        context, verified.place, verified.overlay, cacheGeneration
                    )) return null
                known.remember(verified)
                verified.place
            }
        } catch (_: IOException) {
            return null
        }
        return listOfNotNull(countryPlace, statePlace, town)
    }

    /** Prefer an explicitly named municipality over a nearby postal locality. */
    internal fun resolveAddresses(
        candidate: PendingPlaceCandidate,
        addresses: List<android.location.Address>
    ): List<PlaceDiscovery> {
        fun firstValue(selector: (android.location.Address) -> String?): String =
            addresses.asSequence().mapNotNull(selector).map(String::trim).firstOrNull(String::isNotBlank).orEmpty()

        // Reverse geocoders sometimes return a road/address result first with incomplete
        // administrative fields. Combine only results for this same coordinate rather than
        // letting one sparse result permanently omit the zero-point state/town.
        val reportedCountryName = firstValue { it.countryName }
        val reportedCountryCode = firstValue { it.countryCode }
        val isUnitedStates = reportedCountryCode.equals("US", ignoreCase = true) ||
            canonical(reportedCountryName) in setOf(
                "us", "usa", "united states", "united states of america"
            )
        // Reverse geocoders can omit either country field for the very same GPS
        // position. Use one stable US key and display name regardless of which
        // field was present, including during the zero-point baseline.
        val countryName = if (isUnitedStates) "United States" else reportedCountryName
        val countryKey = if (isUnitedStates) "us" else
            canonical(reportedCountryCode.ifBlank { countryName })
        val stateName = UnitedStatesMunicipalBoundaries.normalizedStateName(
            countryName, firstValue { it.adminArea }
        )
        val namedMunicipality = firstValue { address ->
            address.subAdminArea?.trim()?.takeIf { name ->
                val lower = name.lowercase(Locale.ROOT)
                lower.startsWith("township of ") ||
                    MUNICIPALITY_SUFFIXES.any { lower.endsWith(it) }
            }
        }
        // Android often supplies the county in subAdminArea. Never award a county
        // as a town; only override locality when the administrative value clearly
        // identifies a municipality (such as a named NJ township).
        // Postal locality may name a nearby town, not the municipality at this GPS fix.
        val townName = namedMunicipality.ifBlank { firstValue { it.locality } }
        val result = ArrayList<PlaceDiscovery>(3)

        if (countryKey.isNotBlank() && countryName.isNotBlank()) {
            result += discovery(candidate, PlaceKind.COUNTRY, countryKey, countryName, "", countryName)
        }
        if (stateName.isNotBlank()) {
            val stateKey = listOf(countryKey, canonical(stateName)).filter { it.isNotBlank() }.joinToString("|")
            result += discovery(candidate, PlaceKind.STATE, stateKey, stateName, countryName, countryName)
        }
        if (townName.isNotBlank()) {
            val townKey = listOf(countryKey, canonical(stateName), canonical(townName))
                .filter { it.isNotBlank() }.joinToString("|")
            result += discovery(candidate, PlaceKind.TOWN, townKey, townName, stateName, countryName)
        }
        return result
    }

    private fun discovery(
        candidate: PendingPlaceCandidate,
        kind: PlaceKind,
        key: String,
        name: String,
        parent: String,
        country: String
    ) = PlaceDiscovery(
        kind = kind,
        key = key,
        displayName = name,
        parentName = parent,
        countryName = country,
        visitedAt = candidate.visitedAt,
        latitude = candidate.latitude,
        longitude = candidate.longitude
    )

    private fun canonical(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(WHITESPACE_RE, " ")

    private val MUNICIPALITY_SUFFIXES = listOf(
        " township", " borough", " municipality", " village", " town", " city"
    )
    private val WHITESPACE_RE = Regex("\\s+")
    private const val MAX_RESULTS = 5
}
