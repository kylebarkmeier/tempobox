package com.tempobox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.hilt.navigation.compose.hiltViewModel
import com.tempobox.model.TagData
import com.tempobox.model.Track
import com.tempobox.ui.library.LibraryActionsViewModel
import com.tempobox.ui.library.LibraryActionsViewModel.Dialog
import com.tempobox.ui.library.LibraryItem
import com.tempobox.ui.library.PlaylistsViewModel

/**
 * Renders whichever modal the shared action layer requested. Include ONCE per
 * screen that uses [LibraryActionsViewModel] — this is what makes the
 * "standard options" identical everywhere.
 */
@Composable
fun ActionDialogHost(actions: LibraryActionsViewModel) {
    val dialog by actions.dialog.collectAsState()
    val current = dialog ?: return

    when (current) {
        is Dialog.ConfirmRemove -> ConfirmDialog(
            title = "Remove from library?",
            text = "\"${current.item.title}\" will be removed from the library. " +
                "Files on the device are not touched.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = { actions.confirmRemoveFromLibrary(current.item) },
            onDismiss = actions::dismissDialog,
        )

        is Dialog.ConfirmDelete -> ConfirmDialog(
            title = "Delete permanently?",
            text = "\"${current.item.title}\" will be permanently deleted from this device. " +
                "This cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { actions.confirmDelete(current.item) },
            onDismiss = actions::dismissDialog,
        )

        is Dialog.AddToPlaylist -> {
            val playlistsViewModel: PlaylistsViewModel = hiltViewModel()
            val playlists by playlistsViewModel.playlists.collectAsState()
            AddToPlaylistDialog(
                playlists = playlists,
                onPick = { actions.addToExistingPlaylist(current.item, it.id, it.name) },
                onCreateNew = { actions.addToNewPlaylist(current.item, it) },
                onDismiss = actions::dismissDialog,
            )
        }

        is Dialog.EditTags -> {
            // Resolve tracks once to know single-vs-bulk and prefill values.
            val tracks by produceState(initialValue = emptyList<Track>(), current) {
                value = actions.resolveTracks(current.item)
            }
            if (tracks.isNotEmpty()) {
                TagEditorDialog(
                    subjectLabel = current.item.title,
                    trackCount = tracks.size,
                    initial = if (tracks.size == 1) TagData.from(tracks.first()) else null,
                    onApply = { actions.applyTagEdit(current.item, it) },
                    onDismiss = actions::dismissDialog,
                )
            }
        }

        is Dialog.Rate -> RatingDialog(
            trackTitle = current.track.title,
            currentRating = current.track.rating,
            onRate = { actions.rate(current.track.id, it) },
            onDismiss = actions::dismissDialog,
        )

        is Dialog.CreateAutoPlaylist -> SmartRuleBuilderDialog(
            suggestedName = current.suggestedName,
            initialRule = current.initialRule,
            onCreate = actions::createAutoPlaylist,
            onDismiss = actions::dismissDialog,
        )
    }
}
