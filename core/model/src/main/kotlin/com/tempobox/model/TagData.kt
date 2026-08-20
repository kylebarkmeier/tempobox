package com.tempobox.model

/**
 * The editable subset of a file's tags, as shown in the "Edit ID3 tag(s)" modal.
 *
 * Null fields mean "leave unchanged" when applied to multiple tracks at once
 * (bulk edit), so editing the genre of 20 selected tracks doesn't wipe their
 * titles. When editing a single track all fields are pre-populated.
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

    companion object {
        /** Pre-populated editor state for a single track. */
        fun from(track: Track): TagData = TagData(
            title = track.title,
            artist = track.artist,
            albumArtist = track.albumArtist,
            album = track.album,
            genre = track.genre,
            year = track.year,
            trackNumber = track.trackNumber,
            discNumber = track.discNumber,
        )
    }
}
