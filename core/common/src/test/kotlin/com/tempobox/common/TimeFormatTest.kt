package com.tempobox.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimeFormatTest {

    @Test
    fun `formats seconds and minutes`() {
        assertThat(TimeFormat.duration(0)).isEqualTo("0:00")
        assertThat(TimeFormat.duration(999)).isEqualTo("0:00")
        assertThat(TimeFormat.duration(1_000)).isEqualTo("0:01")
        assertThat(TimeFormat.duration(61_000)).isEqualTo("1:01")
        assertThat(TimeFormat.duration(600_000)).isEqualTo("10:00")
    }

    @Test
    fun `formats hours with two-digit minutes`() {
        assertThat(TimeFormat.duration(3_600_000)).isEqualTo("1:00:00")
        assertThat(TimeFormat.duration(3_661_000)).isEqualTo("1:01:01")
    }

    @Test
    fun `negative input clamps to zero`() {
        assertThat(TimeFormat.duration(-5_000)).isEqualTo("0:00")
    }

    @Test
    fun `remaining counts down with minus prefix and never goes negative`() {
        assertThat(TimeFormat.remaining(30_000, 90_000)).isEqualTo("-1:00")
        assertThat(TimeFormat.remaining(90_000, 90_000)).isEqualTo("-0:00")
        assertThat(TimeFormat.remaining(100_000, 90_000)).isEqualTo("-0:00")
    }
}
