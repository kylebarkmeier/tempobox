package com.tempobox.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowseIdsTest {

    @Test
    fun `fixed nodes round trip`() {
        listOf(
            BrowseIds.Node.Root,
            BrowseIds.Node.Playlists,
            BrowseIds.Node.Artists,
            BrowseIds.Node.Albums,
            BrowseIds.Node.Genres,
        ).forEach { node ->
            assertThat(BrowseIds.decode(BrowseIds.encode(node))).isEqualTo(node)
        }
    }

    @Test
    fun `names with the scheme's own separators round trip`() {
        val nasty = listOf(
            "AC/DC",
            "What? Is / This & That",
            "100% ?parent=track/9",
            "Sigur Rós – ágætis byrjun",
            "日本語のアルバム",
            "",
        )
        nasty.forEach { name ->
            val artist = BrowseIds.Node.Artist(name)
            assertThat(BrowseIds.decode(BrowseIds.encode(artist))).isEqualTo(artist)

            val album = BrowseIds.Node.Album(albumArtist = name, album = "a/b?c=$name")
            assertThat(BrowseIds.decode(BrowseIds.encode(album))).isEqualTo(album)

            val genre = BrowseIds.Node.Genre(name)
            assertThat(BrowseIds.decode(BrowseIds.encode(genre))).isEqualTo(genre)
        }
    }

    @Test
    fun `track ids carry their browse context`() {
        val parent = BrowseIds.Node.Album("AC/DC", "Back in Black")
        val node = BrowseIds.Node.Track(trackId = 42, parent = parent)
        val decoded = BrowseIds.decode(BrowseIds.encode(node))
        assertThat(decoded).isEqualTo(node)
    }

    @Test
    fun `playlist ids round trip`() {
        val node = BrowseIds.Node.Playlist(7)
        assertThat(BrowseIds.decode(BrowseIds.encode(node))).isEqualTo(node)
    }

    @Test
    fun `garbage decodes to null, never throws`() {
        listOf(
            "", "bogus", "track/", "track/x?parent=albums", "track/5",
            "track/5?parent=track/6", "album/only-one-part", "playlist/notanumber",
        ).forEach { id ->
            assertThat(BrowseIds.decode(id)).isNull()
        }
    }
}
