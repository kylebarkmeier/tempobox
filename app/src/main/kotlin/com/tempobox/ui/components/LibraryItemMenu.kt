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
 * Shuffle (collections), Add to queue, Play next, Add to playlist,
 * Create auto playlist, Edit ID3 tags, Rate (tracks), Remove from library,
 * Delete permanently. Extra entries can be appended per screen via [extras].
 */
@Composable
fun LibraryItemMenu(
    item: LibraryItem,
    actions: LibraryActionsViewModel,
    extras: List<Pair<String, () -> Unit>> = emptyList(),
) {
    var open by remember { mutableStateOf(false) }

    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More options for ${item.title}")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        if (item.isCollection) {
            DropdownMenuItem(
                text = { Text("Shuffle") },
                onClick = { open = false; actions.shuffle(item) },
            )
        }
        DropdownMenuItem(
            text = { Text("Add to queue") },
            onClick = { open = false; actions.addToQueue(item) },
        )
        DropdownMenuItem(
            text = { Text("Play next") },
            onClick = { open = false; actions.playNext(item) },
        )
        DropdownMenuItem(
            text = { Text("Add to playlist") },
            onClick = { open = false; actions.requestAddToPlaylist(item) },
        )
        if (item !is LibraryItem.PlaylistItem) {
            DropdownMenuItem(
                text = { Text("Create auto playlist") },
                onClick = { open = false; actions.requestCreateAutoPlaylist(item) },
            )
            DropdownMenuItem(
                text = { Text("Edit ID3 tags") },
                onClick = { open = false; actions.requestEditTags(item) },
            )
        }
        if (item is LibraryItem.TrackItem) {
            DropdownMenuItem(
                text = { Text("Rate") },
                onClick = { open = false; actions.requestRate(item.track) },
            )
        }
        extras.forEach { (label, action) ->
            DropdownMenuItem(
                text = { Text(label) },
                onClick = { open = false; action() },
            )
        }
        DropdownMenuItem(
            text = { Text("Remove from library") },
            onClick = { open = false; actions.requestRemoveFromLibrary(item) },
        )
        if (item !is LibraryItem.PlaylistItem || item.playlist.filePath != null) {
            DropdownMenuItem(
                text = { Text("Delete permanently") },
                onClick = { open = false; actions.requestDelete(item) },
            )
        }
    }
}
