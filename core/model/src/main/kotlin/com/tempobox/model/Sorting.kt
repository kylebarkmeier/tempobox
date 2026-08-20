package com.tempobox.model

import kotlinx.serialization.Serializable

/**
 * Sort keys available in every library view (product spec):
 * alphabetical by the current view paradigm, recently added, last modified,
 * library rating, and the date in the ID3 tag (year).
 */
@Serializable
enum class SortKey { ALPHABETICAL, RECENTLY_ADDED, LAST_MODIFIED, RATING, TAG_DATE }

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
    }
    val directed = if (ascending) base else base.reversed()
    // Stable tie-break so lists don't jitter between refreshes.
    return directed.thenBy { it.title.sortNormalized() }.thenBy { it.id }
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
