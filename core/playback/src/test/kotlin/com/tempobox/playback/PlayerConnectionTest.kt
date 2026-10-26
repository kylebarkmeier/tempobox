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
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.async
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
    private val stateStore = mockk<PlaybackStateStore>()
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
        coEvery { stateStore.load() } returns null
        connection = PlayerConnection(
            context = ApplicationProvider.getApplicationContext(),
            shuffleEngine = ShuffleEngine(),
            settingsRepository = settingsRepository,
            remotePlay = remotePlay,
            stateStore = stateStore,
            controllerOverride = Futures.immediateFuture(controller),
        )
        idleMain()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `playShuffled publishes the shuffle mode without any controller event`() {
        runBlocking { connection.playShuffled(listOf(track(1), track(2), track(3))) }
        idleMain()
        assertThat(connection.state.value.shuffleMode).isNotEqualTo(ShuffleMode.OFF)
    }

    @Test
    fun `playTracks publishes shuffle OFF without any controller event`() {
        runBlocking { connection.playShuffled(listOf(track(1), track(2), track(3))) }
        idleMain()

        runBlocking { connection.playTracks(listOf(track(1), track(2))) }
        idleMain()

        assertThat(connection.state.value.shuffleMode).isEqualTo(ShuffleMode.OFF)
    }

    @Test
    fun `a queue replaced outside the connection drops the stale shuffle flag`() {
        runBlocking { connection.playShuffled(listOf(track(1), track(2), track(3))) }
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

    @Test
    fun `a voice queue the service shuffled is adopted with shuffle on and its pre-shuffle order`() {
        // Service uids, each item carrying the flavor and the track's pre-shuffle position.
        val foreign = listOf(
            MediaItems.toMediaItem(track(9), uid = 5_000_000_001L, shuffled = MediaItems.Shuffled(ShuffleMode.ALL, 2)),
            MediaItems.toMediaItem(track(10), uid = 5_000_000_002L, shuffled = MediaItems.Shuffled(ShuffleMode.ALL, 0)),
            MediaItems.toMediaItem(track(11), uid = 5_000_000_003L, shuffled = MediaItems.Shuffled(ShuffleMode.ALL, 1)),
        )
        mockTimeline(foreign)
        assertThat(connection.state.value.shuffleMode).isEqualTo(ShuffleMode.ALL)

        // "shuffle off" restores the order the service shuffled from.
        connection.setShuffleMode(ShuffleMode.OFF)
        idleMain()
        val restored = slot<List<MediaItem>>()
        verify { controller.addMediaItems(capture(restored)) }
        assertThat(restored.captured.map { it.mediaId })
            .containsExactly("5000000002", "5000000003", "5000000001").inOrder()
        assertThat(connection.state.value.shuffleMode).isEqualTo(ShuffleMode.OFF)
    }

    @Test
    fun `resumeQueue plays what is queued and refuses an empty queue`() = runTest {
        assertThat(connection.resumeQueue()).isFalse()
        verify(exactly = 0) { controller.play() }

        mockTimeline(listOf(MediaItems.toMediaItem(track(1), uid = 1L)))
        every { controller.playbackState } returns Player.STATE_IDLE
        assertThat(connection.resumeQueue()).isTrue()
        verify { controller.prepare() }
        verify { controller.play() }
    }

    @Test
    fun `restoreSavedQueue asks the session only when a snapshot exists and persistence is on`() = runTest {
        assertThat(connection.restoreSavedQueue()).isFalse()
        verify(exactly = 0) { controller.play() }

        coEvery { stateStore.load() } returns PlaybackStateStore.Snapshot(trackIds = listOf(1L, 2L))
        every { settingsRepository.settings } returns flowOf(
            AppSettings(queue = QueueSettings(persistQueue = false)),
        )
        assertThat(connection.restoreSavedQueue()).isFalse()
        verify(exactly = 0) { controller.play() }
    }

    @Test
    fun `restoreSavedQueue is true once the session's restore reaches the queue`() = runTest {
        coEvery { stateStore.load() } returns PlaybackStateStore.Snapshot(trackIds = listOf(1L, 2L))
        val restore = async { connection.restoreSavedQueue() }
        runCurrent()
        // The play request on the empty player is what makes the session restore ...
        verify(exactly = 1) { controller.play() }
        assertThat(restore.isCompleted).isFalse()
        // ... and the restored timeline arriving is what makes the answer true.
        mockTimeline(listOf(MediaItems.toMediaItem(track(1), uid = 0L), MediaItems.toMediaItem(track(2), uid = 1L)))
        assertThat(restore.await()).isTrue()
    }

    @Test
    fun `restoreSavedQueue plays a queue the service restored meanwhile`() = runTest {
        // Cold start through the assistant: the service's own startup restore
        // landed between the snapshot check and the controller call.
        coEvery { stateStore.load() } returns PlaybackStateStore.Snapshot(trackIds = listOf(1L))
        mockTimeline(listOf(MediaItems.toMediaItem(track(1), uid = 0L)))
        every { controller.playbackState } returns Player.STATE_IDLE
        assertThat(connection.restoreSavedQueue()).isTrue()
        verify { controller.prepare() }
        verify(exactly = 1) { controller.play() }
    }

    @Test
    fun `resumeQueue after the queue played to the end starts it over`() = runTest {
        mockTimeline(listOf(MediaItems.toMediaItem(track(1), uid = 1L)))
        every { controller.playbackState } returns Player.STATE_ENDED
        assertThat(connection.resumeQueue()).isTrue()
        verify { controller.seekToDefaultPosition(0) }
        verify { controller.play() }
    }

    @Test
    fun `restoreSavedQueue gives up when nothing arrives`() = runTest {
        coEvery { stateStore.load() } returns PlaybackStateStore.Snapshot(trackIds = listOf(1L))
        val restore = async { connection.restoreSavedQueue() }
        runCurrent()
        verify(exactly = 1) { controller.play() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertThat(restore.await()).isFalse()
    }

    @Test
    fun `the first queue a connection sees keeps the chosen shuffle flag unless it is marked`() {
        connection.setShuffleMode(ShuffleMode.ALL)
        idleMain()
        mockTimeline(listOf(MediaItems.toMediaItem(track(1), uid = 0L)))
        assertThat(connection.state.value.shuffleMode).isEqualTo(ShuffleMode.ALL)
    }

    @Test
    fun `a partly marked foreign queue is adopted as unmarked`() {
        runBlocking { connection.playShuffled(listOf(track(1), track(2))) }
        idleMain()
        mockTimeline(
            listOf(
                MediaItems.toMediaItem(track(9), uid = 5_000_000_001L, shuffled = MediaItems.Shuffled(ShuffleMode.ALL, 1)),
                MediaItems.toMediaItem(track(10), uid = 5_000_000_002L),
            ),
        )
        assertThat(connection.state.value.shuffleMode).isEqualTo(ShuffleMode.OFF)
    }

    /** Mocks the controller's timeline and publishes it to the queue flow. */
    private fun mockTimeline(items: List<MediaItem>) {
        every { controller.mediaItemCount } returns items.size
        items.forEachIndexed { i, item -> every { controller.getMediaItemAt(i) } returns item }
        connection.seekTo(0) // any command path runs refresh(), publishing the queue
        idleMain()
    }

    @Test
    fun `removeTracksFromQueue removes every occurrence of the deleted tracks`() {
        mockTimeline(
            listOf(
                MediaItems.toMediaItem(track(1), uid = 11L),
                MediaItems.toMediaItem(track(2), uid = 12L),
                MediaItems.toMediaItem(track(1), uid = 13L), // same track queued twice
            ),
        )

        connection.removeTracksFromQueue(listOf(1L))
        idleMain()

        // Rows 0 and 2, removed back-to-front so earlier indices stay valid.
        verifyOrder {
            controller.removeMediaItems(2, 3)
            controller.removeMediaItems(0, 1)
        }
        verify(exactly = 0) { controller.removeMediaItems(1, 2) }
    }

    @Test
    fun `removeTracksFromQueue with no queued match touches nothing`() {
        mockTimeline(listOf(MediaItems.toMediaItem(track(1), uid = 11L)))

        connection.removeTracksFromQueue(listOf(99L))
        idleMain()

        verify(exactly = 0) { controller.removeMediaItems(any(), any()) }
    }

    @Test
    fun `removeTracksFromQueue never matches the network streams' shared id 0`() {
        mockTimeline(
            listOf(
                MediaItems.toMediaItem(track(0), uid = 11L), // network stream
                MediaItems.toMediaItem(track(0), uid = 12L), // network stream
            ),
        )

        connection.removeTracksFromQueue(listOf(0L))
        idleMain()

        verify(exactly = 0) { controller.removeMediaItems(any(), any()) }
    }

    @Test
    fun `scattered bulk removal rebuilds the timeline instead of flooding it`() {
        // 11 rows, every second one removed: 5 single-item runs, over the
        // 4-op budget, so the rebuild path must run (one remove + one add).
        val items = (0 until 11).map { i ->
            MediaItems.toMediaItem(track(if (i % 2 == 1) 100L else (i + 1).toLong()), uid = 20L + i)
        }
        mockTimeline(items)
        every { controller.currentMediaItemIndex } returns 0 // survivor

        connection.removeTracksFromQueue(listOf(100L))
        idleMain()

        val added = slot<List<MediaItem>>()
        verifyOrder {
            controller.removeMediaItems(1, 11)
            controller.addMediaItems(capture(added))
        }
        // The anchor (row 0) stays put; the other five survivors are re-added
        // in order behind it.
        assertThat(added.captured.map { it.mediaId })
            .containsExactly("22", "24", "26", "28", "30").inOrder()
        verify(exactly = 1) { controller.removeMediaItems(any(), any()) }
        verify(exactly = 0) { controller.moveMediaItem(any(), any()) }
    }

    @Test
    fun `removing a queued track keeps the un-shuffle order`() {
        // playTracks remembers [u1, u2, u3] as the original order.
        runBlocking { connection.playTracks(listOf(track(1), track(2), track(3))) }
        idleMain()
        val set = slot<List<MediaItem>>()
        verify { controller.setMediaItems(capture(set), any(), any()) }
        val uids = set.captured.map { it.mediaId.toLong() }

        // The timeline now shows a shuffled order; remove the middle track.
        mockTimeline(listOf(set.captured[2], set.captured[0], set.captured[1]))
        connection.removeTracksFromQueue(listOf(2L))
        idleMain()

        // Shuffle-off must restore the remembered order minus the removed
        // uid, NOT adopt the current shuffled order (which would no-op).
        mockTimeline(listOf(set.captured[2], set.captured[0]))
        connection.setShuffleMode(ShuffleMode.OFF)
        idleMain()

        val reordered = slot<List<MediaItem>>()
        verify { controller.addMediaItems(capture(reordered)) }
        assertThat(reordered.captured.map { it.mediaId.toLong() })
            .containsExactly(uids[0], uids[2]).inOrder()
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

    // ------------------------------------------------------------------ voice transport outcomes

    @Test
    fun `execute says why a transport command had no effect`() = runTest {
        every { controller.mediaItemCount } returns 0
        assertThat(connection.execute(VoiceCommand.Resume)).isEqualTo("Nothing to resume: the queue is empty")
        assertThat(connection.execute(VoiceCommand.Next)).isEqualTo("The queue is empty")
        assertThat(connection.execute(VoiceCommand.Pause)).isEqualTo("Nothing is playing")
        verify(exactly = 0) { controller.play() }
        verify(exactly = 0) { controller.seekToNextMediaItem() }

        mockTimeline(listOf(MediaItems.toMediaItem(track(1), uid = 1L), MediaItems.toMediaItem(track(2), uid = 2L)))
        every { controller.hasNextMediaItem() } returns false
        every { controller.hasPreviousMediaItem() } returns true
        assertThat(connection.execute(VoiceCommand.Next)).isEqualTo("Already at the last track")
        assertThat(connection.execute(VoiceCommand.Previous)).isEqualTo("Previous track")
        verify { controller.seekToPreviousMediaItem() }
        every { controller.playWhenReady } returns false
        assertThat(connection.execute(VoiceCommand.Pause)).isEqualTo("Already paused")
        verify(exactly = 0) { controller.pause() }
    }

    @Test
    fun `execute confirms a transport command that took effect`() = runTest {
        mockTimeline(listOf(MediaItems.toMediaItem(track(1), uid = 1L)))
        every { controller.playbackState } returns Player.STATE_IDLE
        assertThat(connection.execute(VoiceCommand.Resume)).isEqualTo("Resuming")
        verify { controller.prepare() }
        verify { controller.play() }
        every { controller.playWhenReady } returns true
        assertThat(connection.execute(VoiceCommand.Pause)).isEqualTo("Pausing")
        verify { controller.pause() }
    }

    @Test
    fun `playNext on an empty queue starts playing the tracks`() = runTest {
        every { controller.mediaItemCount } returns 0
        connection.playNext(listOf(track(1), track(2)))
        verify { controller.setMediaItems(any<List<MediaItem>>(), 0, 0L) }
        verify { controller.play() }
        verify(exactly = 0) { controller.addMediaItems(any(), any<List<MediaItem>>()) }
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
        runBlocking { connection.playShuffled(listOf(track(1), track(2), track(3))) }
        idleMain()

        val app = ApplicationProvider.getApplicationContext<Application>()
        val refreshes = shadowOf(app).broadcastIntents
            .filter { it.action == PlaybackService.ACTION_WIDGET_REFRESH }
        assertThat(refreshes).isNotEmpty()
    }
}
