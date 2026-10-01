package com.tempobox.ui.nowplaying

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tempobox.common.TimeFormat
import com.tempobox.model.Corner
import com.tempobox.model.CornerAction
import com.tempobox.model.RepeatMode
import com.tempobox.model.ShuffleMode
import com.tempobox.model.Track
import com.tempobox.model.TrackInfoField
import com.tempobox.ui.LocalSnackbar
import com.tempobox.ui.components.ActionDialogHost
import com.tempobox.ui.components.TrackArt
import com.tempobox.ui.library.LibraryActionsViewModel
import com.tempobox.ui.library.LibraryItem
import com.tempobox.ui.queue.QueuePanel

/**
 * Full Now Playing view (product spec): scrolling "artist – track" header,
 * "album (year)" line, zoomable album art with configurable corner buttons
 * (track/artist/album actions only), configurable track info, seek bar with
 * elapsed/remaining toggle, shuffle on/off, repeat cycle, and a slide-up queue
 * drawer that is the same full-featured component as the Queue view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (albumArtist: String, album: String) -> Unit,
) {
    val viewModel: NowPlayingViewModel = hiltViewModel()
    val actions: LibraryActionsViewModel = hiltViewModel()

    val state by viewModel.state.collectAsState()
    val npSettings by viewModel.nowPlayingSettings.collectAsState()
    val track = state.track

    var showQueueSheet by rememberSaveable { mutableStateOf(false) }
    var showRemaining by rememberSaveable { mutableStateOf(false) }

    val snackbar = LocalSnackbar.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Now Playing") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { showQueueSheet = true }) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Show queue")
                }
            },
        )

        if (track == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing playing — pick something from the Library")
            }
            return@Column
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // "artist - track name", scrolling when too wide (spec).
            Text(
                "${track.artist.ifBlank { track.effectiveAlbumArtist }} – ${track.title}",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(),
                textAlign = TextAlign.Center,
            )
            // "album (year)" below (spec).
            Text(
                track.effectiveAlbum + (track.year?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )

            // Zoomable album art with configurable corner buttons; swipe
            // horizontally (unzoomed) to change track.
            ZoomableArt(
                track = track,
                cornerActions = npSettings.cornerActions,
                onCorner = { action ->
                    handleCornerAction(
                        action, track, viewModel, actions,
                        onOpenArtist = onOpenArtist,
                        onOpenAlbum = onOpenAlbum,
                    )
                },
                onSwipeNext = viewModel.player::next,
                onSwipePrevious = viewModel.player::previous,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(vertical = 12.dp),
            )

            // Configurable track info lines (Settings ▸ Now Playing).
            npSettings.trackInfoFields.forEach { field ->
                trackInfoLine(field, track)?.let { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SeekBar(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                showRemaining = showRemaining,
                onToggleElapsed = { showRemaining = !showRemaining },
                onSeek = viewModel.player::seekTo,
            )

            // Transport controls: shuffle · previous · play/pause · next · repeat.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShuffleButton(current = state.shuffleMode, onToggle = viewModel.player::toggleShuffle)
                IconButton(onClick = viewModel.player::previous) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "Previous",
                        modifier = Modifier.size(40.dp),
                    )
                }
                FilledIconButton(
                    onClick = viewModel.player::togglePlayPause,
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = viewModel.player::next) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        modifier = Modifier.size(40.dp),
                    )
                }
                RepeatButton(current = state.repeatMode, onCycle = viewModel.player::cycleRepeatMode)
            }
        }
    }

    // Slide-up queue drawer (spec): the same full component as the Queue view,
    // including clear, multi-select, bulk actions and swipe-to-remove.
    // ActionDialogHost below already serves the shared LibraryActionsViewModel.
    if (showQueueSheet) {
        ModalBottomSheet(onDismissRequest = { showQueueSheet = false }) {
            QueuePanel(onBack = null, hostActionDialogs = false)
        }
    }

    ActionDialogHost(actions)
}

// --------------------------------------------------------------------- pieces

/**
 * Pinch-to-zoom album art with up to four configurable corner buttons.
 * While unzoomed, a horizontal swipe skips to the next/previous track.
 */
@Composable
private fun ZoomableArt(
    track: Track,
    cornerActions: Map<Corner, CornerAction>,
    onCorner: (CornerAction) -> Unit,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(modifier) {
        TrackArt(
            trackPath = if (track.hasEmbeddedArt) track.filePath else null,
            cornerRadius = 16,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                )
                .pointerInput(track.filePath) {
                    // Hand-rolled transform detector: pinch zooms + pans like
                    // before, but an unzoomed horizontal drag past the
                    // threshold skips tracks (left = next, right = previous).
                    val swipeThresholdPx = 96.dp.toPx()
                    awaitEachGesture {
                        var swipeDragX = 0f
                        var swipeFired = false
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = newScale
                            if (newScale <= 1.01f) {
                                offsetX = 0f
                                offsetY = 0f
                                swipeDragX += pan.x
                                if (!swipeFired && kotlin.math.abs(swipeDragX) > swipeThresholdPx) {
                                    swipeFired = true
                                    if (swipeDragX < 0) onSwipeNext() else onSwipePrevious()
                                }
                            } else {
                                offsetX += pan.x
                                offsetY += pan.y
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
        )
        cornerActions.forEach { (corner, action) ->
            if (action != CornerAction.NONE) {
                val alignment = when (corner) {
                    Corner.TOP_LEFT -> Alignment.TopStart
                    Corner.TOP_RIGHT -> Alignment.TopEnd
                    Corner.BOTTOM_LEFT -> Alignment.BottomStart
                    Corner.BOTTOM_RIGHT -> Alignment.BottomEnd
                }
                IconButton(
                    onClick = { onCorner(action) },
                    modifier = Modifier.align(alignment),
                ) {
                    Icon(
                        cornerIcon(action),
                        contentDescription = cornerLabel(action),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    showRemaining: Boolean,
    onToggleElapsed: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    // Track the drag locally; only issue the seek when the finger lifts, so
    // scrubbing doesn't spam seeks or fight the position ticker.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val playedFraction = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shownFraction = dragFraction ?: playedFraction
    val shownPositionMs = (shownFraction * durationMs).toLong()

    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = shownFraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { onSeek((it * durationMs).toLong()) }
                dragFraction = null
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // Tapping elapsed flips to "-remaining" (spec).
            Text(
                if (showRemaining) {
                    TimeFormat.remaining(shownPositionMs, durationMs)
                } else {
                    TimeFormat.duration(shownPositionMs)
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .padding(4.dp)
                    .clickable(onClick = onToggleElapsed),
            )
            Text(TimeFormat.duration(durationMs), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Shuffle on/off toggle. The shuffle *flavor* (anti-repeat, rating bias) is
 * configured in Settings ▸ Shuffle, not here.
 */
@Composable
private fun ShuffleButton(current: ShuffleMode, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            Icons.Filled.Shuffle,
            contentDescription = if (current != ShuffleMode.OFF) "Shuffle off" else "Shuffle on",
            tint = if (current != ShuffleMode.OFF) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** Repeat button cycling OFF → ALL → ONE (spec). */
@Composable
private fun RepeatButton(current: RepeatMode, onCycle: () -> Unit) {
    IconButton(onClick = onCycle) {
        Icon(
            if (current == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            contentDescription = "Repeat mode: $current",
            tint = if (current != RepeatMode.OFF) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

// --------------------------------------------------------------------- helpers

private fun handleCornerAction(
    action: CornerAction,
    track: Track,
    viewModel: NowPlayingViewModel,
    actions: LibraryActionsViewModel,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (albumArtist: String, album: String) -> Unit,
) {
    when (action) {
        CornerAction.NONE -> Unit
        CornerAction.GO_TO_ARTIST ->
            onOpenArtist(track.artist.ifBlank { track.effectiveAlbumArtist })
        CornerAction.GO_TO_ALBUM ->
            onOpenAlbum(track.effectiveAlbumArtist, track.effectiveAlbum)
        CornerAction.ADD_TO_PLAYLIST -> actions.requestAddToPlaylist(LibraryItem.TrackItem(track))
        CornerAction.RATE_TRACK -> actions.requestRate(track)
        CornerAction.EDIT_TAGS -> actions.requestEditTags(LibraryItem.TrackItem(track))
        CornerAction.SET_AS_WALLPAPER -> viewModel.setCurrentArtAsWallpaper()
    }
}

fun cornerIcon(action: CornerAction): ImageVector = when (action) {
    CornerAction.GO_TO_ARTIST -> Icons.Filled.Person
    CornerAction.GO_TO_ALBUM -> Icons.Filled.Album
    CornerAction.ADD_TO_PLAYLIST -> Icons.AutoMirrored.Filled.PlaylistAdd
    CornerAction.RATE_TRACK -> Icons.Filled.Star
    CornerAction.EDIT_TAGS -> Icons.Filled.Edit
    CornerAction.SET_AS_WALLPAPER -> Icons.Filled.Wallpaper
    CornerAction.NONE -> Icons.Filled.Star
}

/** Human labels reused by the corner-button settings screen. */
fun cornerLabel(action: CornerAction): String = when (action) {
    CornerAction.NONE -> "Nothing"
    CornerAction.GO_TO_ARTIST -> "Go to artist"
    CornerAction.GO_TO_ALBUM -> "Go to album"
    CornerAction.ADD_TO_PLAYLIST -> "Add to playlist"
    CornerAction.RATE_TRACK -> "Rate track"
    CornerAction.EDIT_TAGS -> "Edit ID3 tags"
    CornerAction.SET_AS_WALLPAPER -> "Set art as wallpaper"
}

private fun trackInfoLine(field: TrackInfoField, track: Track): String? = when (field) {
    TrackInfoField.ARTIST -> track.artist.ifBlank { null }?.let { "Artist: $it" }
    TrackInfoField.ALBUM -> track.album.ifBlank { null }?.let { "Album: $it" }
    TrackInfoField.YEAR -> track.year?.let { "Year: $it" }
    TrackInfoField.GENRE -> track.genre.ifBlank { null }?.let { "Genre: $it" }
    TrackInfoField.FORMAT -> "Format: ${track.format.name}" +
        if (track.sampleRateHz > 0) " · ${track.sampleRateHz / 1000f} kHz" else ""
    TrackInfoField.BITRATE -> if (track.bitrateKbps > 0) "Bitrate: ${track.bitrateKbps} kbps" else null
    TrackInfoField.RATING -> if (track.rating > 0) "Rating: ${"★".repeat(track.rating)}" else null
    TrackInfoField.PLAY_COUNT -> "Plays: ${track.playCount}"
}
