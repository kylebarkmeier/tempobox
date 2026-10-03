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
import com.tempobox.model.filterAlbums
import com.tempobox.model.filterArtists
import com.tempobox.model.filterGenres
import com.tempobox.model.filterPlaylists
import com.tempobox.model.filterTracks
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
 * toggles, and the genre sub-filters for the artist tabs — all feeding
 * reactive repository queries.
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

    /**
     * Free-form search over the visible tab. Transient view state like the
     * sorts: never persisted, cleared by the UI when search is dismissed or
     * the tab changes. One query is enough because only one tab shows at a
     * time, and it filters after the repository sort so sort order is kept.
     */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** Genre sub-filter for the Album Artists tab (null = all genres). */
    val artistGenreFilter = MutableStateFlow<String?>(null)

    /** Genre sub-filter for the (track) Artists tab (null = all genres). */
    val trackArtistGenreFilter = MutableStateFlow<String?>(null)

    val uiSettings: StateFlow<UiSettings> = settingsRepository.settings
        .map { it.ui }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiSettings())

    val preferArtistImages: StateFlow<Boolean> = settingsRepository.settings
        .map { it.artwork.preferArtistImages && it.artwork.discogsToken.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleArtistLayout() = toggleLayout { it.copy(artistLayout = it.artistLayout.flip()) }
    fun toggleAlbumLayout() = toggleLayout { it.copy(albumLayout = it.albumLayout.flip()) }
    fun toggleGenreLayout() = toggleLayout { it.copy(genreLayout = it.genreLayout.flip()) }

    private fun ViewLayout.flip() = if (this == ViewLayout.CARD) ViewLayout.LIST else ViewLayout.CARD

    private fun toggleLayout(transform: (UiSettings) -> UiSettings) {
        viewModelScope.launch { settingsRepository.updateUi(transform) }
    }

    // ------------------------------------------------------------------ data flows

    val tracks: StateFlow<List<Track>> = sortFlow(LibraryTab.TRACKS)
        .flatMapLatest { libraryRepository.observeTracks(it) }
        .combine(_searchQuery) { list, query -> list.filterTracks(query) }
        .stateInList()

    val albums: StateFlow<List<Album>> = sortFlow(LibraryTab.ALBUMS)
        .flatMapLatest { libraryRepository.observeAlbums(sort = it) }
        .combine(_searchQuery) { list, query -> list.filterAlbums(query) }
        .stateInList()

    val artists: StateFlow<List<AlbumArtist>> =
        combine(sortFlow(LibraryTab.ALBUM_ARTISTS), artistGenreFilter) { sort, genre -> sort to genre }
            .flatMapLatest { (sort, genre) ->
                libraryRepository.observeAlbumArtists(genre = genre, sort = sort)
            }
            .combine(_searchQuery) { list, query -> list.filterArtists(query) }
            .stateInList()

    /** Track-artist aggregation for the Artists tab. */
    val trackArtists: StateFlow<List<AlbumArtist>> =
        combine(sortFlow(LibraryTab.ARTISTS), trackArtistGenreFilter) { sort, genre -> sort to genre }
            .flatMapLatest { (sort, genre) ->
                libraryRepository.observeTrackArtists(genre = genre, sort = sort)
            }
            .combine(_searchQuery) { list, query -> list.filterArtists(query) }
            .stateInList()

    val genres: StateFlow<List<Genre>> = sortFlow(LibraryTab.GENRES)
        .flatMapLatest { libraryRepository.observeGenres(sort = it) }
        .combine(_searchQuery) { list, query -> list.filterGenres(query) }
        .stateInList()

    val playlists: StateFlow<List<Playlist>> = playlistRepository.observePlaylists()
        .combine(_searchQuery) { list, query -> list.filterPlaylists(query) }
        .stateInList()

    /** All known genre names (for the artist tab's filter chips). */
    val genreNames: StateFlow<List<String>> = libraryRepository.observeGenres()
        .map { list -> list.map { it.name } }
        .stateInList()

    // ------------------------------------------------------------------ helpers

    private fun sortFlow(tab: LibraryTab) = _sorts.map { it[tab] ?: SortSpec() }

    private fun <T> kotlinx.coroutines.flow.Flow<List<T>>.stateInList(): StateFlow<List<T>> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
