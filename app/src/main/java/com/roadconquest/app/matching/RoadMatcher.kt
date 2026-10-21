package com.roadconquest.app.matching

import com.roadconquest.app.BuildConfig
import com.roadconquest.app.data.TrackPoint

interface RoadMatcher {
    fun match(points: List<TrackPoint>): MatchResult?
    /** Context can include deferred unmatched fixes; only these IDs are eligible work. */
    fun match(points: List<TrackPoint>, pendingIds: Set<Long>): MatchResult? = match(points)
    /** Local-only validated response; callers must require complete eligible coverage to bypass gates. */
    fun cachedMatch(points: List<TrackPoint>): MatchResult? = null
    fun cachedMatch(points: List<TrackPoint>, pendingIds: Set<Long>): MatchResult? = cachedMatch(points)
    val lastCallUsedNetwork: Boolean get() = true
    val lastNetworkProviderPointLimit: Int get() = maxPoints
    fun rateLimitRemainingMs(): Long
    val maxPoints: Int get() = GeoapifyMatcher.MAX_MATCH_POINTS
    /** Actual provider capacity used for the most recent call, not current availability. */
    val lastUsedProviderPointLimit: Int get() = maxPoints
    /** Start of the actual contiguous provider window within the caller's input. */
    val lastUsedProviderPointOffset: Int get() = 0
    /** A valid NoMatch/ambiguous trace is not an outage and must not shop for an easier result. */
    val unavailable: Boolean
}

class FallbackRoadMatcher(private val providers: List<RoadMatcher>) : RoadMatcher {
    override var unavailable = false
        private set
    @Volatile override var lastUsedProviderPointLimit = GeoapifyMatcher.MAX_MATCH_POINTS
        private set
    @Volatile override var lastUsedProviderPointOffset = 0
        private set
    override var lastCallUsedNetwork = false
        private set
    override var lastNetworkProviderPointLimit = GeoapifyMatcher.MAX_MATCH_POINTS
        private set
    @Synchronized override fun cachedMatch(points: List<TrackPoint>): MatchResult? =
        cachedMatch(points, points.filterNot { it.matched }.mapTo(HashSet()) { it.id })

    @Synchronized override fun cachedMatch(points: List<TrackPoint>, pendingIds: Set<Long>): MatchResult? {
        lastCallUsedNetwork = false
        for (provider in providers) {
            // Keep provider precedence even for a cached valid NoMatch/partial.
            // Never shop the backup cache for an easier answer to that trace.
            val offset = providerOffset(points, pendingIds, provider.maxPoints)
            val result = provider.cachedMatch(points.drop(offset).take(provider.maxPoints)) ?: continue
            unavailable = false
            lastUsedProviderPointLimit = provider.maxPoints
            lastUsedProviderPointOffset = offset
            return result
        }
        return null
    }
    @Synchronized override fun match(points: List<TrackPoint>): MatchResult? =
        match(points, points.filterNot { it.matched }.mapTo(HashSet()) { it.id })

    @Synchronized override fun match(points: List<TrackPoint>, pendingIds: Set<Long>): MatchResult? {
        if (providers.any { it.rateLimitRemainingMs() > 0L }) {
            // Incomplete cached evidence must not suppress a ready backup provider.
            cachedMatch(points, pendingIds)?.takeIf {
                it.completelyCoversPending(lastSubmittedPoints(points), pendingIds)
            }?.let { return it }
        }
        unavailable = false
        lastCallUsedNetwork = false
        for (provider in providers) {
            if (provider.rateLimitRemainingMs() > 0L) continue
            // Keep the original prefix unless it contains NO eligible work.
            // Then use one contiguous window with real approach context around
            // the first pending fix. Never fan out or re-request matched context alone.
            val providerLimit = provider.maxPoints
            lastUsedProviderPointLimit = providerLimit
            lastUsedProviderPointOffset = providerOffset(points, pendingIds, providerLimit)
            val result = provider.match(lastSubmittedPoints(points))
            if (provider.lastCallUsedNetwork) {
                lastCallUsedNetwork = true
                lastNetworkProviderPointLimit = provider.lastNetworkProviderPointLimit
            }
            if (result != null || !provider.unavailable) return result
        }
        unavailable = true
        return null // The persistent GPS queue retries; never synthesize a route.
    }
    override fun rateLimitRemainingMs(): Long = providers.minOfOrNull { it.rateLimitRemainingMs() } ?: 300_000L
    override val maxPoints: Int get() = providers.firstOrNull { it.rateLimitRemainingMs() == 0L }?.maxPoints
        ?: GeoapifyMatcher.MAX_MATCH_POINTS

    private fun providerOffset(points: List<TrackPoint>, pendingIds: Set<Long>, limit: Int): Int {
        if (points.take(limit).any { it.id in pendingIds }) return 0
        val firstPending = points.indexOfFirst { it.id in pendingIds }
        if (firstPending < 0) return 0
        val approach = minOf(3, (limit - 1).coerceAtLeast(0))
        return (firstPending - approach).coerceIn(0, (points.size - limit).coerceAtLeast(0))
    }

    companion object {
        fun configured(): FallbackRoadMatcher = FallbackRoadMatcher(buildList {
            if (BuildConfig.MAP_GATEWAY_URL.isNotBlank()) {
                add(GeoapifyMatcher(BuildConfig.MAP_GATEWAY_URL))
                add(LocationIqMatcher(BuildConfig.MAP_GATEWAY_URL))
            }
        })
    }
}

internal fun RoadMatcher.lastSubmittedPoints(points: List<TrackPoint>): List<TrackPoint> =
    points.drop(lastUsedProviderPointOffset).take(lastUsedProviderPointLimit)

internal fun MatchResult.completelyCoversPending(points: List<TrackPoint>, pendingIds: Set<Long>? = null): Boolean {
    // Deferred unmatched fixes can be read-only context. Require the same
    // accepted confidence for every eligible fix in the actual provider window;
    // unresolved context neither blocks that proof nor becomes completed work.
    val pending = points.filter { !it.matched && (pendingIds == null || it.id in pendingIds) }
    return pending.isNotEmpty() && roads.any { it.confidence >= RoadGeometryValidator.MIN_ACCEPTABLE_CONFIDENCE } &&
        pending.all { (matchedPointConfidences[it.id] ?: 0.0) >= RoadGeometryValidator.MIN_ACCEPTABLE_CONFIDENCE }
}
