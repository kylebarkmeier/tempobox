package com.tempobox.playback

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

class VoiceSearchTest {

    private val library = mockk<LibraryRepository>()
    private val playlists = mockk<PlaylistRepository>()
    private val voiceSearch = VoiceSearch(library, playlists)

    private fun track(id: Long, title: String) = Track(
        id = id,
        filePath = "/music/$title.flac",
        title = title,
        artist = "Allicorn",
        albumArtist = "Allicorn",
        album = "First Flight",
        genre = "Trance",
        year = null,
        durationMs = 1000,
        format = AudioFormat.FLAC,
        rating = 0,
        hasEmbeddedArt = false,
    )

    private val allicornTracks = listOf(track(1, "Skyward"), track(2, "Thunderstruck"))

    @Before
    fun setUp() {
        coEvery { playlists.getPlaylists() } returns listOf(Playlist(id = 5, name = "Road Trip"))
        every { library.observeAlbumArtists() } returns flowOf(
            listOf(
                AlbumArtist(name = "Allicorn", albumCount = 1, trackCount = 2),
                AlbumArtist(name = "Sigur Rós", albumCount = 1, trackCount = 1),
            ),
        )
        every { library.observeAlbums() } returns flowOf(
            listOf(
                Album(
                    name = "Back in Black", albumArtist = "AC/DC", year = 1980,
                    trackCount = 1, durationMs = 1, artworkTrackPath = null,
                    dateAddedMs = 0, dateModifiedMs = 0,
                ),
            ),
        )
        every { library.observeGenres() } returns flowOf(
            listOf(Genre(name = "Trance", trackCount = 2, albumCount = 1)),
        )
        every { library.observeTracks() } returns flowOf(allicornTracks)
        every { library.observeArtistTracks("Allicorn") } returns flowOf(allicornTracks)
        every { library.observeArtistTracks("Sigur Rós") } returns flowOf(listOf(track(3, "Svefn")))
        every { library.observeAlbumTracks("Back in Black", "AC/DC") } returns
            flowOf(listOf(track(4, "Hells Bells")))
        every { library.observeGenreTracks("Trance") } returns flowOf(allicornTracks)
    }

    @Test
    fun `artist matches exactly, case-insensitively`() = runTest {
        val resolution = voiceSearch.resolve("allicorn")!!
        assertThat(resolution.description).isEqualTo("artist: Allicorn")
        assertThat(resolution.tracks).hasSize(2)
        assertThat(resolution.shuffle).isFalse()
    }

    @Test
    fun `accents do not matter`() = runTest {
        val resolution = voiceSearch.resolve("sigur ros")!!
        assertThat(resolution.description).isEqualTo("artist: Sigur Rós")
    }

    @Test
    fun `shuffle keyword sets the flag and leaves the query usable`() = runTest {
        val resolution = voiceSearch.resolve("shuffle Allicorn")!!
        assertThat(resolution.shuffle).isTrue()
        assertThat(resolution.description).isEqualTo("artist: Allicorn")
    }

    @Test
    fun `a leading play is stripped`() = runTest {
        coEvery { playlists.getPlaylistTracks(any()) } returns listOf(track(9, "x"))
        val resolution = voiceSearch.resolve("play Road Trip")!!
        assertThat(resolution.description).isEqualTo("playlist: Road Trip")
    }

    @Test
    fun `substring match finds the album when nothing matches exactly`() = runTest {
        val resolution = voiceSearch.resolve("black")!!
        assertThat(resolution.description).isEqualTo("album: Back in Black")
    }

    @Test
    fun `track titles are the last resort`() = runTest {
        val resolution = voiceSearch.resolve("thunderstruck")!!
        assertThat(resolution.description).isEqualTo("track: Thunderstruck")
        assertThat(resolution.tracks.map { it.id }).containsExactly(2L)
    }

    @Test
    fun `blank and unmatched queries resolve to null`() = runTest {
        assertThat(voiceSearch.resolve("")).isNull()
        assertThat(voiceSearch.resolve("   shuffle  ")).isNull()
        assertThat(voiceSearch.resolve("completely unknown thing")).isNull()
    }

    @Test
    fun `playlist tracks come back in stored order`() = runTest {
        val roadTrip = Playlist(id = 5, name = "Road Trip")
        coEvery { playlists.getPlaylistTracks(roadTrip) } returns
            listOf(track(2, "Thunderstruck"), track(1, "Skyward"))
        val resolution = voiceSearch.resolve("road trip")!!
        assertThat(resolution.tracks.map { it.id }).containsExactly(2L, 1L).inOrder()
    }
}
