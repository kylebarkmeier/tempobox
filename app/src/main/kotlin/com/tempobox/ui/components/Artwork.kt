package com.tempobox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import coil.compose.AsyncImage
import com.tempobox.artwork.ArtistImageRepository
import com.tempobox.artwork.TrackArtwork
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Album art from a track file's embedded tag (Coil + TrackArtworkFetcher),
 * with a placeholder icon when the file has none.
 */
@Composable
fun TrackArt(
    trackPath: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 8,
    placeholder: ImageVector = Icons.Filled.MusicNote,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (trackPath != null) {
            AsyncImage(
                model = TrackArtwork(trackPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Placeholder stays behind the image; visible when art is absent/loading.
        Icon(
            imageVector = placeholder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxSize(0.5f),
        )
    }
}

/**
 * 2×2 collage of album covers — the artist-card fallback image, also the
 * genre artwork. [placeholder] is the icon for a collection with no art at
 * all (person for artists, note for genres).
 */
@Composable
fun CollageArt(
    trackPaths: List<String>,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 12,
    placeholder: ImageVector = Icons.Filled.Person,
) {
    Box(modifier = modifier.clip(RoundedCornerShape(cornerRadius.dp))) {
        when {
            trackPaths.isEmpty() -> TrackArt(
                trackPath = null,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = cornerRadius,
                placeholder = placeholder,
            )
            trackPaths.size < 4 -> TrackArt(
                trackPath = trackPaths.first(),
                modifier = Modifier.fillMaxSize(),
                cornerRadius = cornerRadius,
                placeholder = Icons.Filled.Album,
            )
            else -> androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                for (row in 0..1) {
                    androidx.compose.foundation.layout.Row(
                        Modifier
                            .weight(1f)
                            .fillMaxSize(),
                    ) {
                        for (col in 0..1) {
                            TrackArt(
                                trackPath = trackPaths[row * 2 + col],
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f),
                                cornerRadius = 0,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Lightweight VM that memoizes Discogs artist image lookups for cards. */
@HiltViewModel
class ArtistImageViewModel @Inject constructor(
    private val repository: ArtistImageRepository,
) : ViewModel() {
    suspend fun imageUrl(name: String): String? = repository.imageUrl(name)
}

/**
 * Artist card image: Discogs photo when available, album-art collage
 * otherwise (Settings ▸ UI ▸ Artist images controls the preference).
 */
@Composable
fun ArtistImage(
    artistName: String,
    collageTrackPaths: List<String>,
    preferRemoteImage: Boolean,
    modifier: Modifier = Modifier,
) {
    val viewModel: ArtistImageViewModel = hiltViewModel()
    val remoteUrl by produceState<String?>(initialValue = null, artistName, preferRemoteImage) {
        value = if (preferRemoteImage) viewModel.imageUrl(artistName) else null
    }
    if (remoteUrl != null) {
        AsyncImage(
            model = remoteUrl,
            contentDescription = artistName,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(12.dp)),
        )
    } else {
        CollageArt(trackPaths = collageTrackPaths, modifier = modifier)
    }
}
