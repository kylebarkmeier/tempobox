package com.tempobox.playback

import com.tempobox.model.RepeatMode

/**
 * One spoken in-app command, parsed from a SpeechRecognizer utterance. The
 * grammar is pure Kotlin so every phrase mapping is unit-tested; the
 * microphone capture lives in :app (VoiceCommandActivity) and execution in
 * [PlayerConnection.execute].
 *
 * Anything that doesn't match a transport phrase but starts with "play" or
 * "shuffle" becomes a [PlaySearch] handled by [VoiceSearch], which already
 * strips those words and resolves playlist/artist/album/genre/title. Unknown
 * utterances parse to null so the UI can say what it heard instead of
 * guessing.
 */
sealed interface VoiceCommand {

    /** "play lamb of god", "shuffle road trip": resolve via [VoiceSearch]. */
    data class PlaySearch(val query: String) : VoiceCommand

    data object Resume : VoiceCommand
    data object Pause : VoiceCommand
    data object Stop : VoiceCommand
    data object Next : VoiceCommand
    data object Previous : VoiceCommand
    data class SetShuffle(val on: Boolean) : VoiceCommand
    data class SetRepeat(val mode: RepeatMode) : VoiceCommand
    data object VolumeUp : VoiceCommand
    data object VolumeDown : VoiceCommand

    /** Short confirmation for the toast shown after executing. */
    fun confirmation(): String = when (this) {
        is PlaySearch -> "Searching for “$query”"
        Resume -> "Resuming"
        Pause -> "Pausing"
        Stop -> "Stopping"
        Next -> "Next track"
        Previous -> "Previous track"
        is SetShuffle -> if (on) "Shuffle on" else "Shuffle off"
        is SetRepeat -> when (mode) {
            RepeatMode.OFF -> "Repeat off"
            RepeatMode.ALL -> "Repeat all"
            RepeatMode.ONE -> "Repeat one"
        }
        VolumeUp -> "Volume up"
        VolumeDown -> "Volume down"
    }

    companion object {

        private val PAUSE = setOf(
            "pause", "pause music", "pause the music", "pause playback", "pause it",
        )
        private val STOP = setOf(
            "stop", "stop music", "stop the music", "stop playing", "stop playback", "stop it",
        )
        private val RESUME = setOf(
            "play", "resume", "continue", "unpause", "keep playing",
            "resume music", "resume playback", "continue playing",
        )
        private val NEXT = setOf(
            "next", "skip", "next track", "next song", "skip this", "skip this track",
            "skip this song", "skip track", "skip song", "skip ahead", "skip forward",
            "play the next track", "play the next song",
        )
        private val PREVIOUS = setOf(
            "previous", "back", "go back", "previous track", "previous song",
            "last track", "last song", "play the previous track", "play the previous song",
        )
        private val SHUFFLE_ON = setOf(
            "shuffle", "shuffle on", "turn on shuffle", "turn shuffle on", "enable shuffle",
        )
        private val SHUFFLE_OFF = setOf(
            "shuffle off", "turn off shuffle", "turn shuffle off", "disable shuffle",
            "stop shuffling", "no shuffle", "stop shuffle",
        )
        private val REPEAT_ALL = setOf(
            "repeat", "repeat on", "repeat all", "repeat everything", "loop", "loop all",
            "turn on repeat", "turn repeat on",
        )
        private val REPEAT_ONE = setOf(
            "repeat one", "repeat this", "repeat this track", "repeat this song",
            "repeat track", "repeat song", "loop this", "loop this track", "loop this song",
            "loop track", "loop song",
        )
        private val REPEAT_OFF = setOf(
            "repeat off", "no repeat", "stop repeating", "turn off repeat",
            "turn repeat off", "loop off", "dont repeat",
        )
        private val VOLUME_UP = setOf(
            "volume up", "turn up the volume", "turn the volume up", "turn it up",
            "raise the volume", "increase volume", "increase the volume", "louder",
        )
        private val VOLUME_DOWN = setOf(
            "volume down", "turn down the volume", "turn the volume down", "turn it down",
            "lower the volume", "decrease volume", "decrease the volume", "quieter", "softer",
        )

        /**
         * Parses one utterance, or returns null when nothing matched. Phrase
         * matching is case-insensitive and ignores punctuation (recognizers
         * add apostrophes and the odd comma, never reliable casing). A
         * [PlaySearch] query keeps the original wording minus sentence
         * punctuation: names like "AC/DC" must reach [VoiceSearch] intact.
         */
        fun parse(utterance: String): VoiceCommand? {
            val words = utterance.lowercase()
                .replace(Regex("[^\\p{L}\\p{N} ]"), "")
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            val text = words.joinToString(" ")

            return when (text) {
                in PAUSE -> Pause
                in STOP -> Stop
                in RESUME -> Resume
                in NEXT -> Next
                in PREVIOUS -> Previous
                in SHUFFLE_ON -> SetShuffle(true)
                in SHUFFLE_OFF -> SetShuffle(false)
                in REPEAT_OFF -> SetRepeat(RepeatMode.OFF)
                in REPEAT_ONE -> SetRepeat(RepeatMode.ONE)
                in REPEAT_ALL -> SetRepeat(RepeatMode.ALL)
                in VOLUME_UP -> VolumeUp
                in VOLUME_DOWN -> VolumeDown
                else -> {
                    val queryWords = utterance
                        .replace(Regex("[.,!?;:\"“”]"), " ")
                        .trim()
                        .split(Regex("\\s+"))
                        .filter { it.isNotEmpty() }
                    when (words.first()) {
                        // "play X": VoiceSearch gets the query without the verb.
                        "play" -> PlaySearch(queryWords.drop(1).joinToString(" "))
                        // "shuffle X": keep the token; VoiceSearch strips it
                        // and sets the shuffle flag on whatever X resolves to.
                        "shuffle" -> PlaySearch(queryWords.joinToString(" "))
                        else -> null
                    }
                }
            }
        }
    }
}
