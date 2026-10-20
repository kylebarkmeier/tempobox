package com.tempobox.playback

import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CallGuardTest {

    @Test
    fun `ringing and both call modes count as a call`() {
        assertThat(CallGuard.isCallMode(AudioManager.MODE_RINGTONE)).isTrue()
        assertThat(CallGuard.isCallMode(AudioManager.MODE_IN_CALL)).isTrue()
        assertThat(CallGuard.isCallMode(AudioManager.MODE_IN_COMMUNICATION)).isTrue()
    }

    @Test
    fun `normal and unknown modes do not count as a call`() {
        assertThat(CallGuard.isCallMode(AudioManager.MODE_NORMAL)).isFalse()
        assertThat(CallGuard.isCallMode(AudioManager.MODE_CURRENT)).isFalse()
    }

    @Test
    fun `autoplay fires only with the setting on, a connect event, a queue, and no call`() {
        assertThat(
            CallGuard.shouldAutoplay(
                startOnConnect = true, isConnectEvent = true,
                hasQueue = true, audioMode = AudioManager.MODE_NORMAL,
            ),
        ).isTrue()
    }

    @Test
    fun `autoplay is suppressed during a call`() {
        assertThat(
            CallGuard.shouldAutoplay(
                startOnConnect = true, isConnectEvent = true,
                hasQueue = true, audioMode = AudioManager.MODE_IN_CALL,
            ),
        ).isFalse()
    }

    @Test
    fun `autoplay needs the setting, the event, and a queue`() {
        assertThat(
            CallGuard.shouldAutoplay(false, true, true, AudioManager.MODE_NORMAL),
        ).isFalse()
        assertThat(
            CallGuard.shouldAutoplay(true, false, true, AudioManager.MODE_NORMAL),
        ).isFalse()
        assertThat(
            CallGuard.shouldAutoplay(true, true, false, AudioManager.MODE_NORMAL),
        ).isFalse()
    }

    @Test
    fun `mid-call playback is paused, idle or post-call playback is not`() {
        assertThat(CallGuard.shouldPauseNow(isPlaying = true, audioMode = AudioManager.MODE_IN_CALL)).isTrue()
        assertThat(CallGuard.shouldPauseNow(isPlaying = false, audioMode = AudioManager.MODE_IN_CALL)).isFalse()
        assertThat(CallGuard.shouldPauseNow(isPlaying = true, audioMode = AudioManager.MODE_NORMAL)).isFalse()
    }

    @Test
    fun `deliberate playback during VoIP calls and ringing is never force-paused`() {
        // VoIP apps hold MODE_IN_COMMUNICATION for the whole call; the user
        // must still be able to play music on purpose during one.
        assertThat(
            CallGuard.shouldPauseNow(isPlaying = true, audioMode = AudioManager.MODE_IN_COMMUNICATION),
        ).isFalse()
        assertThat(
            CallGuard.shouldPauseNow(isPlaying = true, audioMode = AudioManager.MODE_RINGTONE),
        ).isFalse()
    }
}
