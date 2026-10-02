package com.tempobox.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tempobox.library.LibraryRepository
import com.tempobox.library.PlaylistRepository
import com.tempobox.model.Album
import com.tempobox.model.AlbumArtist
import com.tempobox.model.LibrarySubview
import com.tempobox.model.Playlist
import com.tempobox.model.SortKey
import com.tempobox.model.SortSpec
import com.tempobox.model.Track
import com.tempobox.model.sortTracks
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private fun <T> Flow<List<T>>.stateInList(scope: CoroutineScope): StateFlow<List<T>> =
    stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

private fun Flow<SortSpec>.stateInSort(scope: CoroutineScope, initial: SortSpec): StateFlow<SortSpec> =
    stateIn(scope, SharingStarted.Eagerly, initial)

/**
 * Artist detail: the artist's albums + an all-tracks tab (product spec).
 * Serves both paradigms: by album artist (default) or by track artist
 * (route arg `by=track`). Each tab has its own sort, shared across all artist
 * pages for the session via [SubviewSortState], like the main tabs' sorts.
 */
@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    libraryRepository: LibraryRepository,
    private val sortState: SubviewSortState,
) : ViewModel() {
    val name: String = savedState.get<String>("name").orEmpty()
    val byAlbumArtist: Boolean = savedState.get<String>("by") != "track"

    val albumsSort: StateFlow<SortSpec> = sortState.sortFlow(LibrarySubview.ARTIST_ALBUMS)
        .stateInSort(viewModelScope, sortState.current(LibrarySubview.ARTIST_ALBUMS))

    val tracksSort: StateFlow<SortSpec> = sortState.sortFlow(LibrarySubview.ARTIST_TRACKS)
        .stateInSort(viewModelScope, sortState.current(LibrarySubview.ARTIST_TRACKS))

    fun setAlbumsSort(spec: SortSpec) = sortState.set(LibrarySubview.ARTIST_ALBUMS, spec)
    fun setTracksSort(spec: SortSpec) = sortState.set(LibrarySubview.ARTIST_TRACKS, spec)

    val albums: StateFlow<List<Album>> = sortState.sortFlow(LibrarySubview.ARTIST_ALBUMS)
        .flatMapLatest { sort ->
            if (byAlbumArtist) {
                libraryRepository.observeAlbums(albumArtist = name, sort = sort)
            } else {
                libraryRepository.observeAlbums(artist = name, sort = sort)
            }
        }
        .stateInList(viewModelScope)

    val tracks: StateFlow<List<Track>> = sortState.sortFlow(LibrarySubview.ARTIST_TRACKS)
        .flatMapLatest { sort ->
            if (byAlbumArtist) {
                libraryRepository.observeArtistTracks(name, sort)
            } else {
                libraryRepository.observeTrackArtistTracks(name, sort)
            }
        }
        .stateInList(viewModelScope)
}

/** Album detail: track list, disc/track order by default, sortable. */
@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    libraryRepository: LibraryRepository,
    private val sortState: SubviewSortState,
) : ViewModel() {
    val albumArtist: String = savedState.get<String>("artist").orEmpty()
    val album: String = savedState.get<String>("album").orEmpty()

    val tracksSort: StateFlow<SortSpec> = sortState.sortFlow(LibrarySubview.ALBUM_TRACKS)
        .stateInSort(viewModelScope, sortState.current(LibrarySubview.ALBUM_TRACKS))

    fun setTracksSort(spec: SortSpec) = sortState.set(LibrarySubview.ALBUM_TRACKS, spec)

    val tracks: StateFlow<List<Track>> = sortState.sortFlow(LibrarySubview.ALBUM_TRACKS)
        .flatMapLatest { sort -> libraryRepository.observeAlbumTracks(album, albumArtist, sort) }
        .stateInList(viewModelScope)
}

/** Genre detail: sub-browsable by artists, albums, tracks; each tab sortable. */
@HiltViewModel
class GenreDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    libraryRepository: LibraryRepository,
    private val sortState: SubviewSortState,
) : ViewModel() {
    val name: String = savedState.get<String>("name").orEmpty()

    val artistsSort: StateFlow<SortSpec> = sortState.sortFlow(LibrarySubview.GENRE_ARTISTS)
        .stateInSort(viewModelScope, sortState.current(LibrarySubview.GENRE_ARTISTS))

    val albumsSort: StateFlow<SortSpec> = sortState.sortFlow(LibrarySubview.GENRE_ALBUMS)
        .stateInSort(viewModelScope, sortState.current(LibrarySubview.GENRE_ALBUMS))

    val tracksSort: StateFlow<SortSpec> = sortState.sortFlow(LibrarySubview.GENRE_TRACKS)
        .stateInSort(viewModelScope, sortState.current(LibrarySubview.GENRE_TRACKS))

    fun setArtistsSort(spec: SortSpec) = sortState.set(LibrarySubview.GENRE_ARTISTS, spec)
    fun setAlbumsSort(spec: SortSpec) = sortState.set(LibrarySubview.GENRE_ALBUMS, spec)
    fun setTracksSort(spec: SortSpec) = sortState.set(LibrarySubview.GENRE_TRACKS, spec)

    val artists: StateFlow<List<AlbumArtist>> = sortState.sortFlow(LibrarySubview.GENRE_ARTISTS)
        .flatMapLatest { sort -> libraryRepository.observeAlbumArtists(genre = name, sort = sort) }
        .stateInList(viewModelScope)

    val albums: StateFlow<List<Album>> = sortState.sortFlow(LibrarySubview.GENRE_ALBUMS)
        .flatMapLatest { sort -> libraryRepository.observeAlbums(genre = name, sort = sort) }
        .stateInList(viewModelScope)

    val tracks: StateFlow<List<Track>> = sortState.sortFlow(LibrarySubview.GENRE_TRACKS)
        .flatMapLatest { sort -> libraryRepository.observeGenreTracks(name, sort) }
        .stateInList(viewModelScope)
}

/**
 * Playlist detail: track list; static playlists support entry removal.
 *
 * Sorting here is strictly view-side: [tracks] applies the chosen sort to the
 * stored order in memory, and nothing ever writes a sorted list back through
 * [PlaylistRepository], so the playlist's entry table and its `.m3u8` file
 * keep the user's manual order. The default sort is that stored order.
 */
@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val playlistRepository: PlaylistRepository,
    private val sortState: SubviewSortState,
) : ViewModel() {
    private val playlistId: Long = savedState.get<Long>("id") ?: 0L

    val playlist: StateFlow<Playlist?> = flow {
        emit(playlistRepository.getPlaylist(playlistId))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val tracksSort: StateFlow<SortSpec> = sortState.sortFlow(LibrarySubview.PLAYLIST_TRACKS)
        .stateInSort(viewModelScope, sortState.current(LibrarySubview.PLAYLIST_TRACKS))

    fun setTracksSort(spec: SortSpec) = sortState.set(LibrarySubview.PLAYLIST_TRACKS, spec)

    /** Tracks in stored (manual or smart-rule) order; the source of truth for edits. */
    private val storedTracks: StateFlow<List<Track>> = playlist
        .flatMapLatest { p -> p?.let { playlistRepository.observePlaylistTracks(it) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Tracks as displayed: the stored order with the view sort applied. */
    val tracks: StateFlow<List<Track>> =
        combine(storedTracks, sortState.sortFlow(LibrarySubview.PLAYLIST_TRACKS)) { stored, sort ->
            sort.sortTracks(stored)
        }.stateInList(viewModelScope)

    /** Removes one displayed entry from a static playlist. */
    fun removeEntry(index: Int) {
        val p = playlist.value ?: return
        if (p.isSmart) return
        viewModelScope.launch {
            val stored = storedTracks.value.map { it.id }.toMutableList()
            val sort = tracksSort.value
            // With a view sort active the display index no longer matches the
            // stored position, so fall back to removing that track's first
            // stored occurrence. In stored order the index maps directly,
            // which keeps duplicate entries individually removable.
            val storedIndex = if (sort.key == SortKey.PLAYLIST_ORDER && sort.ascending) {
                index
            } else {
                tracks.value.getOrNull(index)?.let { stored.indexOf(it.id) } ?: -1
            }
            if (storedIndex in stored.indices) {
                stored.removeAt(storedIndex)
                playlistRepository.replacePlaylistTracks(p.id, stored)
            }
        }
    }
}
