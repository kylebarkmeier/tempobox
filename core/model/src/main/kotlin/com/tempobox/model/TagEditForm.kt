package com.tempobox.model

/**
 * Initial editor state of one tag field, as the text the form shows.
 *
 * @property value   Prefill text: the value every selected track shares, or ""
 *                   when the tracks disagree ([isMixed]) or the tag is absent.
 * @property isMixed True when the selected tracks hold differing values, so the
 *                   UI can show a "Multiple values" hint instead of a field
 *                   that just looks empty.
 */
data class TagField(val value: String = "", val isMixed: Boolean = false)

/**
 * Pure logic behind the "Edit ID3 tag(s)" modal:
 *
 *  1. [from] seeds every field from the selected tracks — a field is prefilled
 *     when ALL tracks share the same value, and flagged [TagField.isMixed]
 *     otherwise (always prefilled for a single track).
 *  2. [deriveEdits] turns the final field texts into the [TagData] write set.
 *     Only *dirty* fields (text differs from the seed) are included; untouched
 *     fields — crucially including mixed-value fields left empty — stay null,
 *     which [TagData]'s contract defines as "leave unchanged" all the way down
 *     to the file writer. Saving therefore never clobbers values the user
 *     didn't touch, no matter how many tracks are selected.
 *
 * Lives in `core:model` so it tests as plain JVM JUnit and both the UI and
 * repositories can share one definition of "what did the user change".
 */
data class TagEditForm(
    val title: TagField,
    val artist: TagField,
    val albumArtist: TagField,
    val album: TagField,
    val genre: TagField,
    val year: TagField,
    val trackNumber: TagField,
    val discNumber: TagField,
) {

    /**
     * Builds the write set from the form's final texts: each field maps to its
     * trimmed text when the user changed it, or null (= leave unchanged) when
     * it still matches the seed. Clearing a prefilled text field writes ""
     * (erases the tag on every selected track); numeric fields can't be
     * cleared this way — a blanked number is treated as unchanged, matching
     * what the tag writer can express.
     */
    fun deriveEdits(
        title: String = this.title.value,
        artist: String = this.artist.value,
        albumArtist: String = this.albumArtist.value,
        album: String = this.album.value,
        genre: String = this.genre.value,
        year: String = this.year.value,
        trackNumber: String = this.trackNumber.value,
        discNumber: String = this.discNumber.value,
    ): TagData = TagData(
        title = this.title.textEdit(title),
        artist = this.artist.textEdit(artist),
        albumArtist = this.albumArtist.textEdit(albumArtist),
        album = this.album.textEdit(album),
        genre = this.genre.textEdit(genre),
        year = this.year.numberEdit(year),
        trackNumber = this.trackNumber.numberEdit(trackNumber),
        discNumber = this.discNumber.numberEdit(discNumber),
    )

    /** Trimmed text when it differs from the seed, else null (= unchanged). */
    private fun TagField.textEdit(text: String): String? =
        text.trim().takeIf { it != value }

    /** Parsed int when the text differs from the seed, else null (= unchanged). */
    private fun TagField.numberEdit(text: String): Int? =
        text.trim().takeIf { it != value }?.toIntOrNull()

    companion object {

        /**
         * Seeds the form from the current tags of [tracks]: a field is
         * prefilled when every track shares the same value (trivially true for
         * a single track), and marked [TagField.isMixed] when they differ.
         */
        fun from(tracks: List<Track>): TagEditForm = TagEditForm(
            title = shared(tracks) { it.title },
            artist = shared(tracks) { it.artist },
            albumArtist = shared(tracks) { it.albumArtist },
            album = shared(tracks) { it.album },
            genre = shared(tracks) { it.genre },
            year = shared(tracks) { it.year?.toString().orEmpty() },
            trackNumber = shared(tracks) { it.trackNumber?.toString().orEmpty() },
            discNumber = shared(tracks) { it.discNumber?.toString().orEmpty() },
        )

        private fun shared(tracks: List<Track>, selector: (Track) -> String): TagField {
            val values = tracks.map(selector).distinct()
            return when (values.size) {
                0, 1 -> TagField(values.firstOrNull().orEmpty())
                else -> TagField(value = "", isMixed = true)
            }
        }
    }
}
