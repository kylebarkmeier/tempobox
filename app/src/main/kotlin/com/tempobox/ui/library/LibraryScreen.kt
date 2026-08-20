package com.tempobox.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tempobox.common.TimeFormat
import com.tempobox.model.LibraryTab
import com.tempobox.model.SwipeAction
import com.tempobox.model.ViewLayout
import com.tempobox.ui.components.ActionDialogHost
import com.tempobox.ui.components.ArtistImage
import com.tempobox.ui.components.CollageArt
import com.tempobox.ui.components.CollectionRow
import com.tempobox.ui.components.LibraryItemMenu
import com.tempobox.ui.components.SortMenuButton
import com.tempobox.ui.components.SwipeableLibraryItem
import com.tempobox.ui.components.TextInputDialog
import com.tempobox.ui.components.TrackArt
import com.tempobox.ui.components.TrackRow

/**
 * The tabbed Library: Album Artists / Albums / Genres / Tracks / Playlists /
 * Recently Added. Every list reuses the shared action layer, so play buttons,
 * 3-dot menus, swipe gestures and sorting behave identically everywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    initialTab: LibraryTab?,
    openDrawer: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (albumArtist: String, album: String) -> Unit,
    onOpenGenre: (String) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
) {
    val viewModel: LibraryViewModel = hiltViewModel()
    val actions: LibraryActionsViewModel = hiltViewModel()

    var selectedTab by rememberSaveable(initialTab) {
        mutableStateOf(initialTab ?: LibraryTab.ALBUM_ARTISTS)
    }
    val sorts by viewModel.sorts.collectAsState()
    val ui by viewModel.uiSettings.collectAsState()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Library") },
            navigationIcon = {
                IconButton(onClick = openDrawer) {
                    Icon(Icons.Filled.Menu, contentDescription = "Open navigation")
                }
            },
            actions = {
                // Card/list switch for the artist & album tabs (product spec).
                when (selectedTab) {
                    LibraryTab.ALBUM_ARTISTS -> LayoutToggle(ui.artistLayout) {
                        viewModel.toggleArtistLayout()
                    }
                    LibraryTab.ALBUMS -> LayoutToggle(ui.albumLayout) {
                        viewModel.toggleAlbumLayout()
                    }
                    else -> Unit
                }
                SortMenuButton(
                    current = sorts[selectedTab] ?: com.tempobox.model.SortSpec(),
                    onChange = { viewModel.setSort(selectedTab, it) },
                )
            },
        )

        ScrollableTabRow(selectedTabIndex = LibraryTab.entries.indexOf(selectedTab)) {
            LibraryTab.entries.forEach { tab ->
                Tab(
                    selected = tab == selectedTab,
                    onClick = { selectedTab = tab },
                    text = { Text(tabLabel(tab)) },
                )
            }
        }

        when (selectedTab) {
            LibraryTab.ALBUM_ARTISTS -> ArtistsTab(viewModel, actions, ui.artistLayout, onOpenArtist)
            LibraryTab.ALBUMS -> AlbumsTab(
                albums = viewModel.albums.collectAsState().value,
                layout = ui.albumLayout,
                actions = actions,
                swipeLeft = ui.swipeLeft,
                swipeRight = ui.swipeRight,
                onOpenAlbum = onOpenAlbum,
            )
            LibraryTab.GENRES -> GenresTab(
                genres = viewModel.genres.collectAsState().value,
                actions = actions,
                swipeLeft = ui.swipeLeft,
                swipeRight = ui.swipeRight,
                onOpenGenre = onOpenGenre,
            )
            LibraryTab.TRACKS -> TracksTab(
                tracks = viewModel.tracks.collectAsState().value,
                actions = actions,
                swipeLeft = ui.swipeLeft,
                swipeRight = ui.swipeRight,
            )
            LibraryTab.PLAYLISTS -> PlaylistsTab(
                playlists = viewModel.playlists.collectAsState().value,
                actions = actions,
                swipeLeft = ui.swipeLeft,
                swipeRight = ui.swipeRight,
                onOpenPlaylist = onOpenPlaylist,
            )
            LibraryTab.RECENTLY_ADDED -> RecentlyAddedTab(
                viewModel = viewModel,
                actions = actions,
                ui = ui,
                onOpenArtist = onOpenArtist,
                onOpenAlbum = onOpenAlbum,
                onOpenGenre = onOpenGenre,
                onOpenPlaylist = onOpenPlaylist,
            )
        }
    }

    ActionDialogHost(actions)
}

@Composable
private fun LayoutToggle(current: ViewLayout, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (current == ViewLayout.CARD) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
            contentDescription = if (current == ViewLayout.CARD) "Switch to list" else "Switch to cards",
        )
    }
}

private fun tabLabel(tab: LibraryTab): String = when (tab) {
    LibraryTab.ALBUM_ARTISTS -> "Artists"
    LibraryTab.ALBUMS -> "Albums"
    LibraryTab.GENRES -> "Genres"
    LibraryTab.TRACKS -> "Tracks"
    LibraryTab.PLAYLISTS -> "Playlists"
    LibraryTab.RECENTLY_ADDED -> "Recently Added"
}

// --------------------------------------------------------------------- artists

@Composable
private fun ArtistsTab(
    viewModel: LibraryViewModel,
    actions: LibraryActionsViewModel,
    layout: ViewLayout,
    onOpenArtist: (String) -> Unit,
) {
    val artists by viewModel.artists.collectAsState()
    val genreNames by viewModel.genreNames.collectAsState()
    val genreFilter by viewModel.artistGenreFilter.collectAsState()
    val preferImages by viewModel.preferArtistImages.collectAsState()
    val ui by viewModel.uiSettings.collectAsState()

    Column(Modifier.fillMaxSize()) {
        // Genre sub-browsing chips (spec: artist views sub-browsable by genre).
        if (genreNames.isNotEmpty()) {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = genreFilter == null,
                    onClick = { viewModel.artistGenreFilter.value = null },
                    label = { Text("All genres") },
                )
                genreNames.forEach { genre ->
                    FilterChip(
                        selected = genreFilter == genre,
                        onClick = {
                            viewModel.artistGenreFilter.value =
                                if (genreFilter == genre) null else genre
                        },
                        label = { Text(genre) },
                    )
                }
            }
        }

        if (layout == ViewLayout.CARD) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(artists, key = { it.name }) { artist ->
                    val item = LibraryItem.ArtistItem(artist)
                    Column(Modifier.clickable { onOpenArtist(artist.name) }) {
                        ArtistImage(
                            artistName = artist.name,
                            collageTrackPaths = artist.artworkTrackPaths,
                            preferRemoteImage = preferImages,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    artist.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${artist.albumCount} albums · ${artist.trackCount} tracks",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { actions.play(item) }) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = "Play ${artist.name}",
                                )
                            }
                            LibraryItemMenu(item = item, actions = actions)
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(artists, key = { it.name }) { artist ->
                    val item = LibraryItem.ArtistItem(artist)
                    SwipeableLibraryItem(item, ui.swipeLeft, ui.swipeRight, actions) {
                        CollectionRow(
                            item = item,
                            title = artist.name,
                            subtitle = "${artist.albumCount} albums · ${artist.trackCount} tracks",
                            actions = actions,
                            artwork = {
                                CollageArt(
                                    trackPaths = artist.artworkTrackPaths,
                                    modifier = Modifier.size(48.dp),
                                    cornerRadius = 8,
                                )
                            },
                            onOpen = { onOpenArtist(artist.name) },
                        )
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------- albums

@Composable
fun AlbumsTab(
    albums: List<com.tempobox.model.Album>,
    layout: ViewLayout,
    actions: LibraryActionsViewModel,
    swipeLeft: SwipeAction,
    swipeRight: SwipeAction,
    onOpenAlbum: (String, String) -> Unit,
) {
    if (layout == ViewLayout.CARD) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(albums, key = { "${it.albumArtist}|${it.name}" }) { album ->
                val item = LibraryItem.AlbumItem(album)
                Column(Modifier.clickable { onOpenAlbum(album.albumArtist, album.name) }) {
                    TrackArt(
                        trackPath = album.artworkTrackPath,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        cornerRadius = 12,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                album.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                album.albumArtist + (album.year?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { actions.play(item) }) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Play ${album.name}",
                            )
                        }
                        LibraryItemMenu(item = item, actions = actions)
                    }
                }
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(albums, key = { "${it.albumArtist}|${it.name}" }) { album ->
                val item = LibraryItem.AlbumItem(album)
                SwipeableLibraryItem(item, swipeLeft, swipeRight, actions) {
                    CollectionRow(
                        item = item,
                        title = album.name,
                        subtitle = "${album.albumArtist} · ${album.trackCount} tracks · " +
                            TimeFormat.duration(album.durationMs),
                        actions = actions,
                        artwork = {
                            TrackArt(
                                trackPath = album.artworkTrackPath,
                                modifier = Modifier.size(48.dp),
                            )
                        },
                        onOpen = { onOpenAlbum(album.albumArtist, album.name) },
                    )
                }
            }
        }
    }
}

// --------------------------------------------------------------------- genres

@Composable
fun GenresTab(
    genres: List<com.tempobox.model.Genre>,
    actions: LibraryActionsViewModel,
    swipeLeft: SwipeAction,
    swipeRight: SwipeAction,
    onOpenGenre: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(genres, key = { it.name }) { genre ->
            val item = LibraryItem.GenreItem(genre)
            SwipeableLibraryItem(item, swipeLeft, swipeRight, actions) {
                CollectionRow(
                    item = item,
                    title = genre.name,
                    subtitle = "${genre.albumCount} albums · ${genre.trackCount} tracks",
                    actions = actions,
                    artwork = {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onOpen = { onOpenGenre(genre.name) },
                )
            }
        }
    }
}

// --------------------------------------------------------------------- tracks

@Composable
fun TracksTab(
    tracks: List<com.tempobox.model.Track>,
    actions: LibraryActionsViewModel,
    swipeLeft: SwipeAction,
    swipeRight: SwipeAction,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
            val item = LibraryItem.TrackItem(track)
            SwipeableLibraryItem(item, swipeLeft, swipeRight, actions) {
                TrackRow(
                    track = track,
                    actions = actions,
                    onClick = { actions.playFrom(tracks, index) },
                )
            }
        }
    }
}

// --------------------------------------------------------------------- playlists

@Composable
fun PlaylistsTab(
    playlists: List<com.tempobox.model.Playlist>,
    actions: LibraryActionsViewModel,
    swipeLeft: SwipeAction,
    swipeRight: SwipeAction,
    onOpenPlaylist: (Long) -> Unit,
) {
    val playlistsViewModel: PlaylistsViewModel = hiltViewModel()
    var creating by rememberSaveable { mutableStateOf(false) }
    var creatingAuto by rememberSaveable { mutableStateOf(false) }

    if (creating) {
        TextInputDialog(
            title = "New playlist",
            placeholder = "Playlist name",
            onConfirm = {
                playlistsViewModel.createPlaylist(it)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }
    if (creatingAuto) {
        com.tempobox.ui.components.SmartRuleBuilderDialog(
            suggestedName = "",
            initialRule = null,
            onCreate = { name, rule ->
                actions.createAutoPlaylist(name, rule)
                creatingAuto = false
            },
            onDismiss = { creatingAuto = false },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp)) {
            TextButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("New playlist")
            }
            TextButton(onClick = { creatingAuto = true }) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                Text("New auto playlist")
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(playlists, key = { it.id }) { playlist ->
                val item = LibraryItem.PlaylistItem(playlist)
                SwipeableLibraryItem(item, swipeLeft, swipeRight, actions) {
                    CollectionRow(
                        item = item,
                        title = playlist.name + if (playlist.isSmart) "  ✨" else "",
                        subtitle = "${playlist.trackCount} tracks · " +
                            TimeFormat.duration(playlist.durationMs) +
                            if (playlist.isSmart) " · auto" else "",
                        actions = actions,
                        artwork = {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistPlay,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        onOpen = { onOpenPlaylist(playlist.id) },
                    )
                }
            }
        }
    }
}

// --------------------------------------------------------------------- recently added

/** Recently Added: its own sub-tab row over the same content composables. */
@Composable
private fun RecentlyAddedTab(
    viewModel: LibraryViewModel,
    actions: LibraryActionsViewModel,
    ui: com.tempobox.settings.UiSettings,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String, String) -> Unit,
    onOpenGenre: (String) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
) {
    val subTabs = listOf(
        LibraryTab.ALBUM_ARTISTS,
        LibraryTab.ALBUMS,
        LibraryTab.GENRES,
        LibraryTab.TRACKS,
        LibraryTab.PLAYLISTS,
    )
    var subTab by rememberSaveable { mutableStateOf(LibraryTab.TRACKS) }

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = subTabs.indexOf(subTab)) {
            subTabs.forEach { tab ->
                Tab(
                    selected = tab == subTab,
                    onClick = { subTab = tab },
                    text = { Text(tabLabel(tab)) },
                )
            }
        }
        when (subTab) {
            LibraryTab.ALBUM_ARTISTS -> {
                val artists by viewModel.recentArtists.collectAsState()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(artists, key = { it.name }) { artist ->
                        val item = LibraryItem.ArtistItem(artist)
                        SwipeableLibraryItem(item, ui.swipeLeft, ui.swipeRight, actions) {
                            CollectionRow(
                                item = item,
                                title = artist.name,
                                subtitle = "${artist.albumCount} albums · ${artist.trackCount} tracks",
                                actions = actions,
                                artwork = {
                                    CollageArt(
                                        trackPaths = artist.artworkTrackPaths,
                                        modifier = Modifier.size(48.dp),
                                        cornerRadius = 8,
                                    )
                                },
                                onOpen = { onOpenArtist(artist.name) },
                            )
                        }
                    }
                }
            }
            LibraryTab.ALBUMS -> AlbumsTab(
                albums = viewModel.recentAlbums.collectAsState().value,
                layout = ViewLayout.LIST,
                actions = actions,
                swipeLeft = ui.swipeLeft,
                swipeRight = ui.swipeRight,
                onOpenAlbum = onOpenAlbum,
            )
            LibraryTab.GENRES -> GenresTab(
                genres = viewModel.recentGenres.collectAsState().value,
                actions = actions,
                swipeLeft = ui.swipeLeft,
                swipeRight = ui.swipeRight,
                onOpenGenre = onOpenGenre,
            )
            LibraryTab.PLAYLISTS -> PlaylistsTab(
                playlists = viewModel.recentPlaylists.collectAsState().value,
                actions = actions,
                swipeLeft = ui.swipeLeft,
                swipeRight = ui.swipeRight,
                onOpenPlaylist = onOpenPlaylist,
            )
            else -> TracksTab(
                tracks = viewModel.recentTracks.collectAsState().value,
                actions = actions,
                swipeLeft = ui.swipeLeft,
                swipeRight = ui.swipeRight,
            )
        }
    }
}
