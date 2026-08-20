package com.tempobox.scrobble

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.MessageDigest

class LastFmApiTest {

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `signature is md5 of sorted key-value concatenation plus secret`() {
        val params = mapOf("b" to "2", "a" to "1", "api_key" to "KEY")
        // Sorted: a1 api_keyKEY b2 → "a1api_keyKEYb2" + secret
        assertThat(LastFmApi.sign(params, "SECRET"))
            .isEqualTo(md5("a1api_keyKEYb2SECRET"))
    }

    @Test
    fun `format and callback params are excluded from the signature`() {
        val base = mapOf("method" to "track.scrobble", "api_key" to "K")
        val withNoise = base + mapOf("format" to "json", "callback" to "cb")
        assertThat(LastFmApi.sign(withNoise, "S")).isEqualTo(LastFmApi.sign(base, "S"))
    }

    @Test
    fun `scrobble params use the indexed batch form`() {
        val params = LastFmApi.scrobbleParams(
            artist = "Artist", track = "Track", album = "Album",
            timestampSec = 1_700_000_000, durationSec = 200,
            apiKey = "K", sessionKey = "SK",
        )
        assertThat(params["method"]).isEqualTo("track.scrobble")
        assertThat(params["artist[0]"]).isEqualTo("Artist")
        assertThat(params["track[0]"]).isEqualTo("Track")
        assertThat(params["album[0]"]).isEqualTo("Album")
        assertThat(params["timestamp[0]"]).isEqualTo("1700000000")
        assertThat(params["sk"]).isEqualTo("SK")
    }

    @Test
    fun `blank album and zero duration are omitted`() {
        val params = LastFmApi.nowPlayingParams(
            artist = "A", track = "T", album = "", durationSec = 0,
            apiKey = "K", sessionKey = "SK",
        )
        assertThat(params).doesNotContainKey("album")
        assertThat(params).doesNotContainKey("duration")
    }

    @Test
    fun `mobile session params carry credentials`() {
        val params = LastFmApi.mobileSessionParams("user", "pass", "K")
        assertThat(params["method"]).isEqualTo("auth.getMobileSession")
        assertThat(params["username"]).isEqualTo("user")
        assertThat(params["password"]).isEqualTo("pass")
    }
}
