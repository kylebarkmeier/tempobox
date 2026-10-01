package com.tempobox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.tempobox.model.SwipeAction
import com.tempobox.ui.library.LibraryActionsViewModel
import com.tempobox.ui.library.LibraryItem

/**
 * Wraps any library row with the user's configured left/right swipe gestures
 * (Settings ▸ UI ▸ Swipes). The row snaps back after triggering — swipes fire
 * actions, they don't dismiss.
 */
@Composable
fun SwipeableLibraryItem(
    item: LibraryItem,
    swipeLeft: SwipeAction,
    swipeRight: SwipeAction,
    actions: LibraryActionsViewModel,
    content: @Composable () -> Unit,
) {
    if (swipeLeft == SwipeAction.NONE && swipeRight == SwipeAction.NONE) {
        content()
        return
    }

    // Deliberate drag required (50% of the row width) — a fast flick while
    // scrolling must not fire.
    val swipe = rememberDeliberateSwipeState()
    val state = swipe.state

    LaunchedEffect(state.currentValue) {
        when (state.currentValue) {
            // StartToEnd = drag toward the right ⇒ the user's "right swipe".
            SwipeToDismissBoxValue.StartToEnd -> {
                actions.performSwipe(swipeRight, item)
                state.reset()
            }
            SwipeToDismissBoxValue.EndToStart -> {
                actions.performSwipe(swipeLeft, item)
                state.reset()
            }
            SwipeToDismissBoxValue.Settled -> Unit
        }
    }

    SwipeToDismissBox(
        state = state,
        modifier = swipe.sizeModifier,
        enableDismissFromStartToEnd = swipeRight != SwipeAction.NONE,
        enableDismissFromEndToStart = swipeLeft != SwipeAction.NONE,
        backgroundContent = {
            // Only paint while a swipe is actually revealing the background —
            // rows are transparent, so a settled-state color/icon would show
            // through the whole list.
            val (action, alignment) = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> swipeRight to Alignment.CenterStart
                SwipeToDismissBoxValue.EndToStart -> swipeLeft to Alignment.CenterEnd
                else -> SwipeAction.NONE to Alignment.Center
            }
            if (action != SwipeAction.NONE) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(swipeColor(action)),
                    contentAlignment = alignment,
                ) {
                    Icon(
                        imageVector = swipeIcon(action),
                        contentDescription = swipeLabel(action),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        },
        content = {
            // Opaque surface so the swipe background never bleeds through.
            Box(Modifier.background(MaterialTheme.colorScheme.surface)) { content() }
        },
    )
}

@Composable
private fun swipeColor(action: SwipeAction) = when (action) {
    SwipeAction.REMOVE_FROM_LIBRARY, SwipeAction.DELETE_PERMANENTLY ->
        MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
}

private fun swipeIcon(action: SwipeAction): ImageVector = when (action) {
    SwipeAction.ADD_TO_QUEUE -> Icons.AutoMirrored.Filled.QueueMusic
    SwipeAction.ADD_TO_PLAYLIST -> Icons.AutoMirrored.Filled.PlaylistAdd
    SwipeAction.CREATE_AUTO_PLAYLIST -> Icons.Filled.AutoAwesome
    SwipeAction.SHUFFLE -> Icons.Filled.Shuffle
    SwipeAction.REMOVE_FROM_LIBRARY -> Icons.Filled.Delete
    SwipeAction.EDIT_TAGS -> Icons.Filled.Edit
    SwipeAction.DELETE_PERMANENTLY -> Icons.Filled.DeleteForever
    SwipeAction.NONE -> Icons.Filled.Shuffle
}

/** Human labels reused by the swipe settings screen. */
fun swipeLabel(action: SwipeAction): String = when (action) {
    SwipeAction.NONE -> "Nothing"
    SwipeAction.ADD_TO_QUEUE -> "Add to queue"
    SwipeAction.ADD_TO_PLAYLIST -> "Add to playlist"
    SwipeAction.CREATE_AUTO_PLAYLIST -> "Create auto playlist"
    SwipeAction.SHUFFLE -> "Shuffle"
    SwipeAction.REMOVE_FROM_LIBRARY -> "Remove from library"
    SwipeAction.EDIT_TAGS -> "Edit ID3 tags"
    SwipeAction.DELETE_PERMANENTLY -> "Delete permanently"
}
