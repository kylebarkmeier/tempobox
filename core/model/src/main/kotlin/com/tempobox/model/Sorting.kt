package com.tempobox.model

import kotlinx.serialization.Serializable

/**
 * Sort keys for library views. The first five (the standard set,
 * [STANDARD_SORT_KEYS]) are what every main tab offers: alphabetical by the
 * current view paradigm, recently added, last modified, library rating, and
 * the date in the ID3 tag (year). The rest only appear in the detail views
 * that they make sense for ([LibrarySubview.sortKeys]).
 */
@Serializable
enum class SortKey {
    ALPHABETICAL,
    RECENTLY_ADDED,
    LAST_MODIFIED,
    RATING,
    TAG_DATE,

    /** Disc then track number; untagged numbers sort last. Album detail. */
    TRACK_NUMBER,

    /** Album artist, album, disc, track: the natural order of artist/genre track lists. */
    ALBUM_ORDER,

    DURATION,
    PLAY_COUNT,

    /**
     * The playlist's stored order. Position-based, so it is only meaningful
     * through [sortTracks] (a comparator can't see list positions); there it
     * keeps the incoming order, reversed when descending. View-only: applying
     * any sort never rewrites the stored playlist.
     */
    PLAYLIST_ORDER,
}

/** The keys every main library tab offers (product spec: 5-way sorting). */
val STANDARD_SORT_KEYS: List<SortKey> = listOf(
    SortKey.ALPHABETICAL,
    SortKey.RECENTLY_ADDED,
    SortKey.LAST_MODIFIED,
    SortKey.RATING,
    SortKey.TAG_DATE,
)

/** Direction a key starts in when first picked from a sort menu. */
fun SortKey.defaultAscending(): Boolean = when (this) {
    SortKey.ALPHABETICAL, SortKey.TRACK_NUMBER, SortKey.ALBUM_ORDER, SortKey.PLAYLIST_ORDER -> true
    else -> false
}

@Serializable
data class SortSpec(
    val key: SortKey = SortKey.ALPHABETICAL,
    val ascending: Boolean = true,
)

/** Comparator for tracks under a given sort spec. Kept pure for unit testing. */
fun SortSpec.trackComparator(): Comparator<Track> {
    val base: Comparator<Track> = when (key) {
        SortKey.ALPHABETICAL -> compareBy { it.title.sortNormalized() }
        SortKey.RECENTLY_ADDED -> compareBy { it.dateAddedMs }
        SortKey.LAST_MODIFIED -> compareBy { it.dateModifiedMs }
        SortKey.RATING -> compareBy { it.rating }
        SortKey.TAG_DATE -> compareBy { it.year ?: Int.MIN_VALUE }
        SortKey.TRACK_NUMBER -> compareBy(
            { it.discNumber ?: Int.MAX_VALUE },
            { it.trackNumber ?: Int.MAX_VALUE },
        )
        SortKey.ALBUM_ORDER -> compareBy(
            { it.effectiveAlbumArtist.sortNormalized() },
            { it.effectiveAlbum.sortNormalized() },
            { it.discNumber ?: Int.MAX_VALUE },
            { it.trackNumber ?: Int.MAX_VALUE },
        )
        SortKey.DURATION -> compareBy { it.durationMs }
        SortKey.PLAY_COUNT -> compareBy { it.playCount }
        // Position-based: everything compares equal so a stable sort keeps the
        // incoming order. Direction is handled by sortTracks, and the usual
        // tie-break must NOT apply (it would reorder by title).
        SortKey.PLAYLIST_ORDER -> return Comparator { _, _ -> 0 }
    }
    val directed = if (ascending) base else base.reversed()
    // Stable tie-break so lists don't jitter between refreshes.
    return directed.thenBy { it.title.sortNormalized() }.thenBy { it.id }
}

/**
 * Sorts [tracks] under this spec. Prefer this over using [trackComparator]
 * directly: PLAYLIST_ORDER keeps the incoming (stored) order, which a
 * comparator cannot express, reversing the list when descending.
 */
fun SortSpec.sortTracks(tracks: List<Track>): List<Track> = when (key) {
    SortKey.PLAYLIST_ORDER -> if (ascending) tracks else tracks.reversed()
    else -> tracks.sortedWith(trackComparator())
}

/**
 * Normalizes a display string for alphabetical sorting: case-insensitive and
 * ignores a leading English article ("The Beatles" sorts under B).
 */
fun String.sortNormalized(): String {
    val lower = lowercase().trim()
    for (article in listOf("the ", "a ", "an ")) {
        if (lower.startsWith(article) && lower.length > article.length) {
            return lower.removePrefix(article)
        }
    }
    return lower
}
