package com.tempobox.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.tempobox.library.LibraryRepository
import com.tempobox.library.PlaylistRepository
import com.tempobox.model.Album
import com.tempobox.model.AlbumArtist
import com.tempobox.model.AudioFormat
import com.tempobox.model.Genre
import com.tempobox.model.Playlist
import com.tempobox.model.Track
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Robolectric: building MediaItem needs the Android framework. */
@RunWith(AndroidJUnit4::class)
class LibraryBrowseTreeTest {

    private val library = mockk<LibraryRepository>()
    private val playlists = mockk<PlaylistRepository>()
    private val tree = LibraryBrowseTree(library, playlists)

    private fun track(id: Long, title: String) = Track(
        id = id,
        filePath = "/music/$title.flac",
        title = title,
        artist = "Artist",
        albumArtist = "AC/DC",
        album = "Back in Black",
        genre = "Rock",
        year = 1980,
        durationMs = 1000,
        format = AudioFormat.FLAC,
        rating = 0,
        hasEmbeddedArt = true,
    )

    private val album = Album(
        name = "Back in Black",
        albumArtist = "AC/DC",
        year = 1980,
        trackCount = 2,
        durationMs = 2000,
        artworkTrackPath = "/music/a.flac",
        dateAddedMs = 0,
        dateModifiedMs = 0,
    )

    @Before
    fun setUp() {
        every { library.observeAlbums(albumArtist = null, artist = null, genre = null, sinceMs = null) } returns
            flowOf(listOf(album))
        every { library.observeAlbums(albumArtist = "AC/DC") } returns flowOf(listOf(album))
        every { library.observeAlbums(genre = "Rock") } returns flowOf(listOf(album))
        every { library.observeAlbumArtists() } returns
            flowOf(listOf(AlbumArtist(name = "AC/DC", albumCount = 1, trackCount = 2)))
        every { library.observeGenres() } returns
            flowOf(listOf(Genre(name = "Rock", trackCount = 2, albumCount = 1)))
        every {
            library.observeAlbumTracks(album = "Back in Black", albumArtist = "AC/DC", sort = any())
        } returns flowOf(listOf(track(1, "Hells Bells"), track(2, "Shoot to Thrill")))
        every { playlists.observePlaylists() } returns
            flowOf(listOf(Playlist(id = 7, name = "Road Trip")))
    }

    @Test
    fun `root offers the four Android Auto tabs`() = runTest {
        val children = tree.children(BrowseIds.ROOT)!!
        assertThat(children.map { it.mediaId }).containsExactly(
            BrowseIds.PLAYLISTS, BrowseIds.ARTISTS, BrowseIds.ALBUMS, BrowseIds.GENRES,
        ).inOrder()
        assertThat(children.all { it.mediaMetadata.isBrowsable == true }).isTrue()
    }

    @Test
    fun `albums list one browsable node per album with artwork`() = runTest {
        val children = tree.children(BrowseIds.ALBUMS)!!
        assertThat(children).hasSize(1)
        val item = children.single()
        assertThat(BrowseIds.decode(item.mediaId))
            .isEqualTo(BrowseIds.Node.Album("AC/DC", "Back in Black"))
        assertThat(item.mediaMetadata.isBrowsable).isTrue()
        assertThat(item.mediaMetadata.artworkUri).isNotNull()
    }

    @Test
    fun `an album's children are its tracks, playable, in album order`() = runTest {
        val albumId = BrowseIds.encode(BrowseIds.Node.Album("AC/DC", "Back in Black"))
        val children = tree.children(albumId)!!
        assertThat(children.map { it.mediaMetadata.title.toString() })
            .containsExactly("Hells Bells", "Shoot to Thrill").inOrder()
        children.forEach {
            assertThat(it.mediaMetadata.isPlayable).isTrue()
            val node = BrowseIds.decode(it.mediaId)
            assertThat(node).isInstanceOf(BrowseIds.Node.Track::class.java)
            assertThat((node as BrowseIds.Node.Track).parent)
                .isEqualTo(BrowseIds.Node.Album("AC/DC", "Back in Black"))
        }
    }

    @Test
    fun `an artist's children are their albums`() = runTest {
        val children = tree.children(BrowseIds.encode(BrowseIds.Node.Artist("AC/DC")))!!
        assertThat(children.map { BrowseIds.decode(it.mediaId) })
            .containsExactly(BrowseIds.Node.Album("AC/DC", "Back in Black"))
    }

    @Test
    fun `a genre's children are its albums`() = runTest {
        val children = tree.children(BrowseIds.encode(BrowseIds.Node.Genre("Rock")))!!
        assertThat(children.map { BrowseIds.decode(it.mediaId) })
            .containsExactly(BrowseIds.Node.Album("AC/DC", "Back in Black"))
    }

    @Test
    fun `playlists are listed and their tracks come back in stored order`() = runTest {
        val roadTrip = Playlist(id = 7, name = "Road Trip")
        coEvery { playlists.getPlaylist(7) } returns roadTrip
        coEvery { playlists.getPlaylistTracks(roadTrip) } returns
            listOf(track(2, "Shoot to Thrill"), track(1, "Hells Bells"))

        val lists = tree.children(BrowseIds.PLAYLISTS)!!
        assertThat(BrowseIds.decode(lists.single().mediaId)).isEqualTo(BrowseIds.Node.Playlist(7))

        val tracks = tree.tracksFor(BrowseIds.Node.Playlist(7))
        assertThat(tracks.map { it.title })
            .containsExactly("Shoot to Thrill", "Hells Bells").inOrder()
    }

    @Test
    fun `tracksFor a track node expands its parent context`() = runTest {
        val node = BrowseIds.Node.Track(
            trackId = 2,
            parent = BrowseIds.Node.Album("AC/DC", "Back in Black"),
        )
        val tracks = tree.tracksFor(node)
        assertThat(tracks.map { it.id }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `unknown parent ids yield null, not a crash`() = runTest {
        assertThat(tree.children("not-a-node")).isNull()
    }
}
