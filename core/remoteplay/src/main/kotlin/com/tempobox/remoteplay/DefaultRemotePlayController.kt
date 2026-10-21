package com.tempobox.remoteplay

import android.util.Log
import com.tempobox.model.NetworkDevice
import com.tempobox.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The renderer-session engine behind [RemotePlayController].
 *
 * One session at a time: a [RendererClient] for control, the [StreamServer]
 * for the bytes, a 1s polling loop for position and end-of-track, and a
 * [RendererSessionMachine] deciding when accumulated failures mean the
 * renderer is gone (emit [RemotePlayEvent.SessionLost] once, tear down, and
 * let PlayerConnection fall back to local output).
 */
@Singleton
class DefaultRemotePlayController @Inject constructor(
    private val discovery: SsdpDiscovery,
    private val upnp: UpnpControl,
    private val contentDirectory: ContentDirectoryClient,
) : RemotePlayController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Serializes connect/disconnect/load against each other. */
    private val sessionMutex = Mutex()

    private val tokens = StreamTokens()
    private val server = StreamServer(tokens)

    private var client: RendererClient? = null
    private val machine = RendererSessionMachine()
    private var pollJob: Job? = null

    /** Last position seen while playing; feeds the end-of-track heuristic. */
    private var lastPlayingPositionMs = 0L

    override val renderers: StateFlow<List<NetworkDevice>> = discovery.devices
        .map { devices -> devices.filter { it.isRenderer }.map { NetworkDevice(it.udn, it.friendlyName) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val servers: StateFlow<List<NetworkDevice>> = discovery.devices
        .map { devices -> devices.filter { it.isServer }.map { NetworkDevice(it.udn, it.friendlyName) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val discoveryBlocked: StateFlow<Boolean> = discovery.blocked

    private val _state = MutableStateFlow(RemotePlaybackState())
    override val state: StateFlow<RemotePlaybackState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RemotePlayEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<RemotePlayEvent> = _events.asSharedFlow()

    override fun startDiscovery() = discovery.start()

    override fun stopDiscovery() = discovery.stop()

    override suspend fun connect(rendererId: String): Boolean = sessionMutex.withLock {
        teardownLocked(stopRenderer = true)
        val device = discovery.device(rendererId)
        if (device == null || !device.isRenderer) {
            Log.w(TAG, "Renderer $rendererId is no longer discoverable")
            return false
        }
        machine.on(RendererSessionMachine.Event.ConnectRequested)
        val address = WlanAddress.current()
        if (address == null) {
            Log.w(TAG, "No LAN address; cannot stream to ${device.friendlyName}")
            machine.on(RendererSessionMachine.Event.Disconnected)
            return false
        }
        if (server.start(address) == null) {
            machine.on(RendererSessionMachine.Event.Disconnected)
            return false
        }
        client = RendererClient(upnp, device)
        machine.on(RendererSessionMachine.Event.Connected)
        lastPlayingPositionMs = 0
        _state.value = RemotePlaybackState(
            active = true,
            rendererId = device.udn,
            rendererName = device.friendlyName,
            volumePercent = client?.volume(),
        )
        pollJob = scope.launch { pollLoop() }
        true
    }

    override suspend fun disconnect() = sessionMutex.withLock {
        teardownLocked(stopRenderer = true)
    }

    override suspend fun load(track: Track, positionMs: Long, play: Boolean): Boolean =
        sessionMutex.withLock {
            val client = client ?: return false
            val url: String
            val mime: String
            if (track.isNetworkStream) {
                // A DLNA-server track: the renderer can fetch it straight from
                // the server, no local relay needed.
                url = track.filePath
                mime = AudioMime.forPath(track.filePath)
            } else {
                refreshServerAddressLocked() ?: return fail("No LAN address to serve from")
                val token = tokens.register(File(track.filePath))
                    ?: return fail("Track is not readable: ${track.title}")
                mime = AudioMime.forPath(track.filePath)
                url = "http://${server.boundAddress?.hostAddress}:${server.port}/$token"
            }
            val metadata = DidlLite.trackMetadata(
                title = track.title,
                artist = track.artist.ifBlank { track.effectiveAlbumArtist },
                album = track.effectiveAlbum,
                durationMs = track.durationMs,
                url = url,
                mimeType = mime,
            )
            var ok = client.setUri(url, metadata)
            if (ok && play) ok = client.play()
            if (ok && positionMs > 0) {
                // Some renderers only accept Seek once playback started; a
                // failed initial seek is cosmetic, not a session failure.
                if (!client.seek(positionMs)) {
                    Log.w(TAG, "Initial seek to $positionMs ms rejected by ${client.device.friendlyName}")
                }
            }
            record(ok)
            if (ok) {
                lastPlayingPositionMs = positionMs
                _state.value = _state.value.copy(
                    isPlaying = play,
                    positionMs = positionMs,
                    durationMs = track.durationMs,
                )
            }
            ok
        }

    override suspend fun play() {
        val ok = client?.play() ?: return
        record(ok)
        if (ok) _state.value = _state.value.copy(isPlaying = true)
    }

    override suspend fun pause() {
        val ok = client?.pause() ?: return
        record(ok)
        if (ok) _state.value = _state.value.copy(isPlaying = false)
    }

    override suspend fun seekTo(positionMs: Long) {
        val ok = client?.seek(positionMs) ?: return
        record(ok)
        if (ok) {
            lastPlayingPositionMs = positionMs
            _state.value = _state.value.copy(positionMs = positionMs)
        }
    }

    override suspend fun setVolume(percent: Int) {
        val ok = client?.setVolume(percent) ?: return
        // Volume is RenderingControl, optional on some devices: failures are
        // logged by UpnpControl but never count toward session death.
        if (ok) _state.value = _state.value.copy(volumePercent = percent.coerceIn(0, 100))
    }

    override suspend fun browse(serverId: String, objectId: String): List<DidlLite.Entry>? {
        val device = discovery.device(serverId)
        if (device == null || !device.isServer) {
            Log.w(TAG, "Media server $serverId is no longer discoverable")
            return null
        }
        return contentDirectory.browse(device, objectId)
    }

    override fun shutdown() {
        // Called from the service's onDestroy: release local resources
        // synchronously but skip the renderer Stop call (a blocking network
        // round-trip on the main thread is an ANR; an orphaned renderer stops
        // on its own when the stream socket dies).
        pollJob?.cancel()
        pollJob = null
        client = null
        server.stop()
        tokens.clear()
        machine.on(RendererSessionMachine.Event.Disconnected)
        _state.value = RemotePlaybackState()
        discovery.shutdown()
        scope.cancel()
    }

    // ------------------------------------------------------------------ internals

    /**
     * The phone's address can change mid-session (Wi-Fi roam, DHCP renew).
     * Restart the server on the current address when it moved; already-loaded
     * tracks on the renderer then fail their next fetch, which surfaces
     * through the poll loop as a session failure and a clean local fallback.
     */
    private fun refreshServerAddressLocked(): java.net.InetAddress? {
        val current = WlanAddress.current() ?: return null
        if (server.isRunning && server.boundAddress == current) return current
        Log.w(TAG, "LAN address changed or server down; rebinding stream server to $current")
        return if (server.start(current) != null) current else null
    }

    private suspend fun pollLoop() {
        while (scope.isActive && pollJob?.isActive == true) {
            delay(POLL_INTERVAL_MS)
            val client = client ?: return
            val transport = client.transportState()
            if (transport == null) {
                record(false)
                continue
            }
            val wasPlaying = _state.value.isPlaying
            when (RendererPolling.outcome(wasPlaying, transport, lastPlayingPositionMs, _state.value.durationMs)) {
                RendererPolling.Outcome.PLAYING -> {
                    val position = client.position()
                    record(position != null)
                    if (position != null) {
                        position.positionMs?.let { lastPlayingPositionMs = it }
                        _state.value = _state.value.copy(
                            isPlaying = true,
                            positionMs = position.positionMs ?: _state.value.positionMs,
                            durationMs = position.durationMs?.takeIf { it > 0 } ?: _state.value.durationMs,
                        )
                    }
                }
                RendererPolling.Outcome.PAUSED -> {
                    record(true)
                    _state.value = _state.value.copy(isPlaying = false)
                }
                RendererPolling.Outcome.STOPPED -> {
                    record(true)
                    _state.value = _state.value.copy(isPlaying = false)
                }
                RendererPolling.Outcome.TRACK_ENDED -> {
                    record(true)
                    _state.value = _state.value.copy(isPlaying = false)
                    Log.i(TAG, "Renderer reports track ended")
                    _events.tryEmit(RemotePlayEvent.TrackEnded)
                }
            }
        }
    }

    /** Routes a command result into the session machine; fails over once dead. */
    private fun record(success: Boolean) {
        val before = machine.state
        val after = machine.on(
            if (success) {
                RendererSessionMachine.Event.CommandSucceeded
            } else {
                RendererSessionMachine.Event.CommandFailed
            },
        )
        if (after == RendererSessionMachine.State.FAILED && before != RendererSessionMachine.State.FAILED) {
            val name = _state.value.rendererName
            Log.w(TAG, "Renderer '$name' unreachable; giving up the session")
            scope.launch {
                sessionMutex.withLock { teardownLocked(stopRenderer = false) }
                _events.tryEmit(RemotePlayEvent.SessionLost(name))
            }
        }
    }

    private fun fail(message: String): Boolean {
        Log.w(TAG, message)
        record(false)
        return false
    }

    /** Must hold [sessionMutex]. */
    private suspend fun teardownLocked(stopRenderer: Boolean) {
        pollJob?.cancel()
        pollJob = null
        if (stopRenderer) {
            client?.let { c ->
                if (!c.stop()) Log.w(TAG, "Could not stop ${c.device.friendlyName} while disconnecting")
            }
        }
        client = null
        server.stop()
        tokens.clear()
        machine.on(RendererSessionMachine.Event.Disconnected)
        lastPlayingPositionMs = 0
        _state.value = RemotePlaybackState()
    }

    companion object {
        private const val TAG = "RemotePlay"
        private const val POLL_INTERVAL_MS = 1_000L
    }
}
