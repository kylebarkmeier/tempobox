package com.tempobox.playback

import com.google.common.truth.Truth.assertThat
import com.tempobox.model.RepeatMode
import org.junit.Test

class VoiceCommandTest {

    // ------------------------------------------------------------------ transport

    @Test
    fun `pause phrases parse to Pause`() {
        listOf("pause", "Pause the music", "pause playback", "pause it").forEach {
            assertThat(VoiceCommand.parse(it)).isEqualTo(VoiceCommand.Pause)
        }
    }

    @Test
    fun `stop phrases parse to Stop`() {
        listOf("stop", "stop playing", "Stop the music!").forEach {
            assertThat(VoiceCommand.parse(it)).isEqualTo(VoiceCommand.Stop)
        }
    }

    @Test
    fun `bare play and resume phrases parse to Resume`() {
        listOf("play", "resume", "continue", "keep playing", "unpause").forEach {
            assertThat(VoiceCommand.parse(it)).isEqualTo(VoiceCommand.Resume)
        }
    }

    @Test
    fun `next phrases parse to Next`() {
        listOf("next", "skip", "next track", "Skip this song", "skip forward").forEach {
            assertThat(VoiceCommand.parse(it)).isEqualTo(VoiceCommand.Next)
        }
    }

    @Test
    fun `previous phrases parse to Previous`() {
        listOf("previous", "go back", "previous song", "last track").forEach {
            assertThat(VoiceCommand.parse(it)).isEqualTo(VoiceCommand.Previous)
        }
    }

    // ------------------------------------------------------------------ toggles

    @Test
    fun `shuffle phrases parse to SetShuffle`() {
        listOf("shuffle", "shuffle on", "turn on shuffle").forEach {
            assertThat(VoiceCommand.parse(it)).isEqualTo(VoiceCommand.SetShuffle(true))
        }
        listOf("shuffle off", "stop shuffling", "turn shuffle off", "no shuffle").forEach {
            assertThat(VoiceCommand.parse(it)).isEqualTo(VoiceCommand.SetShuffle(false))
        }
    }

    @Test
    fun `repeat phrases parse to SetRepeat`() {
        listOf("repeat", "repeat all", "loop", "turn on repeat").forEach {
            assertThat(VoiceCommand.parse(it)).isEqualTo(VoiceCommand.SetRepeat(RepeatMode.ALL))
        }
        listOf("repeat one", "repeat this song", "loop this track").forEach {
            assertThat(VoiceCommand.parse(it)).isEqualTo(VoiceCommand.SetRepeat(RepeatMode.ONE))
        }
        listOf("repeat off", "stop repeating", "don't repeat", "loop off").forEach {
            assertThat(VoiceCommand.parse(it)).isEqualTo(VoiceCommand.SetRepeat(RepeatMode.OFF))
        }
    }

    @Test
    fun `volume phrases parse to VolumeUp and VolumeDown`() {
        listOf("volume up", "louder", "turn it up", "raise the volume").forEach {
            assertThat(VoiceCommand.parse(it)).isEqualTo(VoiceCommand.VolumeUp)
        }
        listOf("volume down", "quieter", "turn it down", "lower the volume").forEach {
            assertThat(VoiceCommand.parse(it)).isEqualTo(VoiceCommand.VolumeDown)
        }
    }

    // ------------------------------------------------------------------ search

    @Test
    fun `play with a query parses to PlaySearch without the verb`() {
        assertThat(VoiceCommand.parse("play lamb of god"))
            .isEqualTo(VoiceCommand.PlaySearch("lamb of god"))
        assertThat(VoiceCommand.parse("Play Back in Black"))
            .isEqualTo(VoiceCommand.PlaySearch("Back in Black"))
    }

    @Test
    fun `shuffle with a query keeps the token for VoiceSearch`() {
        // VoiceSearch strips the "shuffle" token itself and sets the flag.
        assertThat(VoiceCommand.parse("shuffle lamb of god"))
            .isEqualTo(VoiceCommand.PlaySearch("shuffle lamb of god"))
    }

    @Test
    fun `punctuation and casing are ignored for phrases but kept in queries`() {
        assertThat(VoiceCommand.parse("Next!")).isEqualTo(VoiceCommand.Next)
        // Sentence punctuation goes; name punctuation stays for the resolver.
        assertThat(VoiceCommand.parse("play AC/DC!"))
            .isEqualTo(VoiceCommand.PlaySearch("AC/DC"))
        assertThat(VoiceCommand.parse("play Guns N' Roses"))
            .isEqualTo(VoiceCommand.PlaySearch("Guns N' Roses"))
    }

    @Test
    fun `unknown utterances parse to null`() {
        listOf("", "   ", "what is the weather", "open settings").forEach {
            assertThat(VoiceCommand.parse(it)).isNull()
        }
    }

    // ------------------------------------------------------------------ confirmations

    @Test
    fun `confirmations name the action`() {
        assertThat(VoiceCommand.PlaySearch("x").confirmation()).contains("x")
        assertThat(VoiceCommand.Pause.confirmation()).isEqualTo("Pausing")
        assertThat(VoiceCommand.SetShuffle(true).confirmation()).isEqualTo("Shuffle on")
        assertThat(VoiceCommand.SetRepeat(RepeatMode.ONE).confirmation()).isEqualTo("Repeat one")
    }
}
