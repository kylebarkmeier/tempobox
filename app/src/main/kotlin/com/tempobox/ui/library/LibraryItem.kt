package com.tempobox.ui.library

import com.tempobox.model.Album
import com.tempobox.model.AlbumArtist
import com.tempobox.model.Genre
import com.tempobox.model.Playlist
import com.tempobox.model.Track

/**
 * Anything a library list can show. The shared action layer
 * ([LibraryActionsViewModel]) resolves any item to its tracks, which is what
 * lets every view reuse the same play buttons, 3-dot menus, and swipe actions
 * without duplication.
 */
sealed interface LibraryItem {
    /** Display title (also used in confirmation dialogs). */
    val title: String

    /** True when the item can expand to more than one track. */
    val isCollection: Boolean

    data class TrackItem(val track: Track) : LibraryItem {
        override val title: String get() = track.title
        override val isCollection: Boolean get() = false
    }

    data class AlbumItem(val album: Album) : LibraryItem {
        override val title: String get() = album.name
        override val isCollection: Boolean get() = true
    }

    /** [byAlbumArtist] false = a track-artist row (the Artists tab). */
    data class ArtistItem(
        val artist: AlbumArtist,
        val byAlbumArtist: Boolean = true,
    ) : LibraryItem {
        override val title: String get() = artist.name
        override val isCollection: Boolean get() = true
    }

    data class GenreItem(val genre: Genre) : LibraryItem {
        override val title: String get() = genre.name
        override val isCollection: Boolean get() = true
    }

    data class PlaylistItem(val playlist: Playlist) : LibraryItem {
        override val title: String get() = playlist.name
        override val isCollection: Boolean get() = true
    }

    /** An ad-hoc set of tracks (e.g. a queue multi-selection). */
    data class TracksItem(val label: String, val tracks: List<Track>) : LibraryItem {
        override val title: String get() = label
        override val isCollection: Boolean get() = tracks.size > 1
    }
}
