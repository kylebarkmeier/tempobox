package com.tempobox.playback

import com.google.common.truth.Truth.assertThat
import com.tempobox.library.AddToPlaylistResult
import com.tempobox.library.LibraryRepository
import com.tempobox.library.PlaylistRepository
import com.tempobox.model.AudioFormat
import com.tempobox.model.NowPlayingState
import com.tempobox.model.Playlist
import com.tempobox.model.SmartRule
import com.tempobox.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceActionsTest {

    private val player = mockk<PlayerConnection>(relaxed = true)
    private val voiceSearch = mockk<VoiceSearch>()
    private val playlists = mockk<PlaylistRepository>()
    private val library = mockk<LibraryRepository>()
    private val voiceLog = mockk<VoiceLog>(relaxed = true)
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val actions = VoiceActions(player, voiceSearch, playlists, library, voiceLog, scope)

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

    private val allicorn = VoiceSearch.Resolution(
        tracks = listOf(track(1, "Skyward"), track(2, "Thunderstruck")),
        shuffle = false,
        description = "artist: Allicorn",
    )
    private val roadTrip = Playlist(id = 5, name = "Road Trip")

    @Before
    fun setUp() {
        coEvery { voiceSearch.resolve("allicorn", any()) } returns allicorn
        coEvery { voiceSearch.resolve("shuffle allicorn", any()) } returns allicorn.copy(shuffle = true)
        coEvery { voiceSearch.resolve("nobody", any()) } returns null
        coEvery { playlists.getPlaylists() } returns listOf(roadTrip)
        coEvery { voiceSearch.findPlaylist("road trip", any()) } returns roadTrip
        coEvery { voiceSearch.findPlaylist("nothing", any()) } returns null
        coEvery { playlists.addToPlaylist(any(), any()) } returns AddToPlaylistResult.ADDED
        coEvery { library.getTracksByIds(any()) } answers { firstArg<List<Long>>().map { track(it, "live $it") } }
        every { player.state } returns MutableStateFlow(NowPlayingState())
    }

    // ------------------------------------------------------------------ dispatch

    @Test
    fun `handle logs words that are not a command and returns null`() {
        val command = actions.handle("what is the weather", listOf("what is the whether"), VoiceLog.SOURCE_DIALOG) { _, _ -> }
        assertThat(command).isNull()
        verify {
            voiceLog.record(
                VoiceLog.SOURCE_DIALOG, "what is the weather", null, "no command", listOf("what is the whether"),
            )
        }
        assertThat(actions.handle("", emptyList(), VoiceLog.SOURCE_DIALOG) { _, _ -> }).isNull()
        verify { voiceLog.record(VoiceLog.SOURCE_DIALOG, "", null, "heard nothing", emptyList()) }
    }

    @Test
    fun `handle runs the command on its scope, logs the outcome and reports it`() = runTest(dispatcher) {
        coEvery { player.addToQueue(allicorn.tracks) } returns 2
        var reported: Pair<VoiceCommand, String>? = null
        val command = actions.handle("add allicorn to the queue", emptyList(), VoiceLog.SOURCE_DIALOG) { c, o ->
            reported = c to o
        }
        assertThat(command).isEqualTo(VoiceCommand.AddToQueue("allicorn"))
        assertThat(reported).isNull()
        scope.advanceUntilIdle()
        assertThat(reported).isEqualTo(command to "Added 2 tracks to queue (artist: Allicorn)")
        verify {
            voiceLog.record(
                VoiceLog.SOURCE_DIALOG, "add allicorn to the queue", "AddToQueue(query=allicorn)",
                "Added 2 tracks to queue (artist: Allicorn)", emptyList(),
            )
        }
    }

    @Test
    fun `handle turns a failure into an honest outcome instead of crashing`() = runTest(dispatcher) {
        coEvery { player.addToQueue(any()) } throws IllegalStateException("controller gone")
        var reported: String? = null
        actions.handle("add allicorn to the queue", emptyList(), VoiceLog.SOURCE_DIALOG) { _, o -> reported = o }
        scope.advanceUntilIdle()
        assertThat(reported).isEqualTo("Couldn't do that: controller gone")
    }

    // ------------------------------------------------------------------ commands

    @Test
    fun `immediate commands report what the player says happened`() = runTest {
        coEvery { player.execute(VoiceCommand.Pause) } returns "Pausing"
        assertThat(actions.execute(VoiceCommand.Pause)).isEqualTo("Pausing")
        // The player's refusal is the outcome, not the canned confirmation.
        coEvery { player.execute(VoiceCommand.Resume) } returns "Nothing to resume: the queue is empty"
        assertThat(actions.execute(VoiceCommand.Resume)).isEqualTo("Nothing to resume: the queue is empty")
    }

    @Test
    fun `play search resolves in-process and replaces the queue`() = runTest {
        assertThat(actions.execute(VoiceCommand.PlaySearch("allicorn")))
            .isEqualTo("Playing 2 tracks (artist: Allicorn)")
        coVerify { player.playTracks(allicorn.tracks) }
        assertThat(actions.execute(VoiceCommand.PlaySearch("shuffle allicorn")))
            .isEqualTo("Playing 2 tracks shuffled (artist: Allicorn)")
        coVerify { player.playShuffled(allicorn.tracks) }
        verify(exactly = 0) { player.playFromSearch(any(), any()) }
    }

    @Test
    fun `a failing play reaches the caller instead of a claimed success`() = runTest(dispatcher) {
        coEvery { player.playTracks(any(), any()) } throws IllegalStateException("controller gone")
        var reported: String? = null
        actions.handle("play allicorn", emptyList(), VoiceLog.SOURCE_DIALOG) { _, o -> reported = o }
        scope.advanceUntilIdle()
        assertThat(reported).isEqualTo("Couldn't do that: controller gone")
    }

    @Test
    fun `play search with no match plays nothing and says so`() = runTest {
        assertThat(actions.execute(VoiceCommand.PlaySearch("nobody")))
            .isEqualTo("Nothing in the library matched \"nobody\"")
        coVerify(exactly = 0) { player.playTracks(any(), any()) }
        coVerify(exactly = 0) { player.playShuffled(any()) }
        // The miss names the request as spoken, without the command word.
        coEvery { voiceSearch.resolve("shuffle nobody", any()) } returns null
        assertThat(actions.execute(VoiceCommand.PlaySearch("shuffle nobody")))
            .isEqualTo("Nothing in the library matched \"nobody\"")
    }

    @Test
    fun `add to queue reports the count and what matched`() = runTest {
        coEvery { player.addToQueue(allicorn.tracks) } returns 2
        assertThat(actions.execute(VoiceCommand.AddToQueue("allicorn")))
            .isEqualTo("Added 2 tracks to queue (artist: Allicorn)")
    }

    @Test
    fun `add to queue is honest about duplicates`() = runTest {
        coEvery { player.addToQueue(allicorn.tracks) } returns 0
        assertThat(actions.execute(VoiceCommand.AddToQueue("allicorn")))
            .isEqualTo("Already in queue (artist: Allicorn)")
        coEvery { player.addToQueue(allicorn.tracks) } returns 1
        assertThat(actions.execute(VoiceCommand.AddToQueue("allicorn")))
            .isEqualTo("Added 1 track to queue (artist: Allicorn; rest already queued)")
    }

    @Test
    fun `an unmatched name says so and touches nothing`() = runTest {
        assertThat(actions.execute(VoiceCommand.AddToQueue("nobody")))
            .isEqualTo("Nothing in the library matched \"nobody\"")
        assertThat(actions.execute(VoiceCommand.PlayNext("nobody")))
            .isEqualTo("Nothing in the library matched \"nobody\"")
        coVerify(exactly = 0) { player.addToQueue(any()) }
        coVerify(exactly = 0) { player.playNext(any()) }
    }

    @Test
    fun `play next awaits the insert then reports it`() = runTest {
        val outcome = actions.execute(VoiceCommand.PlayNext("allicorn"))
        coVerify { player.playNext(allicorn.tracks) }
        assertThat(outcome).isEqualTo("Playing 2 tracks next (artist: Allicorn)")
    }

    // ------------------------------------------------------------------ playlists

    @Test
    fun `add to playlist resolves the subject and the playlist, loading playlists once`() = runTest {
        val outcome = actions.execute(VoiceCommand.AddToPlaylist("allicorn", "road trip"))
        coVerify { playlists.addToPlaylist(5, listOf(1L, 2L)) }
        coVerify(exactly = 1) { playlists.getPlaylists() }
        coVerify { voiceSearch.findPlaylist("road trip", listOf(roadTrip)) }
        coVerify { voiceSearch.resolve("allicorn", listOf(roadTrip)) }
        assertThat(outcome).isEqualTo("Added 2 tracks to \"Road Trip\" (artist: Allicorn)")
    }

    @Test
    fun `add this to a playlist uses the live row of the playing track`() = runTest {
        every { player.state } returns MutableStateFlow(NowPlayingState(track = track(7, "Svefn")))
        val outcome = actions.execute(VoiceCommand.AddToPlaylist(null, "road trip"))
        coVerify { playlists.addToPlaylist(5, listOf(7L)) }
        assertThat(outcome).isEqualTo("Added 1 track to \"Road Trip\" (track: live 7)")
    }

    @Test
    fun `add this with nothing playing says so`() = runTest {
        assertThat(actions.execute(VoiceCommand.AddToPlaylist(null, "road trip")))
            .isEqualTo("Nothing is playing to add to \"Road Trip\"")
        coVerify(exactly = 0) { playlists.addToPlaylist(any(), any()) }
    }

    @Test
    fun `add this refuses a network stream and a track that left the library`() = runTest {
        every { player.state } returns MutableStateFlow(
            NowPlayingState(track = track(0, "Radio").copy(filePath = "http://nas/stream.mp3")),
        )
        assertThat(actions.execute(VoiceCommand.AddToPlaylist(null, "road trip")))
            .isEqualTo("\"Radio\" is a network stream, not a library track")

        every { player.state } returns MutableStateFlow(NowPlayingState(track = track(7, "Gone")))
        coEvery { library.getTracksByIds(listOf(7L)) } returns emptyList()
        assertThat(actions.execute(VoiceCommand.AddToPlaylist(null, "road trip")))
            .isEqualTo("\"Gone\" is no longer in the library")
        coVerify(exactly = 0) { playlists.addToPlaylist(any(), any()) }
    }

    @Test
    fun `an unknown playlist is reported before anything is resolved`() = runTest {
        assertThat(actions.execute(VoiceCommand.AddToPlaylist("allicorn", "nothing")))
            .isEqualTo("No playlist named \"nothing\"")
        coVerify(exactly = 0) { voiceSearch.resolve(any(), any()) }
    }

    @Test
    fun `auto and broken playlists refuse before any lookup, with the real reason`() = runTest {
        coEvery { voiceSearch.findPlaylist("metal", any()) } returns
            Playlist(id = 9, name = "Metal", smartRule = mockk<SmartRule>())
        assertThat(actions.execute(VoiceCommand.AddToPlaylist("allicorn", "metal")))
            .isEqualTo("Can't add tracks to \"Metal\": its auto rule picks the tracks")
        coEvery { voiceSearch.findPlaylist("old", any()) } returns
            Playlist(id = 10, name = "Old", ruleBroken = true)
        assertThat(actions.execute(VoiceCommand.AddToPlaylist(null, "old")))
            .isEqualTo("Can't add tracks to \"Old\": its auto rule is broken")
        coVerify(exactly = 0) { voiceSearch.resolve(any(), any()) }
        coVerify(exactly = 0) { playlists.addToPlaylist(any(), any()) }
    }

    @Test
    fun `the repository's refusal reason is the outcome`() = runTest {
        coEvery { playlists.addToPlaylist(5, any()) } returns AddToPlaylistResult.MISSING
        assertThat(actions.execute(VoiceCommand.AddToPlaylist("allicorn", "road trip")))
            .isEqualTo("Can't add tracks to \"Road Trip\": it no longer exists")
        // A rule that appeared between the lookup and the insert.
        coEvery { playlists.addToPlaylist(5, any()) } returns AddToPlaylistResult.SMART_PLAYLIST
        assertThat(actions.execute(VoiceCommand.AddToPlaylist("allicorn", "road trip")))
            .isEqualTo("Can't add tracks to \"Road Trip\": its auto rule picks the tracks")
    }
}
