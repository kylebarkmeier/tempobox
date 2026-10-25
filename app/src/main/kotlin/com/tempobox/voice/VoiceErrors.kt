package com.tempobox.voice

import android.speech.SpeechRecognizer

/**
 * Honest user-facing reason for a [SpeechRecognizer] error code (logging rule:
 * the failure the user triggered reaches them with the real reason).
 */
internal fun recognizerErrorMessage(code: Int): String = when (code) {
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
