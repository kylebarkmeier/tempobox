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
        listOf(
            "next", "skip", "next track", "Skip this song", "skip forward", "play next",
            "play the next", "play next track", "play next song", "play the next one",
            "skip to the next track",
        ).forEach {
            assertThat(VoiceCommand.parse(it)).isEqualTo(VoiceCommand.Next)
        }
    }

    @Test
    fun `previous phrases parse to Previous`() {
        listOf(
            "previous", "go back", "previous song", "last track", "play previous track",
            "play the previous one",
        ).forEach {
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
        assertThat(VoiceCommand.parse("play everything"))
            .isEqualTo(VoiceCommand.PlaySearch("everything"))
    }

    @Test
    fun `shuffle with a query keeps the token for VoiceSearch`() {
        // VoiceSearch strips the "shuffle" token itself and sets the flag.
        assertThat(VoiceCommand.parse("shuffle lamb of god"))
            .isEqualTo(VoiceCommand.PlaySearch("shuffle lamb of god"))
        assertThat(VoiceCommand.parse("shuffle everything"))
            .isEqualTo(VoiceCommand.PlaySearch("shuffle everything"))
    }

    @Test
    fun `put on X is a play request, never a playlist edit`() {
        assertThat(VoiceCommand.parse("put on road trip"))
            .isEqualTo(VoiceCommand.PlaySearch("road trip"))
        assertThat(VoiceCommand.parse("put on my road trip playlist"))
            .isEqualTo(VoiceCommand.PlaySearch("my road trip playlist"))
        assertThat(VoiceCommand.parse("put on some lamb of god"))
            .isEqualTo(VoiceCommand.PlaySearch("some lamb of god"))
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
    fun `symbol-only words survive in queries`() {
        assertThat(VoiceCommand.parse("play Simon & Garfunkel"))
            .isEqualTo(VoiceCommand.PlaySearch("Simon & Garfunkel"))
        assertThat(VoiceCommand.parse("add Simon & Garfunkel to the queue"))
            .isEqualTo(VoiceCommand.AddToQueue("Simon & Garfunkel"))
        assertThat(VoiceCommand.parse("&")).isNull()
    }

    @Test
    fun `unknown utterances parse to null`() {
        listOf(
            "", "   ", "what is the weather", "open settings", "add", "add to the queue",
            "save this song", "put it on shuffle", "put it on repeat", "put on the", "put on it",
            "put on some", "shuffle the", "shuffle it",
        ).forEach {
            assertThat(VoiceCommand.parse(it)).isNull()
        }
    }

    // ------------------------------------------------------------------ queue

    @Test
    fun `add X to the queue parses to AddToQueue with the spoken name`() {
        assertThat(VoiceCommand.parse("add lamb of god to the queue"))
            .isEqualTo(VoiceCommand.AddToQueue("lamb of god"))
        assertThat(VoiceCommand.parse("Add Back in Black to queue"))
            .isEqualTo(VoiceCommand.AddToQueue("Back in Black"))
        assertThat(VoiceCommand.parse("put AC/DC in the queue"))
            .isEqualTo(VoiceCommand.AddToQueue("AC/DC"))
        assertThat(VoiceCommand.parse("add songs by lamb of god to my play queue"))
            .isEqualTo(VoiceCommand.AddToQueue("songs by lamb of god"))
    }

    @Test
    fun `recognizer spellings of queue are accepted`() {
        assertThat(VoiceCommand.parse("add lamb of god to the Q"))
            .isEqualTo(VoiceCommand.AddToQueue("lamb of god"))
        assertThat(VoiceCommand.parse("add lamb of god to the cue"))
            .isEqualTo(VoiceCommand.AddToQueue("lamb of god"))
        assertThat(VoiceCommand.parse("Q lamb of god"))
            .isEqualTo(VoiceCommand.AddToQueue("lamb of god"))
    }

    @Test
    fun `queue up X parses to AddToQueue`() {
        assertThat(VoiceCommand.parse("queue lamb of god"))
            .isEqualTo(VoiceCommand.AddToQueue("lamb of god"))
        assertThat(VoiceCommand.parse("queue up Back in Black"))
            .isEqualTo(VoiceCommand.AddToQueue("Back in Black"))
    }

    @Test
    fun `queue and next sentences without a real name are not commands`() {
        listOf(
            "queue", "queue up", "queue next", "queue up next", "add this to the queue",
            "play this next", "queue this next", "add the to the queue", "play this", "play it",
        ).forEach {
            assertThat(VoiceCommand.parse(it)).isNull()
        }
    }

    @Test
    fun `play X next and queue X next parse to PlayNext`() {
        assertThat(VoiceCommand.parse("play Thunderstruck next"))
            .isEqualTo(VoiceCommand.PlayNext("Thunderstruck"))
        assertThat(VoiceCommand.parse("queue thunderstruck next"))
            .isEqualTo(VoiceCommand.PlayNext("thunderstruck"))
        assertThat(VoiceCommand.parse("add lamb of god next"))
            .isEqualTo(VoiceCommand.PlayNext("lamb of god"))
        assertThat(VoiceCommand.parse("put lamb of god next"))
            .isEqualTo(VoiceCommand.PlayNext("lamb of god"))
        // A queue target before "next" adds nothing to the name.
        assertThat(VoiceCommand.parse("add lamb of god to the queue next"))
            .isEqualTo(VoiceCommand.PlayNext("lamb of god"))
    }

    // ------------------------------------------------------------------ playlists

    @Test
    fun `add X to playlist Y keeps the spoken playlist words for the resolver`() {
        assertThat(VoiceCommand.parse("add Thunderstruck to playlist road trip"))
            .isEqualTo(VoiceCommand.AddToPlaylist("Thunderstruck", "playlist road trip"))
        assertThat(VoiceCommand.parse("add thunderstruck to the playlist road trip"))
            .isEqualTo(VoiceCommand.AddToPlaylist("thunderstruck", "the playlist road trip"))
        assertThat(VoiceCommand.parse("add thunderstruck to my road trip playlist"))
            .isEqualTo(VoiceCommand.AddToPlaylist("thunderstruck", "my road trip playlist"))
        assertThat(VoiceCommand.parse("add thunderstruck to road trip"))
            .isEqualTo(VoiceCommand.AddToPlaylist("thunderstruck", "road trip"))
        assertThat(VoiceCommand.parse("save thunderstruck to road trip"))
            .isEqualTo(VoiceCommand.AddToPlaylist("thunderstruck", "road trip"))
        assertThat(VoiceCommand.parse("add this to my playlist"))
            .isEqualTo(VoiceCommand.AddToPlaylist(null, "my playlist"))
    }

    @Test
    fun `add this to Y means the playing track`() {
        listOf(
            "add this to road trip",
            "add this song to playlist road trip",
            "add the current track to my road trip playlist",
            "put what's playing in the road trip playlist",
        ).forEach {
            val command = VoiceCommand.parse(it)
            assertThat(command).isInstanceOf(VoiceCommand.AddToPlaylist::class.java)
            assertThat((command as VoiceCommand.AddToPlaylist).query).isNull()
        }
    }

    @Test
    fun `a playlist target without the word playlist needs to or into`() {
        // "put X on Y" and "put it on Y" describe playback, not a playlist.
        assertThat(VoiceCommand.parse("put thunderstruck on road trip")).isNull()
        assertThat(VoiceCommand.parse("add this to the end")).isNotNull()
        assertThat(VoiceCommand.parse("put it in road trip")).isNull()
        assertThat(VoiceCommand.parse("put it in the road trip playlist"))
            .isEqualTo(VoiceCommand.AddToPlaylist(null, "the road trip playlist"))
    }

    @Test
    fun `names containing to split at the last preposition unless a playlist or queue is named`() {
        assertThat(VoiceCommand.parse("add back to black to road trip"))
            .isEqualTo(VoiceCommand.AddToPlaylist("back to black", "road trip"))
        assertThat(VoiceCommand.parse("add back to black to the queue"))
            .isEqualTo(VoiceCommand.AddToQueue("back to black"))
        assertThat(VoiceCommand.parse("add thunderstruck to the playlist back to black"))
            .isEqualTo(VoiceCommand.AddToPlaylist("thunderstruck", "the playlist back to black"))
        // A queue target at the end wins over a playlist mentioned in the subject.
        assertThat(VoiceCommand.parse("add the songs from playlist road trip to the queue"))
            .isEqualTo(VoiceCommand.AddToQueue("the songs from playlist road trip"))
    }

    // ------------------------------------------------------------------ confirmations

    @Test
    fun `immediate commands have confirmations that name the action`() {
        assertThat(VoiceCommand.Pause.confirmation()).isEqualTo("Pausing")
        assertThat(VoiceCommand.SetShuffle(true).confirmation()).isEqualTo("Shuffle on")
        assertThat(VoiceCommand.SetRepeat(RepeatMode.ONE).confirmation()).isEqualTo("Repeat one")
        assertThat(VoiceCommand.VolumeDown.confirmation()).isEqualTo("Volume down")
    }
}
