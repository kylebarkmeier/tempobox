package com.tempobox.playback

import com.tempobox.model.RepeatMode
import com.tempobox.model.searchKeyword

/**
 * One spoken in-app command, parsed from a SpeechRecognizer utterance. The
 * grammar is pure Kotlin so every phrase mapping is unit-tested; the
 * microphone capture lives in :app (VoiceCommandActivity) and execution in
 * [VoiceActions] ([Immediate] commands go straight to
 * [PlayerConnection.execute], the rest resolve names through [VoiceSearch]).
 *
 * Anything that doesn't match a transport phrase or a queue/playlist
 * sentence but starts with "play", "shuffle" or "put on" becomes a
 * [PlaySearch] handled by [VoiceSearch], which strips those words and
 * resolves playlist/artist/album/genre/title. Unknown utterances parse to
 * null so the UI can say what it heard instead of guessing; so do sentences
 * whose object is only a pronoun or filler ("queue up", "play the next"),
 * because searching the library for "the" would add something random.
 */
sealed interface VoiceCommand {

    /** Commands that need no library lookup; [PlayerConnection.execute] runs them. */
    sealed interface Immediate : VoiceCommand {

        /** Toast text: these cannot fail once the player is reached. */
        fun confirmation(): String = when (this) {
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
    }

    /** "play lamb of god", "shuffle road trip", "put on some metal": resolve via [VoiceSearch]. */
    data class PlaySearch(val query: String) : VoiceCommand

    /** "add lamb of god to the queue", "queue up back in black": append the match. */
    data class AddToQueue(val query: String) : VoiceCommand

    /** "play thunderstruck next", "queue back in black next": insert after the current track. */
    data class PlayNext(val query: String) : VoiceCommand

    /**
     * "add thunderstruck to playlist road trip", "add this to road trip".
     * A null [query] means the playing track. [playlist] is the spoken name
     * with its "my"/"the"/"playlist" words still attached; [VoiceSearch.findPlaylist]
     * strips them, so a playlist literally named "My Playlist" stays reachable.
     */
    data class AddToPlaylist(val query: String?, val playlist: String) : VoiceCommand

    data object Resume : Immediate
    data object Pause : Immediate
    data object Stop : Immediate
    data object Next : Immediate
    data object Previous : Immediate
    data class SetShuffle(val on: Boolean) : Immediate
    data class SetRepeat(val mode: RepeatMode) : Immediate
    data object VolumeUp : Immediate
    data object VolumeDown : Immediate

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
            "next", "skip", "next track", "next song", "next one", "the next one",
            "skip this", "skip this track", "skip this song", "skip track", "skip song",
            "skip ahead", "skip forward", "skip to the next track", "skip to the next song",
            "play next", "play the next", "play next track", "play next song",
            "play the next track", "play the next song", "play the next one",
        )
        private val PREVIOUS = setOf(
            "previous", "back", "go back", "previous track", "previous song", "previous one",
            "the previous one", "last track", "last song", "go back a track", "go back a song",
            "play previous track", "play previous song", "play the previous",
            "play the previous track", "play the previous song", "play the previous one",
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

        /** Verbs that open an "add X to Y" sentence. */
        private val ADD_VERBS = setOf("add", "put", "save")

        /** Recognizers spell the queue as "queue", "Q" or "cue". */
        private val QUEUE_VERBS = setOf("queue", "q", "cue")

        private val TARGET_PREPOSITIONS = setOf("to", "in", "into", "onto", "on")

        /** Without the word "playlist", only these mark a playlist target ("put it on shuffle" is not one). */
        private val PLAYLIST_PREPOSITIONS = setOf("to", "into")

        private val ARTICLES = setOf("the", "my")

        /** Compared after a leading article is dropped. */
        private val QUEUE_TARGETS = setOf(
            "queue", "play queue", "playing queue", "playback queue", "q", "cue",
        )

        /** Subjects that mean the playing track. */
        private val THIS_PHRASES = setOf(
            "this", "this song", "this track", "this one", "it", "that",
            "current song", "current track", "the current song", "the current track",
            "whats playing", "what is playing", "the playing song", "the playing track",
            "the song playing", "the track playing", "the song thats playing",
            "the track thats playing",
        )

        /** An object made only of these is not a name worth searching for. */
        private val STOPWORDS = setOf("the", "a", "an", "some", "up", "it", "that", "this")

        private val SENTENCE_PUNCTUATION = Regex("[.,!?;:\"“”]")
        private val WHITESPACE = Regex("\\s+")

        /** One spoken token with its keyword form; the key is empty for "&", "+" and the like. */
        private class Word(val spoken: String, val key: String)

        /**
         * Parses one utterance, or returns null when nothing matched. Phrase
         * matching is case-insensitive and ignores punctuation (recognizers
         * add apostrophes and the odd comma, never reliable casing). Query
         * text keeps the original wording minus sentence punctuation: names
         * like "AC/DC" and "Simon & Garfunkel" must reach [VoiceSearch] intact.
         */
        fun parse(utterance: String): VoiceCommand? {
            val words = utterance
                .replace(SENTENCE_PUNCTUATION, " ")
                .trim()
                .split(WHITESPACE)
                .filter { it.isNotEmpty() }
                .map { Word(it, it.searchKeyword()) }
            val keys = words.map { it.key }
            if (keys.all { it.isEmpty() }) return null
            val text = keys.filter { it.isNotEmpty() }.joinToString(" ")

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
                else -> when (val sentence = parseSentence(words)) {
                    is Sentence.Of -> sentence.command
                    // A recognized shape with no usable name ("play this
                    // next") must not become a search for those words.
                    Sentence.Rejected -> null
                    Sentence.None -> when (keys.first()) {
                        // "play X": VoiceSearch gets the query without the verb.
                        "play" -> PlaySearch(words.name(1, words.size) ?: return null)
                        // "shuffle X": keep the token; VoiceSearch strips it
                        // and sets the shuffle flag on whatever X resolves to.
                        "shuffle" -> PlaySearch(words.spoken(0, words.size))
                        else -> null
                    }
                }
            }
        }

        private sealed interface Sentence {
            data class Of(val command: VoiceCommand) : Sentence
            data object Rejected : Sentence
            data object None : Sentence
        }

        private fun List<Word>.spoken(from: Int, to: Int): String =
            subList(from, to).joinToString(" ") { it.spoken }

        private fun List<Word>.keys(from: Int, to: Int): List<String> =
            subList(from, to).map { it.key }.filter { it.isNotEmpty() }

        /** True when the words in the range mean the playing track. */
        private fun List<Word>.isThis(from: Int, to: Int): Boolean =
            keys(from, to).joinToString(" ") in THIS_PHRASES

        /** The range as a searchable name, or null when it is empty, a pronoun, or filler only. */
        private fun List<Word>.name(from: Int, to: Int): String? {
            val ks = keys(from, to)
            if (ks.isEmpty() || isThis(from, to) || ks.all { it in STOPWORDS }) return null
            return spoken(from, to)
        }

        private fun List<Word>.isQueueTarget(from: Int, to: Int): Boolean =
            keys(from, to).dropWhile { it in ARTICLES }.joinToString(" ") in QUEUE_TARGETS

        /**
         * Queue and playlist sentences. [Sentence.None] when the words don't
         * form one; [Sentence.Rejected] when they do but name nothing usable.
         */
        private fun parseSentence(words: List<Word>): Sentence {
            val keys = words.map { it.key }
            val n = words.size
            val first = keys.first()
            val last = keys.last()
            fun of(command: VoiceCommand?): Sentence = command?.let { Sentence.Of(it) } ?: Sentence.Rejected

            // "queue X", "queue up X", "queue X next".
            if (first in QUEUE_VERBS) {
                val start = if (keys.getOrNull(1) == "up") 2 else 1
                if (start >= n) return Sentence.Rejected
                return if (last == "next") {
                    of(words.name(start, n - 1)?.let { PlayNext(it) })
                } else {
                    of(words.name(start, n)?.let { AddToQueue(it) })
                }
            }

            // "play X next", "add X next", "put X in the queue next". A title
            // that itself ends in "next" is the price of this sentence shape.
            if (last == "next" && n >= 3 && (first == "play" || first in ADD_VERBS)) {
                var end = n - 1
                val split = targetSplit(words, end)
                if (split != null && words.isQueueTarget(split + 1, end)) end = split
                return of(words.name(1, end)?.let { PlayNext(it) })
            }

            if (first !in ADD_VERBS || n < 3) return Sentence.None
            val split = targetSplit(words, n) ?: return Sentence.None
            val preposition = keys[split]
            val targetFrom = split + 1

            if (words.isQueueTarget(targetFrom, n)) {
                return of(words.name(1, split)?.let { AddToQueue(it) })
            }
            if (words.keys(1, split).isEmpty()) {
                // "put on X" is the everyday way to ask for X; any other
                // subject-less sentence is not a command.
                return if (first == "put" && preposition == "on") {
                    Sentence.Of(PlaySearch(words.spoken(targetFrom, n)))
                } else {
                    Sentence.Rejected
                }
            }
            val explicitPlaylist = "playlist" in keys.subList(targetFrom, n)
            if (!explicitPlaylist && preposition !in PLAYLIST_PREPOSITIONS) return Sentence.Rejected
            val playlist = words.spoken(targetFrom, n)
            return if (words.isThis(1, split)) {
                Sentence.Of(AddToPlaylist(query = null, playlist = playlist))
            } else {
                of(words.name(1, split)?.let { AddToPlaylist(query = it, playlist = playlist) })
            }
        }

        /**
         * Index of the preposition that separates subject from target, among
         * words[1 until end]. Names can contain "to" ("Back to Black"), so a
         * queue target wins outright, then an explicit "playlist" right after
         * the preposition, else the last preposition.
         */
        private fun targetSplit(words: List<Word>, end: Int): Int? {
            val keys = words.map { it.key }
            val candidates = (1 until end - 1).filter { keys[it] in TARGET_PREPOSITIONS }
            return candidates.lastOrNull { words.isQueueTarget(it + 1, end) }
                ?: candidates.firstOrNull { i ->
                    keys[i + 1] == "playlist" ||
                        (keys[i + 1] in ARTICLES && keys.getOrNull(i + 2) == "playlist")
                }
                ?: candidates.lastOrNull()
        }
    }
}
