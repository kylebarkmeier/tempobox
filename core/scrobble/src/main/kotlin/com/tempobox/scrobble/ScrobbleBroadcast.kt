package com.tempobox.scrobble

import com.tempobox.model.Track

/**
 * The Simple Last.fm Scrobbler (SLS) broadcast API — the de-facto standard
 * scrobbler apps listen for (Pano Scrobbler, Simple Scrobbler, …). TempoBox
 * emits these so an installed scrobbler app can handle Last.fm without any
 * credentials living in TempoBox.
 *
 * Pure extras builder so the payload is unit-testable on the JVM;
 * [BroadcastScrobbler] wraps it in an Intent.
 */
object ScrobbleBroadcast {
    const val ACTION = "com.adam.aslfms.notify.playstatechanged"

    /** SLS state codes. */
    enum class State(val code: Int) { START(0), RESUME(1), PAUSE(2), COMPLETE(3) }

    /** Extras for one playstate broadcast, keyed per the SLS API. */
    fun extras(track: Track, state: State, appName: String, appPackage: String): Map<String, Any> =
        mapOf(
            "state" to state.code,
            "app-name" to appName,
            "app-package" to appPackage,
            "artist" to track.artist.ifBlank { track.effectiveAlbumArtist },
            "album" to track.album,
            "track" to track.title,
            "duration" to (track.durationMs / 1000).toInt(),
        )
}
