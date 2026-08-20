package com.tempobox.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tempobox.library.LibraryRepository
import com.tempobox.library.PlaylistRepository
import com.tempobox.model.Album
import com.tempobox.model.AlbumArtist
import com.tempobox.model.Genre
import com.tempobox.model.LibraryTab
import com.tempobox.model.Playlist
import com.tempobox.model.SortSpec
import com.tempobox.model.Track
import com.tempobox.model.ViewLayout
import com.tempobox.settings.SettingsRepository
import com.tempobox.settings.UiSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the tabbed Library screen: per-tab sort specs, card/list layout
 * toggles, the genre sub-filter for the artist tab, and the recently-added
 * window — all feeding reactive repository queries.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    playlistRepository: PlaylistRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    // ------------------------------------------------------------------ ui state

    /** Sort spec per tab (spec: every view sortable 5 ways, both directions). */
    private val _sorts = MutableStateFlow<Map<LibraryTab, SortSpec>>(emptyMap())
    val sorts: StateFlow<Map<LibraryTab, SortSpec>> = _sorts.asStateFlow()

    fun sortFor(tab: LibraryTab): SortSpec = _sorts.value[tab] ?: SortSpec()

    fun setSort(tab: LibraryTab, spec: SortSpec) {
        _sorts.value = _sorts.value + (tab to spec)
    }

    /** Genre sub-filter for the Album Artists tab (null = all genres). */
    val artistGenreFilter = MutableStateFlow<String?>(null)

    val uiSettings: StateFlow<UiSettings> = settingsRepository.settings
        .map { it.ui }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiSettings())

    val preferArtistImages: StateFlow<Boolean> = settingsRepository.settings
        .map { it.artwork.preferArtistImages && it.artwork.discogsToken.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleArtistLayout() = toggleLayout { it.copy(artistLayout = it.artistLayout.flip()) }
    fun toggleAlbumLayout() = toggleLayout { it.copy(albumLayout = it.albumLayout.flip()) }

    private fun ViewLayout.flip() = if (this == ViewLayout.CARD) ViewLayout.LIST else ViewLayout.CARD

    private fun toggleLayout(transform: (UiSettings) -> UiSettings) {
        viewModelScope.launch { settingsRepository.updateUi(transform) }
    }

    /** Epoch-ms cutoff for "Recently Added" (Settings ▸ Library ▸ window). */
    private val recentCutoffMs: kotlinx.coroutines.flow.Flow<Long> = settingsRepository.settings
        .map { System.currentTimeMillis() - it.library.recentlyAddedDays * DAY_MS }

    // ------------------------------------------------------------------ data flows

    val tracks: StateFlow<List<Track>> = sortFlow(LibraryTab.TRACKS)
        .flatMapLatest { libraryRepository.observeTracks(it) }
        .stateInList()

    val albums: StateFlow<List<Album>> = sortFlow(LibraryTab.ALBUMS)
        .flatMapLatest { libraryRepository.observeAlbums(sort = it) }
        .stateInList()

    val artists: StateFlow<List<AlbumArtist>> =
        combine(sortFlow(LibraryTab.ALBUM_ARTISTS), artistGenreFilter) { sort, genre -> sort to genre }
            .flatMapLatest { (sort, genre) ->
                libraryRepository.observeAlbumArtists(genre = genre, sort = sort)
            }
            .stateInList()

    val genres: StateFlow<List<Genre>> = sortFlow(LibraryTab.GENRES)
        .flatMapLatest { libraryRepository.observeGenres(sort = it) }
        .stateInList()

    val playlists: StateFlow<List<Playlist>> = playlistRepository.observePlaylists()
        .stateInList()

    /** All known genre names (for the artist tab's filter chips). */
    val genreNames: StateFlow<List<String>> = libraryRepository.observeGenres()
        .map { list -> list.map { it.name } }
        .stateInList()

    // --- Recently added (own sub-tabs, spec) ---

    val recentTracks: StateFlow<List<Track>> = recentCutoffMs
        .flatMapLatest { libraryRepository.observeRecentlyAddedTracks(it) }
        .stateInList()

    val recentAlbums: StateFlow<List<Album>> = recentCutoffMs
        .flatMapLatest { libraryRepository.observeAlbums(sinceMs = it) }
        .stateInList()

    val recentArtists: StateFlow<List<AlbumArtist>> = recentCutoffMs
        .flatMapLatest { libraryRepository.observeAlbumArtists(sinceMs = it) }
        .stateInList()

    val recentGenres: StateFlow<List<Genre>> = recentCutoffMs
        .flatMapLatest { libraryRepository.observeGenres(sinceMs = it) }
        .stateInList()

    val recentPlaylists: StateFlow<List<Playlist>> =
        combine(playlists, recentCutoffMs) { lists, cutoff ->
            lists.filter { it.dateAddedMs >= cutoff }
        }.stateInList()

    // ------------------------------------------------------------------ helpers

    private fun sortFlow(tab: LibraryTab) = _sorts.map { it[tab] ?: SortSpec() }

    private fun <T> kotlinx.coroutines.flow.Flow<List<T>>.stateInList(): StateFlow<List<T>> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
