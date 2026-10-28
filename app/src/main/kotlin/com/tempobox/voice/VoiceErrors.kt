package com.tempobox.voice

import android.content.Context
import android.net.ConnectivityManager
import android.speech.SpeechRecognizer

/**
 * Honest user-facing reason for a [SpeechRecognizer] error code (logging rule:
 * the failure the user triggered reaches them with the real reason). With
 * [offline] set, the codes an unconnected phone produces (the recognizer
 * "hears" nothing without its service, or reports the network) carry the
 * likely cause, since the dialog cannot otherwise tell a quiet car from a
 * phone left in airplane mode.
 */
internal fun recognizerErrorMessage(code: Int, offline: Boolean = false): String {
    val reason = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Could not make out any words"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Did not hear anything"
        SpeechRecognizer.ERROR_AUDIO -> "Microphone error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> "The speech service could not reach its network"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy"
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        -> "Speech service error"
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition failed on this device"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        -> "The speech service does not support this language"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "The speech service is rate limiting requests"
        else -> "Speech recognition failed (error $code)"
    }
    return if (offline && code in OFFLINE_CODES) "$reason. $OFFLINE_HINT" else reason
}

/** The codes an unconnected phone's speech service answers with. */
private val OFFLINE_CODES = setOf(
    SpeechRecognizer.ERROR_NO_MATCH,
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
    SpeechRecognizer.ERROR_SERVER,
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
)

internal const val OFFLINE_HINT =
    "The phone is offline: speech recognition needs a connection, " +
        "or the offline speech pack from the phone's language settings"

/**
 * True when speech recognition has no network to use: no network is active
 * at all (airplane mode with every radio off is the usual way there). The
 * airplane setting itself is not consulted, since Wi-Fi re-enabled inside
 * airplane mode serves the speech service fine; and a network without
 * internet is not caught. Both err toward blaming the recognizer, never the
 * user.
 */
internal fun isOffline(context: Context): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
    return connectivity.activeNetwork == null
}
