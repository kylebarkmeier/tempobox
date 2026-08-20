package com.tempobox.model

/**
 * Aggregated album row for the Albums view. Derived (GROUP BY) from tracks —
 * never stored separately, so it can't drift out of sync with the library.
 *
 * @property artworkTrackPath Path of a representative track whose embedded art
 *                            is shown as the album cover.
 */
data class Album(
    val name: String,
    val albumArtist: String,
    val year: Int?,
    val trackCount: Int,
    val durationMs: Long,
    val artworkTrackPath: String?,
    val dateAddedMs: Long,
    val dateModifiedMs: Long,
    val maxRating: Int = 0,
)

/**
 * Aggregated album-artist row for the Album Artists view.
 *
 * @property imageUrl Remote artist image (Discogs) if fetched; UI falls back to
 *                    a collage of the artist's album covers.
 * @property artworkTrackPaths Up to 4 representative track paths for the collage.
 */
data class AlbumArtist(
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
    val genres: List<String> = emptyList(),
    val imageUrl: String? = null,
    val artworkTrackPaths: List<String> = emptyList(),
    val dateAddedMs: Long = 0,
    val dateModifiedMs: Long = 0,
)

/** Aggregated genre row for the Genres view. */
data class Genre(
    val name: String,
    val trackCount: Int,
    val albumCount: Int,
    val dateAddedMs: Long = 0,
)
