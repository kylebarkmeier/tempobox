package com.tempobox.model

import java.text.Normalizer

/**
 * Free-form list search for the library views: case- and diacritic-insensitive
 * substring matching over the fields that identify each row type. Filtering
 * preserves the incoming list order, so it composes after sorting and the sort
 * order survives inside the results. A blank query matches everything.
 */

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/**
 * Normalizes text for matching: NFD-decomposed with combining marks stripped
 * (so "Beyoncé" and "Beyonce" compare equal), then lowercased.
 */
fun String.searchNormalized(): String =
    COMBINING_MARKS.replace(Normalizer.normalize(this, Normalizer.Form.NFD), "").lowercase()

private val NON_WORD = Regex("[^\\p{L}\\p{N}]")

/**
 * Keyword form for spoken-command matching: [searchNormalized] with every
 * character that is not a letter or digit removed, so "don't" and "dont",
 * "AC/DC" and "acdc" compare equal.
 */
fun String.searchKeyword(): String = NON_WORD.replace(searchNormalized(), "")

/**
 * True when [phrase] occurs in this text as whole words after normalization:
 * "trip" is found in "Road Trip" but not in "Triptych", "2" in "Song 2" but
 * not in "22". A blank phrase matches nothing.
 */
fun String.containsWordPhrase(phrase: String): Boolean {
    val wanted = phrase.trim().searchNormalized()
    if (wanted.isEmpty()) return false
    val pattern = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(wanted) + "(?![\\p{L}\\p{N}])")
    return pattern.containsMatchIn(searchNormalized())
}

/** True when any of [fields] contains [query] after normalization. */
fun searchMatches(query: String, vararg fields: String): Boolean {
    val normalized = query.trim().searchNormalized()
    if (normalized.isEmpty()) return true
    return fields.any { it.searchNormalized().contains(normalized) }
}

private inline fun <T> List<T>.filterMatching(
    query: String,
    crossinline fields: (T) -> List<String>,
): List<T> {
    val normalized = query.trim().searchNormalized()
    if (normalized.isEmpty()) return this
    return filter { item -> fields(item).any { it.searchNormalized().contains(normalized) } }
}

/** Tracks match on title, artist, or album. */
fun List<Track>.filterTracks(query: String): List<Track> =
    filterMatching(query) { listOf(it.title, it.artist, it.album) }

/** Albums match on name or album artist. */
fun List<Album>.filterAlbums(query: String): List<Album> =
    filterMatching(query) { listOf(it.name, it.albumArtist) }

/** Artists match on name. */
fun List<AlbumArtist>.filterArtists(query: String): List<AlbumArtist> =
    filterMatching(query) { listOf(it.name) }

/** Genres match on name. */
fun List<Genre>.filterGenres(query: String): List<Genre> =
    filterMatching(query) { listOf(it.name) }

/** Playlists match on name. */
fun List<Playlist>.filterPlaylists(query: String): List<Playlist> =
    filterMatching(query) { listOf(it.name) }

/** Queue entries match on their track's title, artist, or album. */
fun List<QueueItem>.filterQueueItems(query: String): List<QueueItem> =
    filterMatching(query) { listOf(it.track.title, it.track.artist, it.track.album) }
