package com.tempobox.playback

/**
 * When does a listen count as a "play"? Shared by the play-count column and
 * Last.fm scrobbling, both of which follow the scrobble standard:
 *
 *  - the track is longer than 30 seconds, AND
 *  - at least half of it — or 4 minutes, whichever is less — was played.
 */
object PlayedThreshold {
    private const val MIN_TRACK_LENGTH_MS = 30_000L
    private const val CAP_MS = 4 * 60_000L

    fun shouldCount(durationMs: Long, playedMs: Long): Boolean {
        if (durationMs < MIN_TRACK_LENGTH_MS) return false
        val threshold = minOf(durationMs / 2, CAP_MS)
        return playedMs >= threshold
    }
}
