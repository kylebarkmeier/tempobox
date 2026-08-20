package com.tempobox.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayedThresholdTest {

    @Test
    fun `tracks under 30 seconds never count`() {
        assertThat(PlayedThreshold.shouldCount(durationMs = 29_999, playedMs = 29_999)).isFalse()
    }

    @Test
    fun `half of a normal track counts, just under does not`() {
        assertThat(PlayedThreshold.shouldCount(durationMs = 200_000, playedMs = 100_000)).isTrue()
        assertThat(PlayedThreshold.shouldCount(durationMs = 200_000, playedMs = 99_999)).isFalse()
    }

    @Test
    fun `long tracks cap at four minutes`() {
        val twentyMinutes = 20 * 60_000L
        assertThat(PlayedThreshold.shouldCount(twentyMinutes, 4 * 60_000L)).isTrue()
        assertThat(PlayedThreshold.shouldCount(twentyMinutes, 4 * 60_000L - 1)).isFalse()
    }

    @Test
    fun `exactly 30 seconds duration uses the half rule`() {
        assertThat(PlayedThreshold.shouldCount(30_000, 15_000)).isTrue()
        assertThat(PlayedThreshold.shouldCount(30_000, 14_999)).isFalse()
    }
}
