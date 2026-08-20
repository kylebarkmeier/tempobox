package com.tempobox.scrobble

import java.security.MessageDigest

/**
 * Pure helpers for the Last.fm API protocol (signature + parameter building).
 * Split from the HTTP client so signing rules are unit-testable offline.
 *
 * Docs: https://www.last.fm/api/mobileauth and /api/scrobbling
 */
object LastFmApi {
    const val BASE_URL = "https://ws.audioscrobbler.com/2.0/"

    /**
     * api_sig: md5 of all params (except `format`/`callback`) sorted by key,
     * concatenated as `keyvalue`, with the shared secret appended.
     */
    fun sign(params: Map<String, String>, secret: String): String {
        val payload = params
            .filterKeys { it != "format" && it != "callback" }
            .toSortedMap()
            .entries
            .joinToString(separator = "") { (k, v) -> "$k$v" } + secret
        return md5Hex(payload)
    }

    /** Params for auth.getMobileSession (password grant). */
    fun mobileSessionParams(username: String, password: String, apiKey: String): Map<String, String> =
        mapOf(
            "method" to "auth.getMobileSession",
            "username" to username,
            "password" to password,
            "api_key" to apiKey,
        )

    /** Params for track.updateNowPlaying. */
    fun nowPlayingParams(
        artist: String,
        track: String,
        album: String?,
        durationSec: Long?,
        apiKey: String,
        sessionKey: String,
    ): Map<String, String> = buildMap {
        put("method", "track.updateNowPlaying")
        put("artist", artist)
        put("track", track)
        album?.takeIf { it.isNotBlank() }?.let { put("album", it) }
        durationSec?.takeIf { it > 0 }?.let { put("duration", it.toString()) }
        put("api_key", apiKey)
        put("sk", sessionKey)
    }

    /** Params for track.scrobble (single track). */
    fun scrobbleParams(
        artist: String,
        track: String,
        album: String?,
        timestampSec: Long,
        durationSec: Long?,
        apiKey: String,
        sessionKey: String,
    ): Map<String, String> = buildMap {
        put("method", "track.scrobble")
        put("artist[0]", artist)
        put("track[0]", track)
        album?.takeIf { it.isNotBlank() }?.let { put("album[0]", it) }
        put("timestamp[0]", timestampSec.toString())
        durationSec?.takeIf { it > 0 }?.let { put("duration[0]", it.toString()) }
        put("api_key", apiKey)
        put("sk", sessionKey)
    }

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
