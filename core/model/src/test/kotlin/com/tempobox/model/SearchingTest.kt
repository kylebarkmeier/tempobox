package com.tempobox.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchingTest {

    private fun track(id: Long, title: String, artist: String = "", album: String = "") = Track(
        id = id, filePath = "/m/$id.mp3", title = title, artist = artist, album = album,
    )

    private fun album(name: String, albumArtist: String = "") = Album(
        name = name, albumArtist = albumArtist, year = null, trackCount = 1,
        durationMs = 0, artworkTrackPath = null, dateAddedMs = 0, dateModifiedMs = 0,
    )

    // ------------------------------------------------------------- empty query

    @Test
    fun `blank query returns the list unchanged`() {
        val tracks = listOf(track(1, "Alpha"), track(2, "Bravo"))
        assertThat(tracks.filterTracks("")).isEqualTo(tracks)
        assertThat(tracks.filterTracks("   ")).isEqualTo(tracks)
    }

    // ----------------------------------------------------------------- casing

    @Test
    fun `matching is case-insensitive both ways`() {
        val tracks = listOf(track(1, "Paranoid Android"), track(2, "Karma Police"))
        assertThat(tracks.filterTracks("PARANOID").map { it.title }).containsExactly("Paranoid Android")
        assertThat(tracks.filterTracks("karma").map { it.title }).containsExactly("Karma Police")
    }

    // ------------------------------------------------------------- diacritics

    @Test
    fun `plain query matches accented fields`() {
        val tracks = listOf(track(1, "Beyoncé Interlude"), track(2, "Señorita"))
        assertThat(tracks.filterTracks("beyonce").map { it.id }).containsExactly(1L)
        assertThat(tracks.filterTracks("senorita").map { it.id }).containsExactly(2L)
    }

    @Test
    fun `accented query matches plain fields`() {
        val tracks = listOf(track(1, "Cafe del Mar"))
        assertThat(tracks.filterTracks("café").map { it.id }).containsExactly(1L)
    }

    // ---------------------------------------------------------- field coverage

    @Test
    fun `tracks match on title, artist, and album`() {
        val tracks = listOf(
            track(1, "One", artist = "India Artist", album = "Debut"),
            track(2, "Two", artist = "Other", album = "Juliet Album"),
        )
        assertThat(tracks.filterTracks("one").map { it.id }).containsExactly(1L)
        assertThat(tracks.filterTracks("india").map { it.id }).containsExactly(1L)
        assertThat(tracks.filterTracks("juliet").map { it.id }).containsExactly(2L)
    }

    @Test
    fun `albums match on name and album artist`() {
        val albums = listOf(album("OK Computer", "Radiohead"), album("Blue", "Joni Mitchell"))
        assertThat(albums.filterAlbums("computer").map { it.name }).containsExactly("OK Computer")
        assertThat(albums.filterAlbums("joni").map { it.name }).containsExactly("Blue")
    }

    @Test
    fun `artists, genres, and playlists match on name`() {
        val artists = listOf(
            AlbumArtist(name = "Sigur Rós", albumCount = 1, trackCount = 1),
            AlbumArtist(name = "Low", albumCount = 1, trackCount = 1),
        )
        assertThat(artists.filterArtists("sigur ros").map { it.name }).containsExactly("Sigur Rós")

        val genres = listOf(Genre("Post-Rock", 1, 1), Genre("Jazz", 1, 1))
        assertThat(genres.filterGenres("post").map { it.name }).containsExactly("Post-Rock")

        val playlists = listOf(Playlist(id = 1, name = "Morning Mix"), Playlist(id = 2, name = "Gym"))
        assertThat(playlists.filterPlaylists("morning").map { it.name }).containsExactly("Morning Mix")
    }

    @Test
    fun `queue entries match on their track fields`() {
        val queue = listOf(
            QueueItem(uid = 10, track = track(1, "One", artist = "India Artist")),
            QueueItem(uid = 11, track = track(2, "Two", artist = "Juliet Artist")),
        )
        assertThat(queue.filterQueueItems("juliet").map { it.uid }).containsExactly(11L)
    }

    // ----------------------------------------------------------------- misc

    @Test
    fun `no match yields an empty list`() {
        val tracks = listOf(track(1, "Alpha"), track(2, "Bravo"))
        assertThat(tracks.filterTracks("zzz")).isEmpty()
    }

    @Test
    fun `query whitespace is trimmed before matching`() {
        val tracks = listOf(track(1, "Alpha"))
        assertThat(tracks.filterTracks("  alpha  ").map { it.id }).containsExactly(1L)
    }

    @Test
    fun `filtering preserves the incoming sort order`() {
        val sorted = SortSpec(SortKey.ALPHABETICAL, ascending = false).sortTracks(
            listOf(
                track(1, "Arena Daydream"),
                track(2, "Daydream Nation"),
                track(3, "Zebra"),
                track(4, "Daydreaming"),
            ),
        )
        val filtered = sorted.filterTracks("daydream")
        assertThat(filtered.map { it.title })
            .containsExactly("Daydreaming", "Daydream Nation", "Arena Daydream")
            .inOrder()
    }

    @Test
    fun `searchMatches exposes the same predicate for single items`() {
        assertThat(searchMatches("nação", "Nacao Zumbi")).isTrue()
        assertThat(searchMatches("", "anything")).isTrue()
        assertThat(searchMatches("x", "abc", "def")).isFalse()
    }
}
