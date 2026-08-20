package com.tempobox.scrobble

import com.tempobox.model.Track

/**
 * What playback needs from a scrobbling backend. `core:playback` calls these;
 * [LastFmScrobbler] implements them (no-ops when scrobbling is disabled).
 */
interface Scrobbler {
    /** Track started playing — send a Now Playing update (fire and forget). */
    suspend fun updateNowPlaying(track: Track)

    /**
     * Track passed the scrobble threshold (50% or 4 minutes, per Last.fm
     * rules). [startedAtEpochSec] is when playback of the track began.
     * Implementations must queue on network failure and retry later.
     */
    suspend fun scrobble(track: Track, startedAtEpochSec: Long)

    /** Flush any queued offline scrobbles (call on app start / connectivity). */
    suspend fun flushPending()
}
