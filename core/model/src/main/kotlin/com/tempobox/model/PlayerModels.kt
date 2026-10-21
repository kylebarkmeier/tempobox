package com.tempobox.model

import kotlinx.serialization.Serializable

/** Repeat behavior of the play queue. */
enum class RepeatMode { OFF, ALL, ONE }

/**
 * Shuffle behavior. The Now Playing shuffle button cycles OFF → the user's
 * default mode (see Settings ▸ Shuffle); long-press opens a picker with all modes.
 *
 * - [ALL]         plain uniform random order.
 * - [ANTI_REPEAT] spreads out repeats of the same track/album/artist as far as
 *                 possible (default per product spec).
 * - [RATING_BIASED] anti-repeat ordering additionally weighted toward
 *                 higher-rated tracks (5★ most likely).
 */
enum class ShuffleMode { OFF, ALL, ANTI_REPEAT, RATING_BIASED }

/**
 * One item in the play queue. [uid] is unique per queue insertion so the same
 * track can appear multiple times and still be individually removable.
 */
data class QueueItem(
    val uid: Long,
    val track: Track,
)

/** Snapshot of the playback state exposed to UI and the widget. */
data class NowPlayingState(
    val track: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffleMode: ShuffleMode = ShuffleMode.OFF,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val queueIndex: Int = -1,
    val queueSize: Int = 0,
)

/**
 * One selectable audio output (speaker, wired, Bluetooth A2DP, USB…).
 * [id] is the platform AudioDeviceInfo id, valid only while the device is
 * connected; `null` selection means "automatic" (the system routes).
 */
data class AudioOutputDevice(
    val id: Int,
    val name: String,
)

/**
 * One playback target or media source discovered on the local network (a DLNA/
 * UPnP MediaRenderer or MediaServer). [id] is the device's UDN, stable across
 * rediscoveries; the entry is valid only while the device answers SSDP.
 */
data class NetworkDevice(
    val id: String,
    val name: String,
)

/** Fields that can be shown under the track title in Now Playing (configurable). */
@Serializable
enum class TrackInfoField {
    ARTIST,
    ALBUM,
    YEAR,
    GENRE,
    FORMAT,      // e.g. "FLAC 44.1 kHz"
    BITRATE,
    RATING,
    PLAY_COUNT,
}
