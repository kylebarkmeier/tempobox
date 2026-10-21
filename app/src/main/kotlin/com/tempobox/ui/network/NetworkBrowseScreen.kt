package com.tempobox.ui.network

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tempobox.common.TimeFormat
import com.tempobox.model.NetworkDevice
import com.tempobox.remoteplay.DidlLite

/**
 * "Network" drawer destination: DLNA media servers on the Wi-Fi (a NAS,
 * miniDLNA, Plex, Jellyfin...), with folder drill-down and tap-to-play.
 * Playback happens on the local player by streaming the server's URL; all
 * browsing logic lives in core:remoteplay via [NetworkBrowseViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkBrowseScreen(openDrawer: () -> Unit) {
    val viewModel: NetworkBrowseViewModel = hiltViewModel()
    val servers by viewModel.servers.collectAsState()
    val state by viewModel.state.collectAsState()

    // Discovery (and its multicast lock) only while this screen shows.
    DisposableEffect(viewModel) {
        viewModel.startDiscovery()
        onDispose { viewModel.stopDiscovery() }
    }

    // System back walks up the folder tree before leaving the screen.
    BackHandler(enabled = state.server != null) { viewModel.navigateUp() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.crumbs.lastOrNull()?.title
                            ?: state.server?.name
                            ?: "Network",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (state.server != null) {
                        IconButton(onClick = { viewModel.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    } else {
                        IconButton(onClick = openDrawer) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open menu")
                        }
                    }
                },
                actions = {
                    if (state.entries.any { it is DidlLite.Entry.Audio }) {
                        IconButton(onClick = viewModel::playAll) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Play all")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.server == null -> ServerList(servers, onOpen = viewModel::openServer)
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null -> CenteredNote(state.error.orEmpty())
                state.entries.isEmpty() -> CenteredNote("Nothing here")
                else -> EntryList(
                    entries = state.entries,
                    onOpenFolder = viewModel::openFolder,
                    onPlay = viewModel::playFrom,
                    onAddToQueue = viewModel::addToQueue,
                )
            }
        }
    }
}

@Composable
private fun ServerList(servers: List<NetworkDevice>, onOpen: (NetworkDevice) -> Unit) {
    if (servers.isEmpty()) {
        CenteredNote("Looking for media servers on your Wi-Fi…")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(servers, key = { it.id }) { server ->
            ListItem(
                headlineContent = { Text(server.name) },
                leadingContent = { Icon(Icons.Filled.Dns, contentDescription = null) },
                modifier = Modifier.clickable { onOpen(server) },
            )
        }
    }
}

@Composable
private fun EntryList(
    entries: List<DidlLite.Entry>,
    onOpenFolder: (DidlLite.Entry.Folder) -> Unit,
    onPlay: (DidlLite.Entry.Audio) -> Unit,
    onAddToQueue: (DidlLite.Entry.Audio) -> Unit,
) {
    // No stable keys: sloppy servers reuse DIDL ids, and the list never reorders.
    LazyColumn(Modifier.fillMaxSize()) {
        items(entries) { entry ->
            when (entry) {
                is DidlLite.Entry.Folder -> ListItem(
                    headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = entry.childCount?.let { { Text("$it items") } },
                    leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                    modifier = Modifier.clickable { onOpenFolder(entry) },
                )
                is DidlLite.Entry.Audio -> ListItem(
                    headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val artist = entry.artist.ifBlank { null }
                        val duration = entry.durationMs.takeIf { it > 0 }
                            ?.let(TimeFormat::duration)
                        Text(listOfNotNull(artist, duration).joinToString(" · "))
                    },
                    leadingContent = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
                    trailingContent = {
                        IconButton(onClick = { onAddToQueue(entry) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = "Add to queue",
                            )
                        }
                    },
                    modifier = Modifier.clickable { onPlay(entry) },
                )
            }
        }
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
