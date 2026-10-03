package com.tempobox.ui.queue

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tempobox.common.TimeFormat
import com.tempobox.model.QueueItem
import com.tempobox.ui.components.ActionDialogHost
import com.tempobox.ui.components.ConfirmDialog
import com.tempobox.ui.components.FastScrollLazyColumn
import com.tempobox.ui.components.LibraryItemMenu
import com.tempobox.ui.components.TrackArt
import com.tempobox.ui.library.LibraryActionsViewModel
import com.tempobox.ui.library.LibraryItem

/**
 * Play queue view (product spec): each row shows art, artist, title, time and
 * the standard 3-dot menu; swipe removes; Clear button up top; multi-select
 * via toggle button or long-press, exposing remove / save-to-playlist /
 * edit-tags bulk actions. The playing row gets a highlight + animated bars.
 */
@Composable
fun QueueScreen(openDrawer: () -> Unit, onBack: () -> Unit) {
    QueuePanel(onBack = onBack)
}

/**
 * The queue UI itself — one component shared by the Queue screen and the Now
 * Playing slide-up queue drawer, so both have identical controls.
 *
 * @param onBack null hides the back arrow (e.g. inside a bottom sheet).
 * @param hostActionDialogs false when the caller already hosts an
 *   [ActionDialogHost] for the same (shared) [LibraryActionsViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun QueuePanel(onBack: (() -> Unit)? = null, hostActionDialogs: Boolean = true) {
    val viewModel: QueueViewModel = hiltViewModel()
    val actions: LibraryActionsViewModel = hiltViewModel()

    val queue by viewModel.queue.collectAsState()
    val state by viewModel.state.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val confirmClear by viewModel.confirmClear.collectAsState()
    val multiSelect = selection != null

    var showClearDialog by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (multiSelect) "${selection?.size ?: 0} selected" else "Queue (${queue.size})") },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = {
                if (multiSelect) {
                    // Multi-select mode: bulk action icons (spec).
                    IconButton(onClick = viewModel::removeSelected) {
                        Icon(Icons.Filled.RemoveCircleOutline, contentDescription = "Remove selected from queue")
                    }
                    IconButton(onClick = { viewModel.selectedItem()?.let(actions::requestAddToPlaylist) }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Save selected to playlist")
                    }
                    IconButton(onClick = { viewModel.selectedItem()?.let(actions::requestEditTags) }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit tags of selected")
                    }
                    viewModel.selectedItem()?.let { item ->
                        LibraryItemMenu(item = item, actions = actions)
                    }
                } else {
                    // Clear queue (top of list, spec) + multi-select toggle.
                    IconButton(onClick = {
                        if (confirmClear) showClearDialog = true else viewModel.clearQueue()
                    }) {
                        Icon(Icons.Filled.ClearAll, contentDescription = "Clear queue")
                    }
                }
                IconButton(onClick = viewModel::toggleMultiSelect) {
                    Icon(
                        Icons.Filled.Checklist,
                        contentDescription = if (multiSelect) "Exit multi-select" else "Multi-select",
                        tint = if (multiSelect) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            },
        )

        FastScrollLazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(queue, key = { _, item -> item.uid }) { index, item ->
                QueueRow(
                    item = item,
                    isCurrent = index == state.queueIndex,
                    isPlaying = state.isPlaying,
                    selected = selection?.contains(item.uid) == true,
                    multiSelect = multiSelect,
                    actions = actions,
                    onClick = {
                        if (multiSelect) viewModel.toggleSelected(item.uid)
                        else viewModel.playItem(item.uid)
                    },
                    onLongPress = { viewModel.startMultiSelect(item.uid) },
                    onSwipedAway = { viewModel.remove(item.uid) },
                )
            }
        }
    }

    if (showClearDialog) {
        ConfirmDialog(
            title = "Clear queue?",
            text = "Remove all ${queue.size} tracks from the play queue and stop playback?",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = {
                viewModel.clearQueue()
                showClearDialog = false
            },
            onDismiss = { showClearDialog = false },
        )
    }

    if (hostActionDialogs) {
        ActionDialogHost(actions)
    }
}

// --------------------------------------------------------------------- rows

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun QueueRow(
    item: QueueItem,
    isCurrent: Boolean,
    isPlaying: Boolean,
    selected: Boolean,
    multiSelect: Boolean,
    actions: LibraryActionsViewModel,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onSwipedAway: () -> Unit,
) {
    // Swiping an item removes it from the queue (spec) — but only after a
    // deliberate half-width drag, so scroll flicks don't remove tracks.
    val swipe = com.tempobox.ui.components.rememberDeliberateSwipeState(
        confirmDismiss = { _ ->
            onSwipedAway()
            true
        },
    )

    SwipeToDismissBox(
        state = swipe.state,
        modifier = swipe.sizeModifier,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer),
            )
        },
    ) {
        val background = if (isCurrent) {
            // Different background for the currently playing track (spec).
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surface
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(background)
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (multiSelect) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            }
            if (isCurrent) {
                // Animated bars instead of album art for the playing row (spec).
                NowPlayingBars(animating = isPlaying, modifier = Modifier.size(48.dp))
            } else {
                TrackArt(
                    trackPath = if (item.track.hasEmbeddedArt) item.track.filePath else null,
                    modifier = Modifier.size(48.dp),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    item.track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.track.artist.ifBlank { item.track.effectiveAlbumArtist },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                TimeFormat.duration(item.track.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LibraryItemMenu(item = LibraryItem.TrackItem(item.track), actions = actions)
        }
    }
}

/** Three bouncing equalizer bars — the "now playing" indicator. */
@Composable
fun NowPlayingBars(animating: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "eq")
    val phases = listOf(0, 150, 300).map { delay ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 500, delayMillis = delay, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "bar$delay",
        )
    }
    Row(
        modifier = modifier.padding(10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom,
    ) {
        phases.forEach { phase ->
            Box(
                Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        scaleY = if (animating) phase.value else 0.4f
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                    }
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
