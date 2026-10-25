package com.tempobox.voice

import android.speech.SpeechRecognizer
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoiceErrorsTest {

    @Test
    fun `known codes get a specific reason`() {
        assertThat(recognizerErrorMessage(SpeechRecognizer.ERROR_NO_MATCH))
            .isEqualTo("Could not make out any words")
        assertThat(recognizerErrorMessage(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
            .isEqualTo("Did not hear anything")
        assertThat(recognizerErrorMessage(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
            .isEqualTo("Microphone permission is missing")
        assertThat(recognizerErrorMessage(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
            .isEqualTo("The speech recognizer is busy")
    }

    @Test
    fun `unknown codes still name the code instead of a guessed reason`() {
        assertThat(recognizerErrorMessage(9999)).isEqualTo("Speech recognition failed (error 9999)")
    }
}
