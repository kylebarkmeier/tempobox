package com.tempobox.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tempobox.library.LibraryRepository
import com.tempobox.library.PlaylistRepository
import com.tempobox.model.Album
import com.tempobox.model.Playlist
import com.tempobox.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Artist detail: the artist's albums + an all-tracks tab (product spec). */
@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    libraryRepository: LibraryRepository,
) : ViewModel() {
    val name: String = savedState.get<String>("name").orEmpty()

    val albums: StateFlow<List<Album>> = libraryRepository.observeAlbums(albumArtist = name)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tracks: StateFlow<List<Track>> = libraryRepository.observeArtistTracks(name)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** Album detail: ordered track list. */
@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    libraryRepository: LibraryRepository,
) : ViewModel() {
    val albumArtist: String = savedState.get<String>("artist").orEmpty()
    val album: String = savedState.get<String>("album").orEmpty()

    val tracks: StateFlow<List<Track>> =
        libraryRepository.observeAlbumTracks(album, albumArtist)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** Genre detail: sub-browsable by artists, albums, recently added, tracks. */
@HiltViewModel
class GenreDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    libraryRepository: LibraryRepository,
    settingsRepository: com.tempobox.settings.SettingsRepository,
) : ViewModel() {
    val name: String = savedState.get<String>("name").orEmpty()

    val artists = libraryRepository.observeAlbumArtists(genre = name)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val albums = libraryRepository.observeAlbums(genre = name)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tracks = libraryRepository.observeGenreTracks(name)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Genre ∩ recently-added window. */
    val recentTracks: StateFlow<List<Track>> = settingsRepository.settings
        .flatMapLatest { settings ->
            val cutoff = System.currentTimeMillis() -
                settings.library.recentlyAddedDays * 24L * 60 * 60 * 1000
            libraryRepository.observeGenreTracks(name).let { base ->
                kotlinx.coroutines.flow.combine(base, flowOf(cutoff)) { list, c ->
                    list.filter { it.dateAddedMs >= c }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** Playlist detail: track list; static playlists support entry removal. */
@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {
    private val playlistId: Long = savedState.get<Long>("id") ?: 0L

    val playlist: StateFlow<Playlist?> = flow {
        emit(playlistRepository.getPlaylist(playlistId))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val tracks: StateFlow<List<Track>> = playlist
        .flatMapLatest { p -> p?.let { playlistRepository.observePlaylistTracks(it) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Removes one entry (position-based) from a static playlist. */
    fun removeEntry(index: Int) {
        val p = playlist.value ?: return
        if (p.isSmart) return
        viewModelScope.launch {
            val ids = tracks.value.map { it.id }.toMutableList()
            if (index in ids.indices) {
                ids.removeAt(index)
                playlistRepository.replacePlaylistTracks(p.id, ids)
            }
        }
    }
}
