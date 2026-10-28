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

    @Test
    fun `offline adds the likely cause to the codes an unconnected phone produces`() {
        assertThat(recognizerErrorMessage(SpeechRecognizer.ERROR_NO_MATCH, offline = true))
            .isEqualTo("Could not make out any words. $OFFLINE_HINT")
        assertThat(recognizerErrorMessage(SpeechRecognizer.ERROR_NETWORK, offline = true))
            .isEqualTo("The speech service could not reach its network. $OFFLINE_HINT")
        assertThat(recognizerErrorMessage(SpeechRecognizer.ERROR_SERVER, offline = true))
            .endsWith(OFFLINE_HINT)
    }

    @Test
    fun `offline says nothing for codes the network cannot explain`() {
        assertThat(recognizerErrorMessage(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, offline = true))
            .isEqualTo("Did not hear anything")
        assertThat(recognizerErrorMessage(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, offline = true))
            .isEqualTo("Microphone permission is missing")
        assertThat(recognizerErrorMessage(SpeechRecognizer.ERROR_NO_MATCH, offline = false))
            .isEqualTo("Could not make out any words")
    }
}
