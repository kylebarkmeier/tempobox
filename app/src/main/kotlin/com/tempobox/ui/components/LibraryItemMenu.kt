package com.tempobox.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tempobox.ui.library.LibraryActionsViewModel
import com.tempobox.ui.library.LibraryItem

/**
 * The standard 3-dot "more" menu every library item shows (product spec):
 * Shuffle (collections), Add to queue, Play next, Go to artist (tracks and
 * albums), Go to album (tracks), Add to playlist, Create auto playlist,
 * Edit ID3 tags, Rate (tracks), Remove from library, Delete permanently.
 * Extra entries can be appended per screen via [extras].
 */
@Composable
fun LibraryItemMenu(
    item: LibraryItem,
    actions: LibraryActionsViewModel,
    extras: List<Pair<String, () -> Unit>> = emptyList(),
) {
    var open by remember { mutableStateOf(false) }

    // Box anchors the menu to the button (not the whole row).
    androidx.compose.foundation.layout.Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options for ${item.title}")
        }
        MenuContent(item, actions, extras, open, onDismiss = { open = false })
    }
}

@Composable
private fun MenuContent(
    item: LibraryItem,
    actions: LibraryActionsViewModel,
    extras: List<Pair<String, () -> Unit>>,
    open: Boolean,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        if (item.isCollection) {
            DropdownMenuItem(
                text = { Text("Shuffle") },
                onClick = { onDismiss(); actions.shuffle(item) },
            )
        }
        DropdownMenuItem(
            text = { Text("Add to queue") },
            onClick = { onDismiss(); actions.addToQueue(item) },
        )
        DropdownMenuItem(
            text = { Text("Play next") },
            onClick = { onDismiss(); actions.playNext(item) },
        )
        // Visibility mirrors the action layer: entries only appear when the
        // item actually resolves to an artist/album destination.
        if (LibraryActionsViewModel.artistDestination(item) != null) {
            DropdownMenuItem(
                text = { Text("Go to artist") },
                onClick = { onDismiss(); actions.goToArtist(item) },
            )
        }
        if (LibraryActionsViewModel.albumDestination(item) != null) {
            DropdownMenuItem(
                text = { Text("Go to album") },
                onClick = { onDismiss(); actions.goToAlbum(item) },
            )
        }
        DropdownMenuItem(
            text = { Text("Add to playlist") },
            onClick = { onDismiss(); actions.requestAddToPlaylist(item) },
        )
        if (item !is LibraryItem.PlaylistItem) {
            DropdownMenuItem(
                text = { Text("Create auto playlist") },
                onClick = { onDismiss(); actions.requestCreateAutoPlaylist(item) },
            )
            DropdownMenuItem(
                text = { Text("Edit ID3 tags") },
                onClick = { onDismiss(); actions.requestEditTags(item) },
            )
        }
        if (item is LibraryItem.TrackItem) {
            DropdownMenuItem(
                text = { Text("Rate") },
                onClick = { onDismiss(); actions.requestRate(item.track) },
            )
        }
        extras.forEach { (label, action) ->
            DropdownMenuItem(
                text = { Text(label) },
                onClick = { onDismiss(); action() },
            )
        }
        DropdownMenuItem(
            text = { Text("Remove from library") },
            onClick = { onDismiss(); actions.requestRemoveFromLibrary(item) },
        )
        if (item !is LibraryItem.PlaylistItem || item.playlist.filePath != null) {
            DropdownMenuItem(
                text = { Text("Delete permanently") },
                onClick = { onDismiss(); actions.requestDelete(item) },
            )
        }
    }
}
