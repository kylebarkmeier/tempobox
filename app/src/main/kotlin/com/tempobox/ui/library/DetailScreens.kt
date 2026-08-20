package com.tempobox.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tempobox.model.ViewLayout
import com.tempobox.ui.components.ActionDialogHost
import com.tempobox.ui.components.TrackRow

/**
 * Detail screens share one scaffold: back button, play/shuffle in the bar,
 * the standard action layer, and the same tab/list building blocks as the
 * main library — no duplicated list logic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(
    title: String,
    item: LibraryItem?,
    actions: LibraryActionsViewModel,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (item != null) {
                    IconButton(onClick = { actions.play(item) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play $title")
                    }
                    IconButton(onClick = { actions.shuffle(item) }) {
                        Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle $title")
                    }
                }
            },
        )
        content()
    }
    ActionDialogHost(actions)
}

// --------------------------------------------------------------------- artist

/** Artist page: albums grid + "All tracks" tab (product spec). */
@Composable
fun ArtistDetailScreen(
    name: String,
    onBack: () -> Unit,
    onOpenAlbum: (String, String) -> Unit,
) {
    val viewModel: ArtistDetailViewModel = hiltViewModel()
    val actions: LibraryActionsViewModel = hiltViewModel()
    val libraryViewModel: LibraryViewModel = hiltViewModel()

    val albums by viewModel.albums.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val ui by libraryViewModel.uiSettings.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val artistItem = albums.firstOrNull()?.let {
        LibraryItem.ArtistItem(
            com.tempobox.model.AlbumArtist(
                name = viewModel.name,
                albumCount = albums.size,
                trackCount = tracks.size,
            ),
        )
    }

    DetailScaffold(title = viewModel.name, item = artistItem, actions = actions, onBack = onBack) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Albums") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("All tracks") })
        }
        if (tab == 0) {
            AlbumsTab(
                albums = albums,
                layout = ViewLayout.CARD,
                actions = actions,
                swipeLeft = ui.swipeLeft,
                swipeRight = ui.swipeRight,
                onOpenAlbum = onOpenAlbum,
            )
        } else {
            TracksTab(
                tracks = tracks,
                actions = actions,
                swipeLeft = ui.swipeLeft,
                swipeRight = ui.swipeRight,
            )
        }
    }
}

// --------------------------------------------------------------------- album

@Composable
fun AlbumDetailScreen(
    albumArtist: String,
    album: String,
    onBack: () -> Unit,
) {
    val viewModel: AlbumDetailViewModel = hiltViewModel()
    val actions: LibraryActionsViewModel = hiltViewModel()
    val libraryViewModel: LibraryViewModel = hiltViewModel()

    val tracks by viewModel.tracks.collectAsState()
    val ui by libraryViewModel.uiSettings.collectAsState()

    val albumItem = tracks.firstOrNull()?.let { first ->
        LibraryItem.AlbumItem(
            com.tempobox.model.Album(
                name = viewModel.album,
                albumArtist = viewModel.albumArtist,
                year = first.year,
                trackCount = tracks.size,
                durationMs = tracks.sumOf { it.durationMs },
                artworkTrackPath = tracks.firstOrNull { it.hasEmbeddedArt }?.filePath,
                dateAddedMs = 0,
                dateModifiedMs = 0,
            ),
        )
    }

    DetailScaffold(title = viewModel.album, item = albumItem, actions = actions, onBack = onBack) {
        TracksTab(
            tracks = tracks,
            actions = actions,
            swipeLeft = ui.swipeLeft,
            swipeRight = ui.swipeRight,
        )
    }
}

// --------------------------------------------------------------------- genre

/** Genre page, sub-browsable by Artists / Albums / Recently Added / Tracks. */
@Composable
fun GenreDetailScreen(
    name: String,
    onBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String, String) -> Unit,
) {
    val viewModel: GenreDetailViewModel = hiltViewModel()
    val actions: LibraryActionsViewModel = hiltViewModel()
    val libraryViewModel: LibraryViewModel = hiltViewModel()

    val artists by viewModel.artists.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val recentTracks by viewModel.recentTracks.collectAsState()
    val ui by libraryViewModel.uiSettings.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val genreItem = LibraryItem.GenreItem(
        com.tempobox.model.Genre(name = viewModel.name, trackCount = tracks.size, albumCount = albums.size),
    )

    DetailScaffold(title = viewModel.name, item = genreItem, actions = actions, onBack = onBack) {
        TabRow(selectedTabIndex = tab) {
            listOf("Artists", "Albums", "Recent", "Tracks").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
            }
        }
        when (tab) {
            0 -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(artists, key = { _, a -> a.name }) { _, artist ->
                    com.tempobox.ui.components.CollectionRow(
                        item = LibraryItem.ArtistItem(artist),
                        title = artist.name,
                        subtitle = "${artist.albumCount} albums · ${artist.trackCount} tracks",
                        actions = actions,
                        artwork = {
                            com.tempobox.ui.components.CollageArt(
                                trackPaths = artist.artworkTrackPaths,
                                modifier = Modifier.size(48.dp),
                                cornerRadius = 8,
                            )
                        },
                        onOpen = { onOpenArtist(artist.name) },
                    )
                }
            }
            1 -> AlbumsTab(
                albums = albums,
                layout = ViewLayout.LIST,
                actions = actions,
                swipeLeft = ui.swipeLeft,
                swipeRight = ui.swipeRight,
                onOpenAlbum = onOpenAlbum,
            )
            2 -> TracksTab(recentTracks, actions, ui.swipeLeft, ui.swipeRight)
            else -> TracksTab(tracks, actions, ui.swipeLeft, ui.swipeRight)
        }
    }
}

// --------------------------------------------------------------------- playlist

@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    onBack: () -> Unit,
) {
    val viewModel: PlaylistDetailViewModel = hiltViewModel()
    val actions: LibraryActionsViewModel = hiltViewModel()

    val playlist by viewModel.playlist.collectAsState()
    val tracks by viewModel.tracks.collectAsState()

    val item = playlist?.let { LibraryItem.PlaylistItem(it) }

    DetailScaffold(
        title = playlist?.name ?: "Playlist",
        item = item,
        actions = actions,
        onBack = onBack,
    ) {
        if (playlist?.isSmart == true) {
            Text(
                "Auto playlist — updates automatically as your library changes.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(tracks, key = { index, t -> "$index-${t.id}" }) { index, track ->
                TrackRow(
                    track = track,
                    actions = actions,
                    onClick = { actions.playFrom(tracks, index) },
                )
            }
        }
    }
}
