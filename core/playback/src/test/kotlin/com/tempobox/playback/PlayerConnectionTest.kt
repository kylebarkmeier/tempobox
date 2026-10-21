package com.tempobox.playback

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.tempobox.model.AudioFormat
import com.tempobox.model.QueueItem
import com.tempobox.model.ShuffleMode
import com.tempobox.model.Track
import com.tempobox.remoteplay.RemotePlayController
import com.tempobox.remoteplay.RemotePlayEvent
import com.tempobox.remoteplay.RemotePlaybackState
import com.tempobox.settings.AppSettings
import com.tempobox.settings.QueueSettings
import com.tempobox.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Regression tests for the widget shuffle-state bug: the public [PlayerConnection.state]
 * must reflect a shuffle-mode change immediately, even when the controller
 * never delivers a player event (a mocked controller delivers none — exactly
 * the window in which the home-screen widget used to render a stale mode).
 */
@RunWith(AndroidJUnit4::class)
class PlayerConnectionTest {

    private val controller = mockk<MediaController>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>()
    private val remoteState = MutableStateFlow(RemotePlaybackState())
    private val remoteEvents = MutableSharedFlow<RemotePlayEvent>(extraBufferCapacity = 4)
    private val remotePlay = mockk<RemotePlayController>(relaxed = true) {
        every { state } returns remoteState
        every { events } returns remoteEvents
        every { renderers } returns MutableStateFlow(emptyList())
        every { servers } returns MutableStateFlow(emptyList())
    }
    private lateinit var connection: PlayerConnection

    private fun track(id: Long) = Track(
        id = id,
        filePath = "/music/$id.flac",
        title = "T$id",
        artist = "Artist",
        albumArtist = "Artist",
        album = "Album",
        genre = "Rock",
        year = null,
        durationMs = 1000,
        format = AudioFormat.FLAC,
        rating = 0,
        hasEmbeddedArt = false,
    )

    @Before
    fun setUp() {
        every { settingsRepository.settings } returns flowOf(AppSettings())
        every { controller.mediaItemCount } returns 0
        // A relaxed child mock of MediaItem has no mediaMetadata; refresh()
        // would NPE on it in a background coroutine (invisible to plain tests,
        // but runTest-based ones fail on the accumulated uncaught exceptions).
        every { controller.currentMediaItem } returns null
        connection = PlayerConnection(
            context = ApplicationProvider.getApplicationContext(),
            shuffleEngine = ShuffleEngine(),
            settingsRepository = settingsRepository,
            remotePlay = remotePlay,
            controllerOverride = Futures.immediateFuture(controller),
        )
        idleMain()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `playShuffled publishes the shuffle mode without any controller event`() {
        connection.playShuffled(listOf(track(1), track(2), track(3)))
        idleMain()
        assertThat(connection.state.value.shuffleMode).isNotEqualTo(ShuffleMode.OFF)
    }

    @Test
    fun `playTracks publishes shuffle OFF without any controller event`() {
        connection.playShuffled(listOf(track(1), track(2), track(3)))
        idleMain()

        connection.playTracks(listOf(track(1), track(2)))
        idleMain()

        assertThat(connection.state.value.shuffleMode).isEqualTo(ShuffleMode.OFF)
    }

    @Test
    fun `playFromSearch sends one item carrying the spoken query`() {
        connection.playFromSearch("lamb of god")
        idleMain()

        val items = slot<List<MediaItem>>()
        verify { controller.setMediaItems(capture(items)) }
        assertThat(items.captured).hasSize(1)
        assertThat(items.captured.single().requestMetadata.searchQuery).isEqualTo("lamb of god")
        verify { controller.prepare() }
        verify { controller.play() }
    }

    @Test
    fun `a queue replaced outside the connection drops the stale shuffle flag`() {
        connection.playShuffled(listOf(track(1), track(2), track(3)))
        idleMain()
        assertThat(connection.state.value.shuffleMode).isNotEqualTo(ShuffleMode.OFF)

        // Simulate Android Auto swapping the whole timeline in the service:
        // the controller now reports items whose uids we never allocated.
        val foreign = listOf(
            MediaItems.toMediaItem(track(9), uid = 5_000_000_001L),
            MediaItems.toMediaItem(track(10), uid = 5_000_000_002L),
        )
        every { controller.mediaItemCount } returns foreign.size
        every { controller.getMediaItemAt(0) } returns foreign[0]
        every { controller.getMediaItemAt(1) } returns foreign[1]

        connection.seekTo(0) // any command path runs refresh()
        idleMain()

        assertThat(connection.state.value.shuffleMode).isEqualTo(ShuffleMode.OFF)
    }

    private val collectors = mutableListOf<Job>()

    @After
    fun tearDown() {
        collectors.forEach(Job::cancel)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun collectErrors(): List<String> {
        val events = mutableListOf<String>()
        collectors += CoroutineScope(UnconfinedTestDispatcher()).launch {
            connection.errors.collect { events += it }
        }
        return events
    }

    @Test
    fun `a player error surfaces on the errors flow`() {
        val listener = slot<Player.Listener>()
        verify { controller.addListener(capture(listener)) }
        val events = collectErrors()

        listener.captured.onPlayerError(
            PlaybackException("decoder died", null, PlaybackException.ERROR_CODE_DECODING_FAILED),
        )
        idleMain()

        assertThat(events).hasSize(1)
        assertThat(events.single()).contains("ERROR_CODE_DECODING_FAILED")
    }

    @Test
    fun `a rejected output switch reverts the selection and reports it`() {
        every { controller.sendCustomCommand(any(), any()) } returns
            Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
        val events = collectErrors()

        connection.selectAudioOutput(42)
        idleMain()

        assertThat(connection.selectedOutputId.value).isNull() // reverted
        assertThat(events).hasSize(1)
    }

    @Test
    fun `a successful output switch keeps the selection`() {
        every { controller.sendCustomCommand(any(), any()) } returns
            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        val events = collectErrors()

        connection.selectAudioOutput(42)
        idleMain()

        assertThat(connection.selectedOutputId.value).isEqualTo(42)
        assertThat(events).isEmpty()
    }

    @Test
    fun `addToQueue reports how many tracks were really added`() = runTest {
        every { settingsRepository.settings } returns flowOf(
            AppSettings(queue = QueueSettings(allowDuplicates = false)),
        )
        // The queue already holds track 1.
        val item = MediaItems.toMediaItem(track(1), uid = 1L)
        every { controller.mediaItemCount } returns 1
        every { controller.getMediaItemAt(0) } returns item
        connection.seekTo(0) // any command path runs refresh()
        idleMain()
        assertThat(connection.queue.value.map(QueueItem::uid)).containsExactly(1L)

        assertThat(connection.addToQueue(listOf(track(1)))).isEqualTo(0) // duplicate
        assertThat(connection.addToQueue(listOf(track(1), track(2)))).isEqualTo(1)
    }

    // ------------------------------------------------------------------ network renderer routing

    private fun activateRemote(isPlaying: Boolean = true) {
        remoteState.value = RemotePlaybackState(
            active = true,
            rendererId = "uuid:r1",
            rendererName = "Speaker",
            isPlaying = isPlaying,
            positionMs = 30_000,
            durationMs = 200_000,
        )
    }

    @Test
    fun `selecting a renderer pauses local playback and hands off the track`() {
        val item = MediaItems.toMediaItem(track(1), uid = 1)
        every { controller.mediaItemCount } returns 1
        every { controller.currentMediaItem } returns item
        every { controller.getMediaItemAt(0) } returns item
        every { controller.currentPosition } returns 42_000
        every { controller.isPlaying } returns true
        coEvery { remotePlay.connect("uuid:r1") } returns true

        connection.selectNetworkOutput("uuid:r1")
        idleMain()

        verify { controller.pause() }
        coVerify { remotePlay.connect("uuid:r1") }
        coVerify {
            remotePlay.load(
                match { it.id == 1L },
                positionMs = 42_000,
                play = true,
            )
        }
    }

    @Test
    fun `a failed connect surfaces a message and keeps local playback`() {
        coEvery { remotePlay.connect(any()) } returns false
        val events = collectErrors()
        connection.selectNetworkOutput("uuid:gone")
        idleMain()
        assertThat(events.single()).contains("connect")
        coVerify(exactly = 0) { remotePlay.load(any(), any(), any()) }
    }

    @Test
    fun `transport controls route to the renderer while a session is active`() {
        activateRemote(isPlaying = true)
        connection.togglePlayPause()
        idleMain()
        coVerify { remotePlay.pause() }

        activateRemote(isPlaying = false)
        connection.togglePlayPause()
        idleMain()
        coVerify { remotePlay.play() }

        connection.seekTo(5_000)
        idleMain()
        coVerify { remotePlay.seekTo(5_000) }
    }

    @Test
    fun `now playing state overlays the renderer position while active`() {
        activateRemote(isPlaying = true)
        idleMain()
        assertThat(connection.state.value.isPlaying).isTrue()
        assertThat(connection.state.value.positionMs).isEqualTo(30_000)
        assertThat(connection.state.value.durationMs).isEqualTo(200_000)
    }

    @Test
    fun `a lost session falls back to local playback with a message`() {
        val item = MediaItems.toMediaItem(track(1), uid = 1)
        every { controller.mediaItemCount } returns 1
        every { controller.getMediaItemAt(0) } returns item
        activateRemote(isPlaying = true)
        idleMain() // let the position recorder see 30s

        val events = collectErrors()
        remoteState.value = RemotePlaybackState() // controller tears down first
        remoteEvents.tryEmit(RemotePlayEvent.SessionLost("Speaker"))
        idleMain()

        assertThat(events.single()).contains("Speaker")
        verify { controller.seekTo(30_000) }
        verify { controller.play() }
    }

    @Test
    fun `renderer track end advances the local queue`() {
        every { controller.mediaItemCount } returns 3
        every { controller.hasNextMediaItem() } returns true
        every { controller.repeatMode } returns Player.REPEAT_MODE_OFF
        activateRemote(isPlaying = true)
        remoteEvents.tryEmit(RemotePlayEvent.TrackEnded)
        idleMain()
        verify { controller.seekToNextMediaItem() }
    }

    @Test
    fun `renderer track end at the end of the queue stays stopped`() {
        every { controller.mediaItemCount } returns 3
        every { controller.hasNextMediaItem() } returns false
        every { controller.repeatMode } returns Player.REPEAT_MODE_OFF
        activateRemote(isPlaying = true)
        remoteEvents.tryEmit(RemotePlayEvent.TrackEnded)
        idleMain()
        verify(exactly = 0) { controller.seekToNextMediaItem() }
        coVerify(exactly = 0) { remotePlay.load(any(), any(), any()) }
    }

    @Test
    fun `selecting a local output disconnects the renderer session`() {
        activateRemote(isPlaying = true)
        connection.selectAudioOutput(null)
        idleMain()
        coVerify { remotePlay.disconnect() }
    }

    @Test
    fun `shuffle mode changes nudge the home-screen widget`() {
        connection.playShuffled(listOf(track(1), track(2), track(3)))
        idleMain()

        val app = ApplicationProvider.getApplicationContext<Application>()
        val refreshes = shadowOf(app).broadcastIntents
            .filter { it.action == PlaybackService.ACTION_WIDGET_REFRESH }
        assertThat(refreshes).isNotEmpty()
    }
}
