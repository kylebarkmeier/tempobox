package com.tempobox.playback

import com.google.common.truth.Truth.assertThat
import com.tempobox.playback.VolumeTripleTapDetector.Direction
import org.junit.Test

class VolumeTripleTapDetectorTest {

    @Test
    fun `three quick same-direction taps fire once`() {
        val detector = VolumeTripleTapDetector(windowMs = 900)
        assertThat(detector.onVolumeChange(Direction.UP, 0)).isNull()
        assertThat(detector.onVolumeChange(Direction.UP, 300)).isNull()
        assertThat(detector.onVolumeChange(Direction.UP, 600)).isEqualTo(Direction.UP)
        // Sequence resets after firing — a fourth tap starts over.
        assertThat(detector.onVolumeChange(Direction.UP, 700)).isNull()
    }

    @Test
    fun `slow taps never fire`() {
        val detector = VolumeTripleTapDetector(windowMs = 900)
        assertThat(detector.onVolumeChange(Direction.DOWN, 0)).isNull()
        assertThat(detector.onVolumeChange(Direction.DOWN, 1_000)).isNull() // too late, restarts
        assertThat(detector.onVolumeChange(Direction.DOWN, 1_500)).isNull()
        assertThat(detector.onVolumeChange(Direction.DOWN, 2_000)).isEqualTo(Direction.DOWN)
    }

    @Test
    fun `direction change restarts the count`() {
        val detector = VolumeTripleTapDetector(windowMs = 900)
        detector.onVolumeChange(Direction.UP, 0)
        detector.onVolumeChange(Direction.UP, 100)
        assertThat(detector.onVolumeChange(Direction.DOWN, 200)).isNull()
        assertThat(detector.onVolumeChange(Direction.DOWN, 300)).isNull()
        assertThat(detector.onVolumeChange(Direction.DOWN, 400)).isEqualTo(Direction.DOWN)
    }

    @Test
    fun `currentCount reports sequence progress`() {
        val detector = VolumeTripleTapDetector()
        detector.onVolumeChange(Direction.UP, 0)
        assertThat(detector.currentCount).isEqualTo(1)
        detector.onVolumeChange(Direction.UP, 100)
        assertThat(detector.currentCount).isEqualTo(2)
    }

    @Test
    fun `reset clears everything`() {
        val detector = VolumeTripleTapDetector()
        detector.onVolumeChange(Direction.UP, 0)
        detector.onVolumeChange(Direction.UP, 100)
        detector.reset()
        assertThat(detector.currentCount).isEqualTo(0)
        assertThat(detector.onVolumeChange(Direction.UP, 200)).isNull()
    }
}
