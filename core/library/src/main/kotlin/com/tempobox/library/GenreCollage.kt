package com.tempobox.library

import com.tempobox.database.pojo.GenreAlbumArtRow
import com.tempobox.model.sortNormalized

/**
 * Picks the artwork paths for each genre's collage: the genre's most played
 * albums first (summed track play counts). Ties, including the common case of
 * all-zero play counts, fall back to the album with more tracks, then to
 * article-aware alphabetical album/artist order, so a collage is deterministic
 * and does not reshuffle between emissions.
 */
object GenreCollage {

    /** Collage slots: a 2x2 grid when 4+ albums have art. */
    const val MAX_PATHS = 4

    private val ranking =
        compareByDescending<GenreAlbumArtRow> { it.playCount }
            .thenByDescending { it.trackCount }
            .thenBy { it.album.sortNormalized() }
            .thenBy { it.albumArtist.sortNormalized() }

    /** Top [limit] artwork paths per genre name, in collage slot order. */
    fun topArtPathsByGenre(
        rows: List<GenreAlbumArtRow>,
        limit: Int = MAX_PATHS,
    ): Map<String, List<String>> =
        rows.groupBy { it.genreName }.mapValues { (_, albums) ->
            albums.sortedWith(ranking).take(limit).map { it.artworkTrackPath }
        }
}
