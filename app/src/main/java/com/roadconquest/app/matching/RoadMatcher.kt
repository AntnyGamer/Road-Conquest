package com.roadconquest.app.matching

import com.roadconquest.app.BuildConfig
import com.roadconquest.app.data.TrackPoint

interface RoadMatcher {
    fun match(points: List<TrackPoint>): MatchResult?
    fun rateLimitRemainingMs(): Long
    /** A valid NoMatch/ambiguous trace is not an outage and must not shop for an easier result. */
    val unavailable: Boolean
}

class FallbackRoadMatcher(private val providers: List<RoadMatcher>) : RoadMatcher {
    override var unavailable = false
        private set
    @Synchronized override fun match(points: List<TrackPoint>): MatchResult? {
        unavailable = false
        for (provider in providers) {
            if (provider.rateLimitRemainingMs() > 0L) continue
            val result = provider.match(points)
            if (result != null || !provider.unavailable) return result
        }
        unavailable = true
        return null // The persistent GPS queue retries; never synthesize a route.
    }
    override fun rateLimitRemainingMs(): Long = providers.minOfOrNull { it.rateLimitRemainingMs() } ?: 300_000L

    companion object {
        fun configured(): FallbackRoadMatcher = FallbackRoadMatcher(buildList {
            if (BuildConfig.MAP_GATEWAY_URL.isNotBlank()) add(GeoapifyMatcher(BuildConfig.MAP_GATEWAY_URL))
        })
    }
}
