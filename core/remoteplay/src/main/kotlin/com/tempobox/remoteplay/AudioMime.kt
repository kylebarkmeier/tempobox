package com.tempobox.remoteplay

/**
 * MIME types for the audio formats the library scanner accepts, used in the
 * stream server's Content-Type and the DIDL-Lite protocolInfo. Renderers pick
 * decoders off this, so the mapping stays conservative.
 */
object AudioMime {

    fun forPath(path: String): String =
        when (path.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "ogg", "oga", "opus" -> "audio/ogg"
            "m4a", "mp4", "alac" -> "audio/mp4"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
}
