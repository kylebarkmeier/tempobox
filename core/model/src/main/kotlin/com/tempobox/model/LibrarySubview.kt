package com.tempobox.model

/**
 * Library detail (subview) lists that carry their own sort control, keyed per
 * view type the same way [LibraryTab] keys the main tabs: picking a sort on
 * one artist page applies to every artist page, exactly like a tab sort
 * applies to the whole tab.
 */
enum class LibrarySubview {
    /** Artist detail ▸ Albums tab (both artist paradigms). */
    ARTIST_ALBUMS,

    /** Artist detail ▸ All tracks tab (both artist paradigms). */
    ARTIST_TRACKS,

    /** Album detail track list. */
    ALBUM_TRACKS,

    /** Genre detail ▸ Artists tab. */
    GENRE_ARTISTS,

    /** Genre detail ▸ Albums tab. */
    GENRE_ALBUMS,

    /** Genre detail ▸ Tracks tab. */
    GENRE_TRACKS,

    /** Playlist detail track list (static and smart). */
    PLAYLIST_TRACKS,
}

/**
 * Sort keys this subview's menu offers, in menu order. Collection views get
 * the standard main-tab set; track lists add duration and play count plus
 * their natural order (track number, album order, or playlist order).
 */
fun LibrarySubview.sortKeys(): List<SortKey> = when (this) {
    LibrarySubview.ARTIST_ALBUMS, LibrarySubview.GENRE_ALBUMS -> STANDARD_SORT_KEYS

    LibrarySubview.ARTIST_TRACKS, LibrarySubview.GENRE_TRACKS ->
        listOf(SortKey.ALBUM_ORDER) + STANDARD_SORT_KEYS +
            listOf(SortKey.DURATION, SortKey.PLAY_COUNT)

    LibrarySubview.ALBUM_TRACKS -> listOf(
        SortKey.TRACK_NUMBER,
        SortKey.ALPHABETICAL,
        SortKey.DURATION,
        SortKey.RATING,
        SortKey.PLAY_COUNT,
    )

    // Rating and tag date aggregate per track, not per artist; the artist
    // comparator has no column for them, so the menu leaves them out.
    LibrarySubview.GENRE_ARTISTS -> listOf(
        SortKey.ALPHABETICAL,
        SortKey.RECENTLY_ADDED,
        SortKey.LAST_MODIFIED,
    )

    LibrarySubview.PLAYLIST_TRACKS ->
        listOf(SortKey.PLAYLIST_ORDER) + STANDARD_SORT_KEYS +
            listOf(SortKey.DURATION, SortKey.PLAY_COUNT)
}

/**
 * The order a subview shows before the user touches its sort menu. Track
 * lists keep their natural order (disc/track on an album, album order for an
 * artist or genre, stored order for a playlist); artist albums default to
 * release year so a discography reads chronologically.
 */
fun LibrarySubview.defaultSort(): SortSpec = when (this) {
    LibrarySubview.ARTIST_ALBUMS -> SortSpec(SortKey.TAG_DATE, ascending = true)
    LibrarySubview.ARTIST_TRACKS -> SortSpec(SortKey.ALBUM_ORDER, ascending = true)
    LibrarySubview.ALBUM_TRACKS -> SortSpec(SortKey.TRACK_NUMBER, ascending = true)
    LibrarySubview.GENRE_ARTISTS -> SortSpec(SortKey.ALPHABETICAL, ascending = true)
    LibrarySubview.GENRE_ALBUMS -> SortSpec(SortKey.ALPHABETICAL, ascending = true)
    LibrarySubview.GENRE_TRACKS -> SortSpec(SortKey.ALBUM_ORDER, ascending = true)
    LibrarySubview.PLAYLIST_TRACKS -> SortSpec(SortKey.PLAYLIST_ORDER, ascending = true)
}
