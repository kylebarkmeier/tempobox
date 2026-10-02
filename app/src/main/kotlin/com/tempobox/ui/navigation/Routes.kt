package com.tempobox.ui.navigation

import android.net.Uri
import com.tempobox.model.LibraryTab

/**
 * Navigation graph routes. Dynamic segments are Uri-encoded because artist,
 * album and genre names come straight from tags (slashes, spaces, emoji…).
 */
object Routes {
    const val LIBRARY = "library?tab={tab}"
    const val QUEUE = "queue"
    const val SETTINGS = "settings"
    const val SETTINGS_SECTION = "settings/{section}"
    const val ARTIST = "artist/{name}?by={by}"
    const val ALBUM = "album/{artist}/{album}"
    const val GENRE = "genre/{name}"
    const val PLAYLIST = "playlist/{id}"

    fun library(tab: LibraryTab? = null): String =
        if (tab != null) "library?tab=${tab.name}" else "library"

    fun settingsSection(section: String) = "settings/$section"

    /** Artist detail; [byAlbumArtist] false browses by track artist instead. */
    fun artist(name: String, byAlbumArtist: Boolean = true) =
        "artist/${Uri.encode(name)}?by=${if (byAlbumArtist) "album" else "track"}"
    fun album(albumArtist: String, album: String) =
        "album/${Uri.encode(albumArtist)}/${Uri.encode(album)}"
    fun genre(name: String) = "genre/${Uri.encode(name)}"
    fun playlist(id: Long) = "playlist/$id"
}
