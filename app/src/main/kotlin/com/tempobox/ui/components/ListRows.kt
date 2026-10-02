package com.tempobox.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tempobox.common.TimeFormat
import com.tempobox.model.STANDARD_SORT_KEYS
import com.tempobox.model.SortKey
import com.tempobox.model.SortSpec
import com.tempobox.model.Track
import com.tempobox.model.defaultAscending
import com.tempobox.ui.library.LibraryActionsViewModel
import com.tempobox.ui.library.LibraryItem

/**
 * Standard track row: art, title, artist, duration, rating stars, 3-dot menu.
 * Tap plays (behavior injected by the hosting list via [onClick]).
 */
@Composable
fun TrackRow(
    track: Track,
    actions: LibraryActionsViewModel,
    modifier: Modifier = Modifier,
    subtitle: String = track.artist.ifBlank { track.effectiveAlbumArtist },
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackArt(
            trackPath = if (track.hasEmbeddedArt) track.filePath else null,
            modifier = Modifier.size(48.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track.rating > 0) {
                RatingBar(rating = track.rating, starSize = 12.dp)
            }
        }
        Text(
            TimeFormat.duration(track.durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LibraryItemMenu(item = LibraryItem.TrackItem(track), actions = actions)
    }
}

/**
 * Generic list row for collections (artist / album / genre / playlist):
 * artwork, title/subtitle, immediate Play button, standard 3-dot menu.
 */
@Composable
fun CollectionRow(
    item: LibraryItem,
    title: String,
    subtitle: String,
    actions: LibraryActionsViewModel,
    modifier: Modifier = Modifier,
    artwork: @Composable () -> Unit,
    onOpen: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onOpen != null) it.clickable(onClick = onOpen) else it }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        artwork()
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Immediate play: overwrites the queue with this item (product spec).
        IconButton(onClick = { actions.play(item) }) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Play $title")
        }
        LibraryItemMenu(item = item, actions = actions)
    }
}

/**
 * Sort control shown in every library view's toolbar (main tabs and detail
 * screens alike): pick a key from [keys] and tap the active key again to flip
 * direction. Main tabs offer the standard five; detail views pass their own
 * list from [com.tempobox.model.LibrarySubview.sortKeys].
 */
@Composable
fun SortMenuButton(
    current: SortSpec,
    onChange: (SortSpec) -> Unit,
    keys: List<SortKey> = STANDARD_SORT_KEYS,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            keys.forEach { key ->
                DropdownMenuItem(
                    text = { Text(sortLabel(key)) },
                    trailingIcon = {
                        if (key == current.key) {
                            Icon(
                                if (current.ascending) Icons.Filled.ArrowUpward
                                else Icons.Filled.ArrowDownward,
                                contentDescription = null,
                            )
                        }
                    },
                    onClick = {
                        open = false
                        onChange(
                            if (key == current.key) {
                                current.copy(ascending = !current.ascending)
                            } else {
                                SortSpec(key = key, ascending = key.defaultAscending())
                            },
                        )
                    },
                )
            }
        }
    }
}

fun sortLabel(key: SortKey): String = when (key) {
    SortKey.ALPHABETICAL -> "Alphabetical"
    SortKey.RECENTLY_ADDED -> "Recently added"
    SortKey.LAST_MODIFIED -> "Last modified"
    SortKey.RATING -> "Rating"
    SortKey.TAG_DATE -> "Tag date (year)"
    SortKey.TRACK_NUMBER -> "Track number"
    SortKey.ALBUM_ORDER -> "Album order"
    SortKey.DURATION -> "Duration"
    SortKey.PLAY_COUNT -> "Play count"
    SortKey.PLAYLIST_ORDER -> "Playlist order"
}
