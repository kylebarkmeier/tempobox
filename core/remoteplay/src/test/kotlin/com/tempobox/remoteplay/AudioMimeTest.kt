package com.tempobox.remoteplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioMimeTest {

    @Test
    fun `maps library formats case-insensitively`() {
        assertThat(AudioMime.forPath("/music/a.mp3")).isEqualTo("audio/mpeg")
        assertThat(AudioMime.forPath("/music/a.FLAC")).isEqualTo("audio/flac")
        assertThat(AudioMime.forPath("/music/a.ogg")).isEqualTo("audio/ogg")
        assertThat(AudioMime.forPath("/music/a.opus")).isEqualTo("audio/ogg")
        assertThat(AudioMime.forPath("/music/a.m4a")).isEqualTo("audio/mp4")
        assertThat(AudioMime.forPath("http://nas/track.wav")).isEqualTo("audio/wav")
    }

    @Test
    fun `unknown and missing extensions fall back to octet-stream`() {
        assertThat(AudioMime.forPath("/music/a.xyz")).isEqualTo("application/octet-stream")
        assertThat(AudioMime.forPath("/music/noext")).isEqualTo("application/octet-stream")
    }
}
