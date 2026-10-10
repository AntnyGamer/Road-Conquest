package com.roadconquest.app.progression

import android.content.Context
import android.location.Geocoder
import com.roadconquest.app.data.PendingPlaceCandidate
import com.roadconquest.app.data.PlaceDiscovery
import com.roadconquest.app.data.PlaceKind
import com.roadconquest.app.map.PlaceOverlayCache
import com.roadconquest.app.map.PlaceOverlayClient
import com.roadconquest.app.map.PlaceGeometry
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

object PlaceResolver {
    @Suppress("DEPRECATION")
    fun resolve(context: Context, candidate: PendingPlaceCandidate): List<PlaceDiscovery>? {
        if (!Geocoder.isPresent()) return null
        val addresses = try {
            Geocoder(context.applicationContext, Locale.US)
                .getFromLocation(candidate.latitude, candidate.longitude, MAX_RESULTS)
                .orEmpty()
        } catch (_: IOException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
        if (addresses.isEmpty()) return null
        val parsed = resolveAddresses(candidate, addresses)
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
                        key = "$" + "{parentKey}|" + "$" + "{canonical(alternate)}",
                        displayName = alternate
                    )
                )
            }
        }.distinctBy { it.key }.take(4)
        val client by lazy { PlaceOverlayClient() }
        for (town in townCandidates) {
            val cached = PlaceOverlayCache.read(context, town).data
            val boundary = if (cached != null) cached else try {
                client.fetch(town)?.also { PlaceOverlayCache.write(context, town, it) }
            } catch (_: IOException) {
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
        val countryName = firstValue { it.countryName }
        val countryCode = firstValue { it.countryCode }
        val countryKey = canonical(countryCode.ifBlank { countryName })
        val stateName = firstValue { it.adminArea }
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
