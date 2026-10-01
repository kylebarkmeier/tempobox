package com.tempobox.scrobble

import com.tempobox.model.Track
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fans each playback event out to every scrobbling route — the external
 * scrobbler-app broadcast and the built-in Last.fm client. Each route no-ops
 * when its setting is off, so both can coexist.
 */
@Singleton
class CompositeScrobbler @Inject constructor(
    private val broadcast: BroadcastScrobbler,
    private val lastFm: LastFmScrobbler,
) : Scrobbler {

    override suspend fun updateNowPlaying(track: Track) {
        broadcast.updateNowPlaying(track)
        lastFm.updateNowPlaying(track)
    }

    override suspend fun scrobble(track: Track, startedAtEpochSec: Long) {
        broadcast.scrobble(track, startedAtEpochSec)
        lastFm.scrobble(track, startedAtEpochSec)
    }

    override suspend fun flushPending() {
        lastFm.flushPending()
    }
}
