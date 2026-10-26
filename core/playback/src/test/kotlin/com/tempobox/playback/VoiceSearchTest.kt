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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class VoiceSearchTest {

    private val library = mockk<LibraryRepository>()
    private val playlists = mockk<PlaylistRepository>()
    private val voiceSearch = VoiceSearch(library, playlists, Dispatchers.Unconfined)

    private fun track(id: Long, title: String, artist: String = "Allicorn", album: String = "First Flight") = Track(
        id = id,
        filePath = "/music/$title.flac",
        title = title,
        artist = artist,
        albumArtist = artist,
        album = album,
        genre = "Trance",
        year = null,
        durationMs = 1000,
        format = AudioFormat.FLAC,
        rating = 0,
        hasEmbeddedArt = false,
    )

    private fun album(name: String, artist: String) = Album(
        name = name, albumArtist = artist, year = 1980,
        trackCount = 1, durationMs = 1, artworkTrackPath = null,
        dateAddedMs = 0, dateModifiedMs = 0,
    )

    private val allicornTracks = listOf(track(1, "Skyward"), track(2, "Thunderstruck"))
    private val guestTrack = track(5, "Duet", artist = "Guest Singer", album = "Back in Black")
    private val roadTrip = Playlist(id = 5, name = "Road Trip")

    private var tracks = allicornTracks + guestTrack
    private var albums = listOf(album("Back in Black", "AC/DC"))
    private var albumArtists = listOf(
        AlbumArtist(name = "Allicorn", albumCount = 1, trackCount = 2),
        AlbumArtist(name = "Sigur Rós", albumCount = 1, trackCount = 1),
    )

    @Before
    fun setUp() {
        coEvery { playlists.getPlaylists() } returns listOf(roadTrip)
        coEvery { playlists.getPlaylistTracks(roadTrip) } returns listOf(track(9, "x"))
        every { library.observeAlbumArtists() } answers { flowOf(albumArtists) }
        every { library.observeAlbums() } answers { flowOf(albums) }
        every { library.observeGenres() } returns flowOf(
            listOf(Genre(name = "Trance", trackCount = 2, albumCount = 1)),
        )
        // Only a track artist: a featured singer on someone else's album.
        coEvery { library.getTrackArtistNames() } returns listOf("Allicorn", "Guest Singer")
        every { library.observeTrackArtistTracks("Guest Singer") } returns flowOf(listOf(guestTrack))
        every { library.observeTracks() } answers { flowOf(tracks) }
        coEvery { library.getTracks() } answers { tracks }
        every { library.observeArtistTracks("Allicorn") } returns flowOf(allicornTracks)
        every { library.observeArtistTracks("Sigur Rós") } returns flowOf(listOf(track(3, "Svefn")))
        every { library.observeArtistTracks("The Album Leaf") } returns flowOf(listOf(track(7, "Window")))
        every { library.observeAlbumTracks("Back in Black", "AC/DC") } returns
            flowOf(listOf(track(4, "Hells Bells", artist = "AC/DC", album = "Back in Black")))
        every { library.observeAlbumTracks("Songs from the Big Chair", "Tears for Fears") } returns
            flowOf(listOf(track(8, "Shout", artist = "Tears for Fears")))
        every { library.observeGenreTracks("Trance") } returns flowOf(allicornTracks)
    }

    // ------------------------------------------------------------------ buckets

    @Test
    fun `artist matches exactly, case-insensitively`() = runTest {
        val resolution = voiceSearch.resolve("allicorn")!!
        assertThat(resolution.description).isEqualTo("artist: Allicorn")
        assertThat(resolution.tracks).hasSize(2)
        assertThat(resolution.shuffle).isFalse()
    }

    @Test
    fun `accents do not matter`() = runTest {
        assertThat(voiceSearch.resolve("sigur ros")!!.description).isEqualTo("artist: Sigur Rós")
    }

    @Test
    fun `shuffle keyword sets the flag and leaves the query usable`() = runTest {
        val resolution = voiceSearch.resolve("shuffle Allicorn")!!
        assertThat(resolution.shuffle).isTrue()
        assertThat(resolution.description).isEqualTo("artist: Allicorn")
    }

    @Test
    fun `a leading play is stripped`() = runTest {
        assertThat(voiceSearch.resolve("play Road Trip")!!.description).isEqualTo("playlist: Road Trip")
    }

    @Test
    fun `whole-word match finds the album when nothing matches exactly`() = runTest {
        assertThat(voiceSearch.resolve("black")!!.description).isEqualTo("album: Back in Black")
    }

    @Test
    fun `a whole-word match does not fire inside a longer word`() = runTest {
        // "lack" is inside "Black" but is not a word of it.
        assertThat(voiceSearch.resolve("lack")).isNull()
        tracks = tracks + track(10, "22") + track(11, "Song 2")
        assertThat(voiceSearch.resolve("2")!!.description).isEqualTo("track: Song 2 by Allicorn")
    }

    @Test
    fun `track titles are the last resort`() = runTest {
        val resolution = voiceSearch.resolve("thunderstruck")!!
        assertThat(resolution.description).isEqualTo("track: Thunderstruck by Allicorn")
        assertThat(resolution.tracks.map { it.id }).containsExactly(2L)
    }

    @Test
    fun `track artists are found when no album artist matches`() = runTest {
        val resolution = voiceSearch.resolve("guest singer")!!
        assertThat(resolution.description).isEqualTo("artist: Guest Singer")
        assertThat(resolution.tracks.map { it.id }).containsExactly(5L)
    }

    @Test
    fun `blank and unmatched queries resolve to null`() = runTest {
        assertThat(voiceSearch.resolve("")).isNull()
        assertThat(voiceSearch.resolve("   shuffle  ")).isNull()
        assertThat(voiceSearch.resolve("play")).isNull()
        assertThat(voiceSearch.resolve("completely unknown thing")).isNull()
    }

    @Test
    fun `playlist tracks come back in stored order`() = runTest {
        coEvery { playlists.getPlaylistTracks(roadTrip) } returns
            listOf(track(2, "Thunderstruck"), track(1, "Skyward"))
        val resolution = voiceSearch.resolve("road trip")!!
        assertThat(resolution.tracks.map { it.id }).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun `symbols in names match as spoken`() = runTest {
        albumArtists = albumArtists + AlbumArtist(name = "Simon & Garfunkel", albumCount = 1, trackCount = 1)
        every { library.observeArtistTracks("Simon & Garfunkel") } returns flowOf(listOf(track(12, "Cecilia")))
        assertThat(voiceSearch.resolve("Simon & Garfunkel")!!.description).isEqualTo("artist: Simon & Garfunkel")
    }

    @Test
    fun `punctuation in a tag or in the transcription does not matter`() = runTest {
        tracks = tracks + track(20, "Don’t Stop Believin’", artist = "Journey") +
            track(21, "Mr. Brightside", artist = "The Killers")
        assertThat(voiceSearch.resolve("don't stop believin")!!.description)
            .isEqualTo("track: Don’t Stop Believin’ by Journey")
        assertThat(voiceSearch.resolve("mr brightside")!!.description)
            .isEqualTo("track: Mr. Brightside by The Killers")
        // The recognizer writes "ACDC"; the artist filter folds the tag the same way.
        assertThat(voiceSearch.resolve("back in black by acdc")!!.description).isEqualTo("album: Back in Black")
        // A session-path query may arrive with sentence punctuation attached.
        assertThat(voiceSearch.resolve("back in black.")!!.description).isEqualTo("album: Back in Black")
    }

    @Test
    fun `a lone article or filler word never matches loosely`() = runTest {
        coEvery { playlists.getPlaylists() } returns listOf(roadTrip, Playlist(id = 8, name = "My Favorites"))
        tracks = tracks + track(16, "Stand By Me", artist = "Ben E. King") + track(18, "The End", artist = "The Doors")
        assertThat(voiceSearch.resolve("my")).isNull()
        assertThat(voiceSearch.resolve("me")).isNull()
        assertThat(voiceSearch.resolve("the")).isNull()
        // An exact name that is such a word is still fine.
        tracks = tracks + track(17, "Me", artist = "Allicorn")
        assertThat(voiceSearch.resolve("me")!!.description).isEqualTo("track: Me by Allicorn")
    }

    // ------------------------------------------------------------------ typed requests

    @Test
    fun `a spoken type limits the search to that bucket first`() = runTest {
        // A playlist shares the artist's name: the bare name picks the playlist
        // (bucket order), the type word picks the artist.
        val allicornList = Playlist(id = 6, name = "Allicorn")
        coEvery { playlists.getPlaylists() } returns listOf(roadTrip, allicornList)
        coEvery { playlists.getPlaylistTracks(allicornList) } returns listOf(track(9, "x"))
        assertThat(voiceSearch.resolve("allicorn")!!.description).isEqualTo("playlist: Allicorn")
        assertThat(voiceSearch.resolve("the artist allicorn")!!.description).isEqualTo("artist: Allicorn")
        assertThat(voiceSearch.resolve("songs by allicorn")!!.description).isEqualTo("artist: Allicorn")
        assertThat(voiceSearch.resolve("the album back in black")!!.description)
            .isEqualTo("album: Back in Black")
        assertThat(voiceSearch.resolve("the song thunderstruck")!!.description)
            .isEqualTo("track: Thunderstruck by Allicorn")
        assertThat(voiceSearch.resolve("genre trance")!!.description).isEqualTo("genre: Trance")
    }

    @Test
    fun `a spoken type that misses falls back to the words as spoken`() = runTest {
        // Allicorn is an artist, not a playlist: the fallback still finds it.
        assertThat(voiceSearch.resolve("playlist allicorn")!!.description).isEqualTo("artist: Allicorn")
        albumArtists = albumArtists + AlbumArtist(name = "The Album Leaf", albumCount = 1, trackCount = 1)
        assertThat(voiceSearch.resolve("play the album leaf")!!.description).isEqualTo("artist: The Album Leaf")
        albums = albums + album("Songs from the Big Chair", "Tears for Fears")
        assertThat(voiceSearch.resolve("songs from the big chair")!!.description)
            .isEqualTo("album: Songs from the Big Chair")
    }

    @Test
    fun `a trailing playlist word selects the playlist bucket`() = runTest {
        assertThat(voiceSearch.resolve("my road trip playlist")!!.description)
            .isEqualTo("playlist: Road Trip")
    }

    @Test
    fun `filler and the app name are ignored but a name starting with filler still resolves`() = runTest {
        assertThat(voiceSearch.resolve("play some allicorn on tempobox")!!.description)
            .isEqualTo("artist: Allicorn")
        assertThat(voiceSearch.resolve("shuffle songs by allicorn on tempo box")!!.let {
            it.description to it.shuffle
        }).isEqualTo("artist: Allicorn" to true)
        albums = albums + album("Some Girls", "The Rolling Stones")
        every { library.observeAlbumTracks("Some Girls", "The Rolling Stones") } returns
            flowOf(listOf(track(13, "Miss You", artist = "The Rolling Stones")))
        assertThat(voiceSearch.resolve("play some girls")!!.description).isEqualTo("album: Some Girls")
    }

    @Test
    fun `whole-library phrases resolve to every track`() = runTest {
        val resolution = voiceSearch.resolve("shuffle everything")!!
        assertThat(resolution.shuffle).isTrue()
        assertThat(resolution.tracks).hasSize(3)
        assertThat(resolution.description).isEqualTo("whole library (3 tracks)")
        assertThat(voiceSearch.resolve("play my music")!!.shuffle).isFalse()
        assertThat(voiceSearch.resolve("all my songs")).isNotNull()
    }

    @Test
    fun `vague requests take the whole library shuffled, filler included`() = runTest {
        listOf("play something", "play some music", "play me some music", "play me something", "music").forEach {
            val resolution = voiceSearch.resolve(it)!!
            assertThat(resolution.description).isEqualTo("whole library (3 tracks)")
            assertThat(resolution.shuffle).isTrue()
        }
    }

    @Test
    fun `a collection literally named like a library phrase wins by its exact name`() = runTest {
        val myMusic = Playlist(id = 6, name = "My Music")
        val mix = Playlist(id = 7, name = "My Music Mix")
        coEvery { playlists.getPlaylists() } returns listOf(roadTrip, mix, myMusic)
        coEvery { playlists.getPlaylistTracks(myMusic) } returns listOf(track(9, "x"))
        coEvery { playlists.getPlaylistTracks(mix) } returns listOf(track(10, "y"))
        assertThat(voiceSearch.resolve("play my music")!!.description).isEqualTo("playlist: My Music")
        // Only an exact playlist or artist name: a whole-word match ("My
        // Music Mix") or a track titled "Everything" does not hijack the phrase.
        coEvery { playlists.getPlaylists() } returns listOf(roadTrip, mix)
        tracks = tracks + track(11, "Everything")
        assertThat(voiceSearch.resolve("play my music")!!.description).isEqualTo("whole library (4 tracks)")
        assertThat(voiceSearch.resolve("everything")!!.description).isEqualTo("whole library (4 tracks)")
    }

    @Test
    fun `X by Y limits the title to that artist`() = runTest {
        val resolution = voiceSearch.resolve("thunderstruck by allicorn")!!
        assertThat(resolution.description).isEqualTo("track: Thunderstruck by Allicorn")
        // The album Back in Black is by AC/DC; asked for by a different
        // artist it must not match, and neither does anything else.
        assertThat(voiceSearch.resolve("back in black by allicorn")).isNull()
        assertThat(voiceSearch.resolve("back in black by ac/dc")!!.description)
            .isEqualTo("album: Back in Black")
    }

    @Test
    fun `a spoken type survives a by clause`() = runTest {
        tracks = tracks + track(14, "Back in Black", artist = "AC/DC", album = "Back in Black")
        assertThat(voiceSearch.resolve("the song back in black by ac/dc")!!.description)
            .isEqualTo("track: Back in Black by AC/DC")
        assertThat(voiceSearch.resolve("the album back in black by ac/dc")!!.description)
            .isEqualTo("album: Back in Black")
    }

    @Test
    fun `an exact title beats a loose by-split reading`() = runTest {
        // "stand by me" could read as title "stand" by artist "me"; the decoy
        // would satisfy that reading loosely, but the exact title wins.
        tracks = tracks + track(15, "The Last Stand", artist = "Megadeth") + track(16, "Stand By Me", artist = "Ben E. King")
        assertThat(voiceSearch.resolve("stand by me")!!.description).isEqualTo("track: Stand By Me by Ben E. King")
    }

    @Test
    fun `each bucket loads at most once per query and only when reached`() = runTest {
        voiceSearch.resolve("road trip")
        verify(exactly = 0) { library.observeAlbumArtists() }
        coVerify(exactly = 0) { library.getTracks() }
        coVerify(exactly = 1) { playlists.getPlaylists() }

        voiceSearch.resolve("stand by me")
        // Several readings and two match passes, one load of the track table.
        coVerify(exactly = 1) { library.getTracks() }
        // The whole library in order is the only reading that pays for the sort.
        verify(exactly = 0) { library.observeTracks() }
        voiceSearch.resolve("everything")
        verify(exactly = 1) { library.observeTracks() }
    }

    @Test
    fun `parseQuery exposes what was peeled off and keeps the plain words`() {
        assertThat(VoiceSearch.parseQuery("play the artist Lamb of God on TempoBox"))
            .isEqualTo(
                VoiceSearch.Query(
                    "Lamb of God", shuffle = false, scope = VoiceSearch.Scope.ARTIST,
                    plain = "the artist Lamb of God",
                ),
            )
        assertThat(VoiceSearch.parseQuery("shuffle my Road Trip playlist"))
            .isEqualTo(
                VoiceSearch.Query(
                    "Road Trip", shuffle = true, scope = VoiceSearch.Scope.PLAYLIST,
                    plain = "my Road Trip playlist",
                ),
            )
        assertThat(VoiceSearch.parseQuery("AC/DC"))
            .isEqualTo(VoiceSearch.Query("AC/DC", shuffle = false, scope = VoiceSearch.Scope.ANY))
        assertThat(VoiceSearch.parseQuery("everything").scope).isEqualTo(VoiceSearch.Scope.LIBRARY)
        assertThat(VoiceSearch.parseQuery("play me something"))
            .isEqualTo(VoiceSearch.Query("", shuffle = true, scope = VoiceSearch.Scope.LIBRARY, plain = "me something"))
        assertThat(VoiceSearch.spokenName("shuffle lamb of gob")).isEqualTo("lamb of gob")
        assertThat(VoiceSearch.spokenName("play")).isEqualTo("play")
    }

    // ------------------------------------------------------------------ playlists by name

    @Test
    fun `findPlaylist matches exactly then by whole word, with playlist words stripped`() = runTest {
        assertThat(voiceSearch.findPlaylist("ROAD TRIP")?.id).isEqualTo(5L)
        assertThat(voiceSearch.findPlaylist("my road trip playlist")?.id).isEqualTo(5L)
        assertThat(voiceSearch.findPlaylist("the playlist road trip")?.id).isEqualTo(5L)
        // "add this to my road trip": the article goes even without the word "playlist".
        assertThat(voiceSearch.findPlaylist("my road trip")?.id).isEqualTo(5L)
        assertThat(voiceSearch.findPlaylist("the road trip")?.id).isEqualTo(5L)
        assertThat(voiceSearch.findPlaylist("trip")?.id).isEqualTo(5L)
        assertThat(voiceSearch.findPlaylist("beach")).isNull()
        assertThat(voiceSearch.findPlaylist("  ")).isNull()
    }

    @Test
    fun `findPlaylist needs whole words and keeps a playlist named My Playlist reachable`() = runTest {
        coEvery { playlists.getPlaylists() } returns listOf(
            roadTrip, Playlist(id = 6, name = "Weekend"), Playlist(id = 7, name = "My Playlist"),
        )
        assertThat(voiceSearch.findPlaylist("end")).isNull()
        assertThat(voiceSearch.findPlaylist("my playlist")?.id).isEqualTo(7L)
        // Without a playlist literally named so, the leftover article must
        // not pick "My Favorites" as a whole-word match.
        coEvery { playlists.getPlaylists() } returns listOf(roadTrip, Playlist(id = 8, name = "My Favorites"))
        assertThat(voiceSearch.findPlaylist("my playlist")).isNull()
        assertThat(voiceSearch.findPlaylist("the playlist")).isNull()
    }
}
