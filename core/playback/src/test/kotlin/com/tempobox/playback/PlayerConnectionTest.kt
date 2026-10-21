package com.tempobox.playback

import android.app.Application
import android.os.Looper
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
import com.tempobox.settings.AppSettings
import com.tempobox.settings.QueueSettings
import com.tempobox.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
        connection = PlayerConnection(
            context = ApplicationProvider.getApplicationContext(),
            shuffleEngine = ShuffleEngine(),
            settingsRepository = settingsRepository,
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
