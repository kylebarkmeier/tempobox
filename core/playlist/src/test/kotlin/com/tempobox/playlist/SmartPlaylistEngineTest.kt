package com.tempobox.playlist

import com.google.common.truth.Truth.assertThat
import com.tempobox.model.RuleField
import com.tempobox.model.RuleOp
import com.tempobox.model.SmartRule
import com.tempobox.model.Track
import org.junit.Test

class SmartPlaylistEngineTest {

    private val engine = SmartPlaylistEngine()

    private fun track(
        title: String,
        artist: String = "Artist",
        album: String = "Album",
        genre: String = "Rock",
        year: Int? = 2000,
        rating: Int = 0,
        disc: Int? = 1,
        no: Int? = 1,
    ) = Track(
        filePath = "/m/$artist-$album-$title.mp3", title = title, artist = artist,
        albumArtist = artist, album = album, genre = genre, year = year,
        rating = rating, discNumber = disc, trackNumber = no,
    )

    @Test
    fun `evaluate filters by the rule`() {
        val rock = track("R", genre = "Rock")
        val jazz = track("J", genre = "Jazz")
        val result = engine.evaluate(
            SmartRule.Condition(RuleField.GENRE, RuleOp.IS, "Rock"),
            listOf(rock, jazz),
        )
        assertThat(result).containsExactly(rock)
    }

    @Test
    fun `evaluate orders artist, album, disc, track`() {
        val t1 = track("Late", artist = "Zed", no = 1)
        val t2 = track("First", artist = "Abba", album = "A1", no = 1)
        val t3 = track("Second", artist = "Abba", album = "A1", no = 2)
        val t4 = track("OtherAlbum", artist = "Abba", album = "B2", no = 1)
        val result = engine.evaluate(
            SmartRule.Condition(RuleField.GENRE, RuleOp.IS, "Rock"),
            listOf(t1, t4, t3, t2),
        )
        assertThat(result).containsExactly(t2, t3, t4, t1).inOrder()
    }

    @Test
    fun `new matching tracks appear on re-evaluation`() {
        val rule = SmartRule.Condition(RuleField.RATING, RuleOp.GREATER_THAN, "3")
        val initial = listOf(track("A", rating = 5))
        assertThat(engine.evaluate(rule, initial)).hasSize(1)

        // Library grew — the auto playlist picks the new track up automatically.
        val grown = initial + track("B", rating = 4) + track("C", rating = 2)
        assertThat(engine.evaluate(rule, grown).map { it.title }).containsExactly("A", "B")
    }

    @Test
    fun `describe renders conditions and boolean combinations`() {
        val rule = SmartRule.AllOf(
            listOf(
                SmartRule.AnyOf(
                    listOf(
                        SmartRule.Condition(RuleField.GENRE, RuleOp.IS, "Rock"),
                        SmartRule.Condition(RuleField.GENRE, RuleOp.IS, "Metal"),
                    ),
                ),
                SmartRule.Condition(RuleField.YEAR, RuleOp.GREATER_THAN, "1990"),
            ),
        )
        assertThat(engine.describe(rule))
            .isEqualTo("(Genre is Rock OR Genre is Metal) AND Year > 1990")
    }
}
