package com.roadconquest.app.matching

import com.roadconquest.app.BuildConfig
import com.roadconquest.app.data.TrackPoint

interface RoadMatcher {
    fun match(points: List<TrackPoint>): MatchResult?
    fun rateLimitRemainingMs(): Long
    val maxPoints: Int get() = GeoapifyMatcher.MAX_MATCH_POINTS
    /** Actual provider capacity used for the most recent call, not current availability. */
    val lastUsedProviderPointLimit: Int get() = maxPoints
    /** A valid NoMatch/ambiguous trace is not an outage and must not shop for an easier result. */
    val unavailable: Boolean
}

class FallbackRoadMatcher(private val providers: List<RoadMatcher>) : RoadMatcher {
    override var unavailable = false
        private set
    @Volatile override var lastUsedProviderPointLimit = GeoapifyMatcher.MAX_MATCH_POINTS
        private set
    @Synchronized override fun match(points: List<TrackPoint>): MatchResult? {
        unavailable = false
        for (provider in providers) {
            if (provider.rateLimitRemainingMs() > 0L) continue
            // A fallback may have a smaller request limit. Submit one prefix;
            // unsubmitted fixes remain in the persistent queue, without fan-out.
            val providerLimit = provider.maxPoints
            lastUsedProviderPointLimit = providerLimit
            val result = provider.match(points.take(providerLimit))
            if (result != null || !provider.unavailable) return result
        }
        unavailable = true
        return null // The persistent GPS queue retries; never synthesize a route.
    }
    override fun rateLimitRemainingMs(): Long = providers.minOfOrNull { it.rateLimitRemainingMs() } ?: 300_000L
    override val maxPoints: Int get() = providers.firstOrNull { it.rateLimitRemainingMs() == 0L }?.maxPoints
        ?: GeoapifyMatcher.MAX_MATCH_POINTS

    companion object {
        fun configured(): FallbackRoadMatcher = FallbackRoadMatcher(buildList {
            if (BuildConfig.MAP_GATEWAY_URL.isNotBlank()) {
                add(GeoapifyMatcher(BuildConfig.MAP_GATEWAY_URL))
                add(LocationIqMatcher(BuildConfig.MAP_GATEWAY_URL))
            }
        })
    }
}
