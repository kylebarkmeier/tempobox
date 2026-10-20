package com.tempobox.playback

import android.app.Application
import android.os.Looper
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.tempobox.model.AudioFormat
import com.tempobox.model.ShuffleMode
import com.tempobox.model.Track
import com.tempobox.settings.AppSettings
import com.tempobox.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
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
    fun `shuffle mode changes nudge the home-screen widget`() {
        connection.playShuffled(listOf(track(1), track(2), track(3)))
        idleMain()

        val app = ApplicationProvider.getApplicationContext<Application>()
        val refreshes = shadowOf(app).broadcastIntents
            .filter { it.action == PlaybackService.ACTION_WIDGET_REFRESH }
        assertThat(refreshes).isNotEmpty()
    }
}
