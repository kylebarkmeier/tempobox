package com.tempobox.model

/**
 * The write set of a tag edit, as produced by [TagEditForm.deriveEdits] from
 * the "Edit ID3 tag(s)" modal.
 *
 * Null fields mean "leave unchanged": the tag writer only touches non-null
 * fields, so an edit never clobbers values the user didn't change — whether
 * that's one track or a bulk edit where the selected tracks hold differing
 * values. A non-null empty string erases that tag.
 */
data class TagData(
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val comment: String? = null,
) {
    /** True when applying this edit would change nothing. */
    val isEmpty: Boolean
        get() = listOf(
            title, artist, albumArtist, album, genre, year, trackNumber, discNumber, comment,
        ).all { it == null }
}
