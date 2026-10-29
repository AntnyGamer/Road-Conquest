package com.roadconquest.app.map

/** Marker presentation only. It never changes tracking, road credit or saved cosmetics. */
class MotionModeTracker {
    var isWalking = false
        private set
    private var lastSample: Long? = null
    private var walkingSince: Long? = null
    private var fastSince: Long? = null
    private var lastDriving: Long? = null

    fun observe(time: Long, speed: Float, accuracy: Float): Boolean {
        val previous = lastSample
        if (previous != null && time <= previous) return isWalking
        if (previous != null && time - previous > 30_000L) reset()
        lastSample = time
        if (!speed.isFinite() || speed < 0f || accuracy !in 0.01f..25f) {
            walkingSince = null
            fastSince = null
            return isWalking
        }
        when {
            // Match the recording cutoff; faster walking/jogging is treated as driving.
            speed >= 2.2f -> {
                walkingSince = null
                val start = fastSince ?: time.also { fastSince = it }
                if (time - start >= 2_000L) {
                    isWalking = false
                    lastDriving = time
                }
            }
            speed >= 0.5f -> {
                fastSince = null
                val start = walkingSince ?: time.also { walkingSince = it }
                if (time - start >= 12_000L && lastDriving?.let { time - it < 45_000L } != true) {
                    isWalking = true
                }
            }
            else -> {
                // Standing still keeps the last mode. Slowing at a light is not walking.
                walkingSince = null
                fastSince = null
            }
        }
        return isWalking
    }

    fun reset() {
        isWalking = false
        lastSample = null
        walkingSince = null
        fastSince = null
        lastDriving = null
    }
}
