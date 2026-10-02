package com.tempobox.ui.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tempobox.common.TimeFormat
import com.tempobox.model.NowPlayingState
import com.tempobox.playback.PlayerConnection
import com.tempobox.ui.components.TrackArt

/**
 * Bottom pill shown whenever a track is loaded (product spec): album art,
 * artist, track, progress with elapsed (left) / total (right), and previous /
 * play-pause / next. It is the collapsed state of the Now Playing sheet:
 * tapping or dragging it up expands the full view ([NowPlayingSheet]).
 */
@Composable
fun MiniPlayer(
    state: NowPlayingState,
    player: PlayerConnection,
    onOpen: () -> Unit,
) {
    val track = state.track ?: return

    Surface(tonalElevation = 4.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                // Surface color extends under the gesture bar; content doesn't.
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackArt(
                    trackPath = if (track.hasEmbeddedArt) track.filePath else null,
                    modifier = Modifier.size(44.dp),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                ) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.artist.ifBlank { track.effectiveAlbumArtist },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = player::previous) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous track")
                }
                IconButton(onClick = player::togglePlayPause) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(onClick = player::next) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next track")
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    TimeFormat.duration(state.positionMs),
                    style = MaterialTheme.typography.labelSmall,
                )
                LinearProgressIndicator(
                    progress = {
                        if (state.durationMs > 0) {
                            (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                Text(
                    TimeFormat.duration(state.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
