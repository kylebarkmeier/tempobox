package com.tempobox.scrobble

import com.google.common.truth.Truth.assertThat
import com.tempobox.model.Track
import org.junit.Test

class ScrobbleBroadcastTest {

    private fun track(artist: String = "KI/KI", albumArtist: String = "Various Artists") = Track(
        id = 1,
        filePath = "/music/a.mp3",
        title = "Getting Ready",
        artist = artist,
        albumArtist = albumArtist,
        album = "A State Of Trance 2025",
        durationMs = 175_000,
    )

    @Test
    fun `extras follow the SLS api`() {
        val extras = ScrobbleBroadcast.extras(
            track = track(),
            state = ScrobbleBroadcast.State.COMPLETE,
            appName = "TempoBox",
            appPackage = "com.tempobox",
        )

        assertThat(extras["state"]).isEqualTo(3)
        assertThat(extras["app-name"]).isEqualTo("TempoBox")
        assertThat(extras["app-package"]).isEqualTo("com.tempobox")
        assertThat(extras["artist"]).isEqualTo("KI/KI")
        assertThat(extras["album"]).isEqualTo("A State Of Trance 2025")
        assertThat(extras["track"]).isEqualTo("Getting Ready")
        assertThat(extras["duration"]).isEqualTo(175)
    }

    @Test
    fun `blank track artist falls back to album artist`() {
        val extras = ScrobbleBroadcast.extras(
            track = track(artist = ""),
            state = ScrobbleBroadcast.State.START,
            appName = "TempoBox",
            appPackage = "com.tempobox",
        )

        assertThat(extras["state"]).isEqualTo(0)
        assertThat(extras["artist"]).isEqualTo("Various Artists")
    }

    @Test
    fun `state codes match the SLS protocol`() {
        assertThat(ScrobbleBroadcast.State.START.code).isEqualTo(0)
        assertThat(ScrobbleBroadcast.State.RESUME.code).isEqualTo(1)
        assertThat(ScrobbleBroadcast.State.PAUSE.code).isEqualTo(2)
        assertThat(ScrobbleBroadcast.State.COMPLETE.code).isEqualTo(3)
    }

    @Test
    fun `the broadcast action is the SLS playstatechanged intent`() {
        // Scrobbler apps register receivers for exactly this string.
        assertThat(ScrobbleBroadcast.ACTION).isEqualTo("com.adam.aslfms.notify.playstatechanged")
    }

    @Test
    fun `duration is whole seconds, truncated`() {
        val extras = ScrobbleBroadcast.extras(
            track = track().copy(durationMs = 2_999),
            state = ScrobbleBroadcast.State.START,
            appName = "TempoBox",
            appPackage = "com.tempobox",
        )
        assertThat(extras["duration"]).isEqualTo(2)
    }
}
