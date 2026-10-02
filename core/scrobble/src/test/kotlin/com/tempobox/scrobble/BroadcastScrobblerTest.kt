package com.tempobox.scrobble

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.tempobox.model.Track
import com.tempobox.settings.AppSettings
import com.tempobox.settings.ScrobbleSettings
import com.tempobox.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Robolectric: asserts the actual Intents handed to the user's scrobbler app
 * (the "Integrations ▸ Scrobbling" product feature) and the settings gate.
 */
@RunWith(AndroidJUnit4::class)
class BroadcastScrobblerTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val settingsFlow = MutableStateFlow(AppSettings())
    private val settingsRepository: SettingsRepository = mockk {
        every { settings } returns settingsFlow
    }
    private val scrobbler = BroadcastScrobbler(application, settingsRepository)

    private val track = Track(
        id = 1,
        filePath = "/music/a.mp3",
        title = "Getting Ready",
        artist = "KI/KI",
        albumArtist = "Various Artists",
        album = "A State Of Trance 2025",
        durationMs = 175_000,
    )

    private fun broadcasts(): List<Intent> = shadowOf(application).broadcastIntents

    @Test
    fun `scrobble sends one SLS COMPLETE broadcast with the track payload`() = runTest {
        scrobbler.scrobble(track, startedAtEpochSec = 1_700_000_000)

        val intent = broadcasts().single()
        assertThat(intent.action).isEqualTo(ScrobbleBroadcast.ACTION)
        assertThat(intent.getIntExtra("state", -1))
            .isEqualTo(ScrobbleBroadcast.State.COMPLETE.code)
        assertThat(intent.getStringExtra("artist")).isEqualTo("KI/KI")
        assertThat(intent.getStringExtra("album")).isEqualTo("A State Of Trance 2025")
        assertThat(intent.getStringExtra("track")).isEqualTo("Getting Ready")
        assertThat(intent.getIntExtra("duration", -1)).isEqualTo(175)
        assertThat(intent.getStringExtra("app-name")).isEqualTo("TempoBox")
        assertThat(intent.getStringExtra("app-package")).isEqualTo(application.packageName)
    }

    @Test
    fun `updateNowPlaying sends the START state`() = runTest {
        scrobbler.updateNowPlaying(track)
        assertThat(broadcasts().single().getIntExtra("state", -1))
            .isEqualTo(ScrobbleBroadcast.State.START.code)
    }

    @Test
    fun `nothing is broadcast when scrobbling is turned off`() = runTest {
        settingsFlow.value = AppSettings(scrobble = ScrobbleSettings(broadcastScrobbles = false))

        scrobbler.updateNowPlaying(track)
        scrobbler.scrobble(track, startedAtEpochSec = 0)

        assertThat(broadcasts()).isEmpty()
    }

    @Test
    fun `the gate is re-read per broadcast so toggling applies immediately`() = runTest {
        settingsFlow.value = AppSettings(scrobble = ScrobbleSettings(broadcastScrobbles = false))
        scrobbler.scrobble(track, startedAtEpochSec = 0)
        assertThat(broadcasts()).isEmpty()

        settingsFlow.value = AppSettings(scrobble = ScrobbleSettings(broadcastScrobbles = true))
        scrobbler.scrobble(track, startedAtEpochSec = 0)
        assertThat(broadcasts()).hasSize(1)
    }
}
