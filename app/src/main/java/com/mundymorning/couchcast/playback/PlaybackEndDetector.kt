package com.mundymorning.couchcast.playback

/**
 * Decides when Roku playback through the relay is over, from the Roku's own media-player reports.
 * Nothing is "over" before playback has been seen, except after a start grace period.
 */
class PlaybackEndDetector(
    private val maxMisses: Int = 3,
    private val startGraceMs: Long = 60_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val startedAt = now()
    private var seenActive = false
    private var misses = 0

    /** [state] is the Roku's player state ("play", "buffer", "pause", "close", "none"...) or null if it couldn't be read. */
    fun onReport(state: String?): Boolean {
        if (state == "play" || state == "buffer" || state == "pause") {
            seenActive = true; misses = 0
            return false
        }
        if (!seenActive) return now() - startedAt > startGraceMs
        misses++
        return misses >= maxMisses
    }
}
