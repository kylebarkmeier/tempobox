package com.tempobox.playback

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Stable media-id scheme for the Android Auto / media-browser tree.
 *
 * Browsable ids name a node ("albums", "album/<artist>/<album>"); playable
 * ids name a track plus the node it was browsed under
 * ("track/<trackId>?parent=album/<artist>/<album>"), so that tapping one track
 * can queue its whole context with the right start index. Names are
 * URL-encoded because artists and albums can contain any character, including
 * the '/' and '?' this scheme uses as separators.
 */
object BrowseIds {

    const val ROOT = "root"
    const val PLAYLISTS = "playlists"
    const val ARTISTS = "artists"
    const val ALBUMS = "albums"
    const val GENRES = "genres"

    private const val TRACK_PREFIX = "track/"
    private const val PARENT_SEP = "?parent="

    sealed interface Node {
        data object Root : Node
        data object Playlists : Node
        data object Artists : Node
        data object Albums : Node
        data object Genres : Node
        data class Artist(val name: String) : Node
        data class Album(val albumArtist: String, val album: String) : Node
        data class Genre(val name: String) : Node
        data class Playlist(val id: Long) : Node
        data class Track(val trackId: Long, val parent: Node) : Node
    }

    fun encode(node: Node): String = when (node) {
        Node.Root -> ROOT
        Node.Playlists -> PLAYLISTS
        Node.Artists -> ARTISTS
        Node.Albums -> ALBUMS
        Node.Genres -> GENRES
        is Node.Artist -> "artist/${enc(node.name)}"
        is Node.Album -> "album/${enc(node.albumArtist)}/${enc(node.album)}"
        is Node.Genre -> "genre/${enc(node.name)}"
        is Node.Playlist -> "playlist/${node.id}"
        is Node.Track -> "$TRACK_PREFIX${node.trackId}$PARENT_SEP${encode(node.parent)}"
    }

    fun decode(id: String): Node? {
        if (id.startsWith(TRACK_PREFIX)) {
            val rest = id.removePrefix(TRACK_PREFIX)
            val sep = rest.indexOf(PARENT_SEP)
            if (sep < 0) return null
            val trackId = rest.take(sep).toLongOrNull() ?: return null
            val parent = decode(rest.substring(sep + PARENT_SEP.length)) ?: return null
            return Node.Track(trackId, parent)
        }
        return when {
            id == ROOT -> Node.Root
            id == PLAYLISTS -> Node.Playlists
            id == ARTISTS -> Node.Artists
            id == ALBUMS -> Node.Albums
            id == GENRES -> Node.Genres
            id.startsWith("artist/") -> Node.Artist(dec(id.removePrefix("artist/")))
            id.startsWith("genre/") -> Node.Genre(dec(id.removePrefix("genre/")))
            id.startsWith("playlist/") ->
                id.removePrefix("playlist/").toLongOrNull()?.let { Node.Playlist(it) }
            id.startsWith("album/") -> {
                val parts = id.removePrefix("album/").split('/')
                if (parts.size != 2) null else Node.Album(dec(parts[0]), dec(parts[1]))
            }
            else -> null
        }
    }

    // java.net URL coding is JVM-pure (unit-testable) and encodes '/' and '?'.
    private fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name())
    private fun dec(s: String): String = URLDecoder.decode(s, Charsets.UTF_8.name())
}
