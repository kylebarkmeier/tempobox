package com.tempobox.library

import com.tempobox.database.pojo.CollageAlbumRow
import com.tempobox.model.sortNormalized

/**
 * Picks the artwork paths for the 2x2 album collages (genre tiles, artist
 * cards): each group's most played albums first (summed track play counts),
 * one slot per album. Ties, including the common case of all-zero play
 * counts, fall back to the album with more tracks, then to article-aware
 * alphabetical album/artist order, so a collage is deterministic and does not
 * reshuffle between emissions.
 */
object AlbumCollage {

    /** Collage slots: a 2x2 grid when 4+ albums have art. */
    const val MAX_PATHS = 4

    private val ranking =
        compareByDescending<CollageAlbumRow> { it.playCount }
            .thenByDescending { it.trackCount }
            .thenBy { it.album.sortNormalized() }
            .thenBy { it.albumArtist.sortNormalized() }

    /** Top [limit] artwork paths per group key, in collage slot order. */
    fun topArtPathsByKey(
        rows: List<CollageAlbumRow>,
        limit: Int = MAX_PATHS,
    ): Map<String, List<String>> =
        rows.groupBy { it.groupKey }.mapValues { (_, albums) ->
            albums.sortedWith(ranking).take(limit).map { it.artworkTrackPath }
        }
}
