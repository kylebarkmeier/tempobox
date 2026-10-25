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

    /**
     * False when the item holds a track with no library row (network streams
     * share id 0). Such items cannot be rated, tagged, put in playlists,
     * removed, or deleted: "Add to playlist" would even break the
     * playlist_entries foreign key. The menu hides those entries on it.
     */
    val isLibraryBacked: Boolean
        get() = when (this) {
            is TrackItem -> track.id != 0L
            is TracksItem -> tracks.all { it.id != 0L }
            else -> true
        }

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
