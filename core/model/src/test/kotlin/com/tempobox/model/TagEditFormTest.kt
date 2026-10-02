package com.tempobox.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TagEditFormTest {

    private fun track(
        path: String = "/music/a.mp3",
        title: String = "Song",
        artist: String = "Artist",
        albumArtist: String = "AA",
        album: String = "Album",
        genre: String = "Rock",
        year: Int? = 1999,
        trackNumber: Int? = 3,
        discNumber: Int? = 1,
    ) = Track(
        filePath = path, title = title, artist = artist, albumArtist = albumArtist,
        album = album, genre = genre, year = year, trackNumber = trackNumber,
        discNumber = discNumber,
    )

    // ------------------------------------------------------------- seeding

    @Test
    fun `single track prefills every field and marks none mixed`() {
        val form = TagEditForm.from(listOf(track()))

        assertThat(form.title).isEqualTo(TagField("Song"))
        assertThat(form.artist).isEqualTo(TagField("Artist"))
        assertThat(form.albumArtist).isEqualTo(TagField("AA"))
        assertThat(form.album).isEqualTo(TagField("Album"))
        assertThat(form.genre).isEqualTo(TagField("Rock"))
        assertThat(form.year).isEqualTo(TagField("1999"))
        assertThat(form.trackNumber).isEqualTo(TagField("3"))
        assertThat(form.discNumber).isEqualTo(TagField("1"))
    }

    @Test
    fun `single track with absent optional tags prefills empty, not mixed`() {
        val form = TagEditForm.from(listOf(track(genre = "", year = null, trackNumber = null, discNumber = null)))

        assertThat(form.genre).isEqualTo(TagField(""))
        assertThat(form.year).isEqualTo(TagField(""))
        assertThat(form.trackNumber).isEqualTo(TagField(""))
        assertThat(form.discNumber).isEqualTo(TagField(""))
    }

    @Test
    fun `multiple tracks prefill the fields all of them share`() {
        val form = TagEditForm.from(
            listOf(
                track(path = "/1.mp3", title = "One", trackNumber = 1),
                track(path = "/2.mp3", title = "Two", trackNumber = 2),
            ),
        )

        // Shared across both tracks → prefilled.
        assertThat(form.artist).isEqualTo(TagField("Artist"))
        assertThat(form.albumArtist).isEqualTo(TagField("AA"))
        assertThat(form.album).isEqualTo(TagField("Album"))
        assertThat(form.genre).isEqualTo(TagField("Rock"))
        assertThat(form.year).isEqualTo(TagField("1999"))
        assertThat(form.discNumber).isEqualTo(TagField("1"))
        // Differing → empty and flagged mixed.
        assertThat(form.title).isEqualTo(TagField("", isMixed = true))
        assertThat(form.trackNumber).isEqualTo(TagField("", isMixed = true))
    }

    @Test
    fun `numeric field is mixed when one track has a value and another has none`() {
        val form = TagEditForm.from(
            listOf(
                track(path = "/1.mp3", year = 1999),
                track(path = "/2.mp3", year = null),
            ),
        )
        assertThat(form.year).isEqualTo(TagField("", isMixed = true))
    }

    @Test
    fun `field absent on every track is shared-empty, not mixed`() {
        val form = TagEditForm.from(
            listOf(
                track(path = "/1.mp3", genre = "", year = null),
                track(path = "/2.mp3", genre = "", year = null),
            ),
        )
        assertThat(form.genre).isEqualTo(TagField(""))
        assertThat(form.year).isEqualTo(TagField(""))
    }

    @Test
    fun `string field differing only in case is mixed`() {
        val form = TagEditForm.from(
            listOf(
                track(path = "/1.mp3", genre = "rock"),
                track(path = "/2.mp3", genre = "Rock"),
            ),
        )
        assertThat(form.genre.isMixed).isTrue()
    }

    // ------------------------------------------------------------- write set

    @Test
    fun `untouched form derives an empty write set`() {
        val form = TagEditForm.from(listOf(track()))
        assertThat(form.deriveEdits().isEmpty).isTrue()
    }

    @Test
    fun `untouched multi-track form with mixed fields derives an empty write set`() {
        val form = TagEditForm.from(
            listOf(
                track(path = "/1.mp3", title = "One", trackNumber = 1),
                track(path = "/2.mp3", title = "Two", trackNumber = 2),
            ),
        )
        assertThat(form.deriveEdits().isEmpty).isTrue()
    }

    @Test
    fun `mixed field left empty stays unchanged while an edited field is written`() {
        val form = TagEditForm.from(
            listOf(
                track(path = "/1.mp3", title = "One", genre = "Rock"),
                track(path = "/2.mp3", title = "Two", genre = "Pop"),
            ),
        )

        val edits = form.deriveEdits(album = "Remaster")

        assertThat(edits.album).isEqualTo("Remaster")
        // Mixed title/genre were never touched → must NOT be blanked.
        assertThat(edits.title).isNull()
        assertThat(edits.genre).isNull()
        assertThat(edits.artist).isNull()
    }

    @Test
    fun `typing into a mixed field includes it in the write set`() {
        val form = TagEditForm.from(
            listOf(
                track(path = "/1.mp3", genre = "Rock"),
                track(path = "/2.mp3", genre = "Pop"),
            ),
        )
        assertThat(form.deriveEdits(genre = "Shoegaze").genre).isEqualTo("Shoegaze")
    }

    @Test
    fun `only changed fields are written, prefilled fields written back are not`() {
        val form = TagEditForm.from(listOf(track()))

        // Simulates a user editing only the genre; every other field still
        // holds its prefill text.
        val edits = form.deriveEdits(genre = "Shoegaze")

        assertThat(edits).isEqualTo(TagData(genre = "Shoegaze"))
    }

    @Test
    fun `clearing a prefilled text field writes an empty string to erase the tag`() {
        val form = TagEditForm.from(listOf(track(genre = "Rock")))
        val edits = form.deriveEdits(genre = "")
        assertThat(edits.genre).isEqualTo("")
        assertThat(edits.isEmpty).isFalse()
    }

    @Test
    fun `numeric edits parse to ints`() {
        val form = TagEditForm.from(listOf(track(year = 1999, trackNumber = 3, discNumber = 1)))
        val edits = form.deriveEdits(year = "2005", trackNumber = "12", discNumber = "2")
        assertThat(edits.year).isEqualTo(2005)
        assertThat(edits.trackNumber).isEqualTo(12)
        assertThat(edits.discNumber).isEqualTo(2)
    }

    @Test
    fun `blanked numeric field is treated as unchanged`() {
        // The writer can't express "delete the year", so blanking it must not
        // produce a write (and must not crash).
        val form = TagEditForm.from(listOf(track(year = 1999)))
        assertThat(form.deriveEdits(year = "").year).isNull()
    }

    @Test
    fun `unparseable numeric edit is treated as unchanged`() {
        val form = TagEditForm.from(listOf(track(year = 1999)))
        assertThat(form.deriveEdits(year = "199x").year).isNull()
    }

    @Test
    fun `whitespace-only differences are not dirty`() {
        val form = TagEditForm.from(listOf(track(artist = "Artist", year = 1999)))
        val edits = form.deriveEdits(artist = "  Artist  ", year = " 1999 ")
        assertThat(edits.isEmpty).isTrue()
    }

    @Test
    fun `edited text is trimmed before writing`() {
        val form = TagEditForm.from(listOf(track()))
        assertThat(form.deriveEdits(artist = "  New Artist ").artist).isEqualTo("New Artist")
    }

    @Test
    fun `edit then revert to the prefill is not dirty`() {
        val form = TagEditForm.from(listOf(track(album = "Album")))
        // User typed something, changed their mind, restored the original text.
        assertThat(form.deriveEdits(album = "Album").album).isNull()
    }

    @Test
    fun `comment is never part of the editor write set`() {
        val form = TagEditForm.from(listOf(track()))
        assertThat(form.deriveEdits(title = "Renamed").comment).isNull()
    }

    @Test
    fun `identical duplicate values across tracks count as shared`() {
        val form = TagEditForm.from(
            listOf(
                track(path = "/1.mp3", title = "Same"),
                track(path = "/2.mp3", title = "Same"),
                track(path = "/3.mp3", title = "Same"),
            ),
        )
        assertThat(form.title).isEqualTo(TagField("Same"))
    }
}
