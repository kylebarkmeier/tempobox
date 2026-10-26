package com.tempobox.ui.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tempobox.model.AudioFormat
import com.tempobox.model.NetworkDevice
import com.tempobox.model.Track
import com.tempobox.playback.PlayerConnection
import com.tempobox.remoteplay.DidlLite
import com.tempobox.remoteplay.RemotePlayController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Browsing DLNA media servers (a NAS, miniDLNA, Plex...) and playing their
 * audio on the local player. Network tracks are not library rows: they queue
 * by their HTTP resource URL with server metadata, get no rating/play-count/
 * scrobbling, and are skipped by queue persistence.
 */
@HiltViewModel
class NetworkBrowseViewModel @Inject constructor(
    private val remotePlay: RemotePlayController,
    private val player: PlayerConnection,
) : ViewModel() {

    /** One level of the folder drill-down (for the title and back behavior). */
    data class Crumb(val objectId: String, val title: String)

    data class BrowseState(
        /** null: the server list is showing. */
        val server: NetworkDevice? = null,
        val crumbs: List<Crumb> = emptyList(),
        val entries: List<DidlLite.Entry> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )

    val servers: StateFlow<List<NetworkDevice>> = remotePlay.servers

    /** True while the OS refuses discovery sends (Android 17 local network permission). */
    val discoveryBlocked: StateFlow<Boolean> = remotePlay.discoveryBlocked

    private val _state = MutableStateFlow(BrowseState())
    val state: StateFlow<BrowseState> = _state.asStateFlow()

    /** Discovery runs only while the screen shows (multicast lock). */
    fun startDiscovery() = remotePlay.startDiscovery()
    fun stopDiscovery() = remotePlay.stopDiscovery()

    fun openServer(server: NetworkDevice) {
        _state.value = BrowseState(server = server)
        load(server, objectId = ROOT_OBJECT)
    }

    fun openFolder(folder: DidlLite.Entry.Folder) {
        val server = _state.value.server ?: return
        _state.value = _state.value.copy(
            crumbs = _state.value.crumbs + Crumb(folder.id, folder.title),
        )
        load(server, folder.id)
    }

    /** One level up; false when already on the server list (leave the screen). */
    fun navigateUp(): Boolean {
        val current = _state.value
        val server = current.server ?: return false
        if (current.crumbs.isEmpty()) {
            _state.value = BrowseState()
            return true
        }
        val crumbs = current.crumbs.dropLast(1)
        _state.value = current.copy(crumbs = crumbs)
        load(server, crumbs.lastOrNull()?.objectId ?: ROOT_OBJECT)
        return true
    }

    /** Queue every track of the current folder, starting at [entry]. */
    fun playFrom(entry: DidlLite.Entry.Audio) {
        val tracks = audioEntries().map { it.toTrack() }
        val start = audioEntries().indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
        viewModelScope.launch { player.playTracks(tracks, start) }
    }

    fun playAll() {
        val tracks = audioEntries().map { it.toTrack() }
        viewModelScope.launch { player.playTracks(tracks) }
    }

    fun addToQueue(entry: DidlLite.Entry.Audio) {
        viewModelScope.launch { player.addToQueue(listOf(entry.toTrack())) }
    }

    private fun audioEntries(): List<DidlLite.Entry.Audio> =
        _state.value.entries.filterIsInstance<DidlLite.Entry.Audio>()

    private fun load(server: NetworkDevice, objectId: String) {
        _state.value = _state.value.copy(loading = true, error = null, entries = emptyList())
        viewModelScope.launch {
            val entries = remotePlay.browse(server.id, objectId)
            _state.value = _state.value.copy(
                loading = false,
                entries = entries.orEmpty(),
                error = if (entries == null) "Couldn't browse ${server.name}" else null,
            )
        }
    }

    private fun DidlLite.Entry.Audio.toTrack(): Track = Track(
        id = 0, // not a library row
        filePath = url,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        format = AudioFormat.fromExtension(
            url.substringBefore('?').substringAfterLast('.', ""),
        ),
    )

    companion object {
        /** ContentDirectory's well-known root container id. */
        private const val ROOT_OBJECT = "0"
    }
}
