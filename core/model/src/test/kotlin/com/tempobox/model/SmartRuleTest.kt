package com.tempobox.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Exhaustive coverage of the smart-playlist rule engine (pure functions). */
class SmartRuleTest {

    private fun track(
        title: String = "T",
        artist: String = "Artist",
        albumArtist: String = "Album Artist",
        album: String = "LP",
        genre: String = "Rock",
        year: Int? = 2000,
        rating: Int = 3,
        trackNumber: Int? = 4,
        discNumber: Int? = 1,
    ) = Track(
        filePath = "/music/t.mp3",
        title = title,
        artist = artist,
        albumArtist = albumArtist,
        album = album,
        genre = genre,
        year = year,
        rating = rating,
        trackNumber = trackNumber,
        discNumber = discNumber,
    )

    // ------------------------------------------------------------ string fields

    @Test
    fun `genre IS matches case-insensitively`() {
        val rule = SmartRule.Condition(RuleField.GENRE, RuleOp.IS, "rock")
        assertThat(rule.matches(track(genre = "Rock"))).isTrue()
        assertThat(rule.matches(track(genre = "Jazz"))).isFalse()
    }

    @Test
    fun `genre IS_NOT inverts`() {
        val rule = SmartRule.Condition(RuleField.GENRE, RuleOp.IS_NOT, "Rock")
        assertThat(rule.matches(track(genre = "Rock"))).isFalse()
        assertThat(rule.matches(track(genre = "Jazz"))).isTrue()
    }

    @Test
    fun `CONTAINS does substring matching`() {
        val rule = SmartRule.Condition(RuleField.ARTIST, RuleOp.CONTAINS, "beat")
        assertThat(rule.matches(track(artist = "The Beatles"))).isTrue()
        assertThat(rule.matches(track(artist = "Oasis"))).isFalse()
    }

    @Test
    fun `album artist falls back to artist when blank`() {
        val rule = SmartRule.Condition(RuleField.ALBUM_ARTIST, RuleOp.IS, "Solo Artist")
        assertThat(rule.matches(track(artist = "Solo Artist", albumArtist = ""))).isTrue()
    }

    @Test
    fun `blank genre matches the unknown-genre placeholder`() {
        val rule = SmartRule.Condition(RuleField.GENRE, RuleOp.IS, Track.UNKNOWN_GENRE)
        assertThat(rule.matches(track(genre = ""))).isTrue()
    }

    @Test
    fun `title matching covers IS and CONTAINS`() {
        assertThat(
            SmartRule.Condition(RuleField.TITLE, RuleOp.IS, "paranoid")
                .matches(track(title = "Paranoid")),
        ).isTrue()
        assertThat(
            SmartRule.Condition(RuleField.TITLE, RuleOp.CONTAINS, "rano")
                .matches(track(title = "Paranoid")),
        ).isTrue()
        assertThat(
            SmartRule.Condition(RuleField.TITLE, RuleOp.IS, "Paranoid")
                .matches(track(title = "War Pigs")),
        ).isFalse()
    }

    @Test
    fun `blank album matches the unknown-album placeholder`() {
        val rule = SmartRule.Condition(RuleField.ALBUM, RuleOp.IS, Track.UNKNOWN_ALBUM)
        assertThat(rule.matches(track(album = ""))).isTrue()
        assertThat(
            SmartRule.Condition(RuleField.ALBUM, RuleOp.CONTAINS, "lp").matches(track(album = "LP")),
        ).isTrue()
    }

    @Test
    fun `ordering operators are not defined for string fields`() {
        val rule = SmartRule.Condition(RuleField.GENRE, RuleOp.GREATER_THAN, "Rock")
        assertThat(rule.matches(track(genre = "Rock"))).isFalse()
    }

    // ------------------------------------------------------------ numeric fields

    @Test
    fun `year greater-than and less-than`() {
        assertThat(
            SmartRule.Condition(RuleField.YEAR, RuleOp.GREATER_THAN, "1990")
                .matches(track(year = 1991)),
        ).isTrue()
        assertThat(
            SmartRule.Condition(RuleField.YEAR, RuleOp.GREATER_THAN, "1990")
                .matches(track(year = 1990)),
        ).isFalse()
        assertThat(
            SmartRule.Condition(RuleField.YEAR, RuleOp.LESS_THAN, "1990")
                .matches(track(year = 1989)),
        ).isTrue()
    }

    @Test
    fun `rating comparisons`() {
        assertThat(
            SmartRule.Condition(RuleField.RATING, RuleOp.GREATER_THAN, "3")
                .matches(track(rating = 4)),
        ).isTrue()
        assertThat(
            SmartRule.Condition(RuleField.RATING, RuleOp.IS, "5")
                .matches(track(rating = 5)),
        ).isTrue()
        assertThat(
            SmartRule.Condition(RuleField.RATING, RuleOp.LESS_THAN, "2")
                .matches(track(rating = 3)),
        ).isFalse()
    }

    @Test
    fun `missing year fails ordered comparisons but passes IS_NOT`() {
        val absent = track(year = null)
        assertThat(
            SmartRule.Condition(RuleField.YEAR, RuleOp.GREATER_THAN, "1990").matches(absent),
        ).isFalse()
        assertThat(
            SmartRule.Condition(RuleField.YEAR, RuleOp.IS_NOT, "1990").matches(absent),
        ).isTrue()
    }

    @Test
    fun `track and disc numbers compare numerically and honor absence`() {
        assertThat(
            SmartRule.Condition(RuleField.TRACK_NUMBER, RuleOp.LESS_THAN, "5")
                .matches(track(trackNumber = 4)),
        ).isTrue()
        assertThat(
            SmartRule.Condition(RuleField.DISC_NUMBER, RuleOp.IS, "2")
                .matches(track(discNumber = 1)),
        ).isFalse()
        val untagged = track(trackNumber = null, discNumber = null)
        assertThat(
            SmartRule.Condition(RuleField.TRACK_NUMBER, RuleOp.GREATER_THAN, "0").matches(untagged),
        ).isFalse()
        assertThat(
            SmartRule.Condition(RuleField.DISC_NUMBER, RuleOp.IS_NOT, "1").matches(untagged),
        ).isTrue()
    }

    @Test
    fun `non-numeric value never matches numeric fields`() {
        val rule = SmartRule.Condition(RuleField.YEAR, RuleOp.IS, "not a number")
        assertThat(rule.matches(track())).isFalse()
    }

    // ------------------------------------------------------------ boolean combos

    @Test
    fun `AllOf requires every condition`() {
        val rule = SmartRule.AllOf(
            listOf(
                SmartRule.Condition(RuleField.GENRE, RuleOp.IS, "Rock"),
                SmartRule.Condition(RuleField.YEAR, RuleOp.GREATER_THAN, "1995"),
            ),
        )
        assertThat(rule.matches(track(genre = "Rock", year = 2000))).isTrue()
        assertThat(rule.matches(track(genre = "Rock", year = 1990))).isFalse()
        assertThat(rule.matches(track(genre = "Jazz", year = 2000))).isFalse()
    }

    @Test
    fun `AnyOf requires at least one condition`() {
        val rule = SmartRule.AnyOf(
            listOf(
                SmartRule.Condition(RuleField.GENRE, RuleOp.IS, "Rock"),
                SmartRule.Condition(RuleField.GENRE, RuleOp.IS, "Metal"),
            ),
        )
        assertThat(rule.matches(track(genre = "Metal"))).isTrue()
        assertThat(rule.matches(track(genre = "Jazz"))).isFalse()
    }

    @Test
    fun `nested combination - (rock OR metal) AND year gt 1990 AND rating gt 3`() {
        val rule = SmartRule.AllOf(
            listOf(
                SmartRule.AnyOf(
                    listOf(
                        SmartRule.Condition(RuleField.GENRE, RuleOp.IS, "Rock"),
                        SmartRule.Condition(RuleField.GENRE, RuleOp.IS, "Metal"),
                    ),
                ),
                SmartRule.Condition(RuleField.YEAR, RuleOp.GREATER_THAN, "1990"),
                SmartRule.Condition(RuleField.RATING, RuleOp.GREATER_THAN, "3"),
            ),
        )
        assertThat(rule.matches(track(genre = "Metal", year = 1995, rating = 5))).isTrue()
        assertThat(rule.matches(track(genre = "Metal", year = 1995, rating = 3))).isFalse()
        assertThat(rule.matches(track(genre = "Pop", year = 1995, rating = 5))).isFalse()
    }

    // ------------------------------------------------------------ serialization

    @Test
    fun `JSON round trip preserves the tree`() {
        val rule: SmartRule = SmartRule.AllOf(
            listOf(
                SmartRule.AnyOf(
                    listOf(
                        SmartRule.Condition(RuleField.GENRE, RuleOp.IS, "Rock"),
                        SmartRule.Condition(RuleField.ARTIST, RuleOp.CONTAINS, "beat"),
                    ),
                ),
                SmartRule.Condition(RuleField.RATING, RuleOp.GREATER_THAN, "2"),
            ),
        )
        val json = SmartRule.toJson(rule)
        assertThat(SmartRule.fromJson(json)).isEqualTo(rule)
    }

    @Test
    fun `malformed JSON returns null instead of throwing`() {
        assertThat(SmartRule.fromJson("not json at all")).isNull()
        assertThat(SmartRule.fromJson("{}")).isNull()
    }

    @Test
    fun `isComplete requires a value and a parseable number for numeric fields`() {
        assertThat(SmartRule.Condition(RuleField.GENRE, RuleOp.IS, "Rock").isComplete).isTrue()
        assertThat(SmartRule.Condition(RuleField.GENRE, RuleOp.IS, "  ").isComplete).isFalse()
        assertThat(SmartRule.Condition(RuleField.YEAR, RuleOp.IS, " 1990 ").isComplete).isTrue()
        assertThat(SmartRule.Condition(RuleField.YEAR, RuleOp.IS, "soon").isComplete).isFalse()
    }

    @Test
    fun `every field and operator carries a human label`() {
        assertThat(RuleField.entries.map { it.label }).containsNoDuplicates()
        assertThat(RuleField.TRACK_NUMBER.label).isEqualTo("Track number")
        assertThat(RuleOp.GREATER_THAN.label).isEqualTo(">")
    }

    @Test
    fun `operatorsFor exposes ordering ops only for numeric fields`() {
        assertThat(RuleOp.operatorsFor(RuleField.YEAR))
            .containsExactly(RuleOp.IS, RuleOp.IS_NOT, RuleOp.LESS_THAN, RuleOp.GREATER_THAN)
        assertThat(RuleOp.operatorsFor(RuleField.GENRE))
            .containsExactly(RuleOp.IS, RuleOp.IS_NOT, RuleOp.CONTAINS)
    }
}
