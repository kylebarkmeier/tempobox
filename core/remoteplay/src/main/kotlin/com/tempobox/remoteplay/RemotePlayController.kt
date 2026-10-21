package com.tempobox.remoteplay

import com.tempobox.model.NetworkDevice
import com.tempobox.model.Track
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Live state of the renderer session, overlaid on Now Playing while active. */
data class RemotePlaybackState(
    val active: Boolean = false,
    val rendererId: String? = null,
    val rendererName: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Renderer volume 0..100; null when the device exposes none. */
    val volumePercent: Int? = null,
)

sealed interface RemotePlayEvent {
    /** The renderer finished the loaded track; the queue should advance. */
    data object TrackEnded : RemotePlayEvent

    /** The renderer died or vanished; playback should fall back to local. */
    data class SessionLost(val rendererName: String) : RemotePlayEvent
}

/**
 * The playback-facing API of network play. `PlayerConnection` drives it:
 * discovery around the output picker, one renderer session at a time, and
 * ContentDirectory browsing for the network browse screen.
 *
 * An interface so `core:playback` tests can substitute a mock; the real
 * implementation is [DefaultRemotePlayController].
 */
interface RemotePlayController {

    /** Devices that can play our audio (DLNA renderers), while discovering. */
    val renderers: StateFlow<List<NetworkDevice>>

    /** Devices we can browse and play from (DLNA servers), while discovering. */
    val servers: StateFlow<List<NetworkDevice>>

    val state: StateFlow<RemotePlaybackState>

    val events: SharedFlow<RemotePlayEvent>

    /** Ref-counted discovery; call around picker/browse screen visibility. */
    fun startDiscovery()
    fun stopDiscovery()

    /**
     * Opens a session on renderer [rendererId] (also starts the stream
     * server). False when the device is gone or the phone has no LAN address.
     */
    suspend fun connect(rendererId: String): Boolean

    /** Ends the session: stops the renderer, the server, and the polling. */
    suspend fun disconnect()

    /**
     * Makes [track] the renderer's current URI. Local files are served from
     * the stream server by token; network streams (DLNA server tracks) are
     * handed to the renderer by their own URL. False on failure.
     */
    suspend fun load(track: Track, positionMs: Long, play: Boolean): Boolean

    suspend fun play()
    suspend fun pause()
    suspend fun seekTo(positionMs: Long)
    suspend fun setVolume(percent: Int)

    /** Browse a media server folder; null when the request failed. */
    suspend fun browse(serverId: String, objectId: String): List<DidlLite.Entry>?

    /** Service shutdown: disconnect, stop discovery, release everything. */
    fun shutdown()
}
