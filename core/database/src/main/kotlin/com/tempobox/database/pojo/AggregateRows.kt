package com.tempobox.database.pojo

import com.tempobox.model.Album
import com.tempobox.model.AlbumArtist
import com.tempobox.model.Genre

/**
 * Projection rows for GROUP BY queries in [com.tempobox.database.dao.TrackDao].
 * Field names must match the SQL aliases exactly (Room maps by name).
 */

data class AlbumRow(
    val name: String,
    val albumArtist: String,
    val year: Int?,
    val trackCount: Int,
    val durationMs: Long,
    val artworkTrackPath: String?,
    val dateAddedMs: Long,
    val dateModifiedMs: Long,
    val maxRating: Int,
) {
    fun toModel(): Album = Album(
        name = name,
        albumArtist = albumArtist,
        year = year,
        trackCount = trackCount,
        durationMs = durationMs,
        artworkTrackPath = artworkTrackPath,
        dateAddedMs = dateAddedMs,
        dateModifiedMs = dateModifiedMs,
        maxRating = maxRating,
    )
}

data class AlbumArtistRow(
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
    /** Comma-joined distinct genres (GROUP_CONCAT); split in the mapper. */
    val genresConcat: String?,
    /** Comma-joined representative artwork paths (up to 4 used by the collage). */
    val artPathsConcat: String?,
    val dateAddedMs: Long,
    val dateModifiedMs: Long,
) {
    fun toModel(imageUrl: String? = null): AlbumArtist = AlbumArtist(
        name = name,
        albumCount = albumCount,
        trackCount = trackCount,
        // De-dup in Kotlin: SQLite's GROUP_CONCAT can't combine DISTINCT with a
        // custom separator, so the query concatenates raw values.
        genres = genresConcat?.split(GROUP_CONCAT_SEP)
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.sorted()
            .orEmpty(),
        imageUrl = imageUrl,
        artworkTrackPaths = artPathsConcat?.split(GROUP_CONCAT_SEP)
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.take(4)
            .orEmpty(),
        dateAddedMs = dateAddedMs,
        dateModifiedMs = dateModifiedMs,
    )

    companion object {
        /** Separator unlikely to appear in genres or file paths. */
        const val GROUP_CONCAT_SEP = ""
    }
}

data class GenreRow(
    val name: String,
    val trackCount: Int,
    val albumCount: Int,
    val dateAddedMs: Long,
) {
    fun toModel(): Genre = Genre(
        name = name,
        trackCount = trackCount,
        albumCount = albumCount,
        dateAddedMs = dateAddedMs,
    )
}

/** Playlist row joined with its entry count and total duration. */
data class PlaylistWithStats(
    val id: Long,
    val name: String,
    val filePath: String?,
    val smartRuleJson: String?,
    val dateAddedMs: Long,
    val dateModifiedMs: Long,
    val trackCount: Int,
    val durationMs: Long,
)
