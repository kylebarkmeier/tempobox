package com.tempobox.remoteplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HttpRangesTest {

    @Test
    fun `no header serves the whole file`() {
        assertThat(HttpRanges.parse(null, 100)).isEqualTo(HttpRanges.Result.Whole)
    }

    @Test
    fun `explicit range`() {
        assertThat(HttpRanges.parse("bytes=10-19", 100))
            .isEqualTo(HttpRanges.Result.Partial(10, 19))
    }

    @Test
    fun `open-ended range runs to the last byte`() {
        assertThat(HttpRanges.parse("bytes=90-", 100))
            .isEqualTo(HttpRanges.Result.Partial(90, 99))
    }

    @Test
    fun `end past the file clamps`() {
        assertThat(HttpRanges.parse("bytes=90-500", 100))
            .isEqualTo(HttpRanges.Result.Partial(90, 99))
    }

    @Test
    fun `suffix range serves the last n bytes`() {
        assertThat(HttpRanges.parse("bytes=-10", 100))
            .isEqualTo(HttpRanges.Result.Partial(90, 99))
        assertThat(HttpRanges.parse("bytes=-500", 100))
            .isEqualTo(HttpRanges.Result.Partial(0, 99))
    }

    @Test
    fun `start beyond the file is unsatisfiable`() {
        assertThat(HttpRanges.parse("bytes=100-", 100))
            .isEqualTo(HttpRanges.Result.Unsatisfiable)
        assertThat(HttpRanges.parse("bytes=-5", 0))
            .isEqualTo(HttpRanges.Result.Unsatisfiable)
    }

    @Test
    fun `malformed and multi-range headers fall back to the whole file`() {
        listOf(
            "bites=0-5",
            "bytes=a-b",
            "bytes=5-2",
            "bytes=0-5,10-15",
            "bytes=",
            "bytes=-",
        ).forEach { header ->
            assertThat(HttpRanges.parse(header, 100)).isEqualTo(HttpRanges.Result.Whole)
        }
    }
}
