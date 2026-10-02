package com.tempobox.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SortingTest {

    private fun track(
        id: Long,
        title: String,
        added: Long = 0,
        modified: Long = 0,
        rating: Int = 0,
        year: Int? = null,
        disc: Int? = null,
        trackNo: Int? = null,
        duration: Long = 0,
        playCount: Long = 0,
        artist: String = "",
        album: String = "",
    ) = Track(
        id = id, filePath = "/m/$id.mp3", title = title,
        dateAddedMs = added, dateModifiedMs = modified, rating = rating, year = year,
        discNumber = disc, trackNumber = trackNo, durationMs = duration, playCount = playCount,
        artist = artist, albumArtist = artist, album = album,
    )

    @Test
    fun `alphabetical ignores leading articles and case`() {
        val tracks = listOf(
            track(1, "Zebra"),
            track(2, "The Apple"),
            track(3, "an orange"),
            track(4, "Banana"),
        )
        val sorted = tracks.sortedWith(SortSpec(SortKey.ALPHABETICAL, ascending = true).trackComparator())
        assertThat(sorted.map { it.title })
            .containsExactly("The Apple", "Banana", "an orange", "Zebra")
            .inOrder()
    }

    @Test
    fun `descending flips the order`() {
        val tracks = listOf(track(1, "A"), track(2, "B"))
        val sorted = tracks.sortedWith(SortSpec(SortKey.ALPHABETICAL, ascending = false).trackComparator())
        assertThat(sorted.map { it.title }).containsExactly("B", "A").inOrder()
    }

    @Test
    fun `recently added sorts by dateAdded`() {
        val tracks = listOf(track(1, "Old", added = 100), track(2, "New", added = 200))
        val sorted = tracks.sortedWith(SortSpec(SortKey.RECENTLY_ADDED, ascending = false).trackComparator())
        assertThat(sorted.first().title).isEqualTo("New")
    }

    @Test
    fun `last modified sorts by file mtime`() {
        val tracks = listOf(track(1, "A", modified = 5), track(2, "B", modified = 9))
        val sorted = tracks.sortedWith(SortSpec(SortKey.LAST_MODIFIED, ascending = true).trackComparator())
        assertThat(sorted.map { it.title }).containsExactly("A", "B").inOrder()
    }

    @Test
    fun `rating sort puts highest first when descending`() {
        val tracks = listOf(track(1, "Meh", rating = 2), track(2, "Great", rating = 5))
        val sorted = tracks.sortedWith(SortSpec(SortKey.RATING, ascending = false).trackComparator())
        assertThat(sorted.first().title).isEqualTo("Great")
    }

    @Test
    fun `tag date sorts by year with missing years last when descending`() {
        val tracks = listOf(track(1, "NoYear", year = null), track(2, "Y2020", year = 2020), track(3, "Y1990", year = 1990))
        val sorted = tracks.sortedWith(SortSpec(SortKey.TAG_DATE, ascending = false).trackComparator())
        assertThat(sorted.map { it.title }).containsExactly("Y2020", "Y1990", "NoYear").inOrder()
    }

    @Test
    fun `equal keys tie-break deterministically by title then id`() {
        val tracks = listOf(track(2, "Same", rating = 3), track(1, "Same", rating = 3))
        val sorted = tracks.sortedWith(SortSpec(SortKey.RATING).trackComparator())
        assertThat(sorted.map { it.id }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `track number sorts by disc then track with untagged numbers last`() {
        val tracks = listOf(
            track(1, "D2T1", disc = 2, trackNo = 1),
            track(2, "D1T2", disc = 1, trackNo = 2),
            track(3, "D1T1", disc = 1, trackNo = 1),
            track(4, "Untagged", disc = null, trackNo = null),
        )
        val sorted = tracks.sortedWith(SortSpec(SortKey.TRACK_NUMBER, ascending = true).trackComparator())
        assertThat(sorted.map { it.title })
            .containsExactly("D1T1", "D1T2", "D2T1", "Untagged")
            .inOrder()
    }

    @Test
    fun `album order groups by artist then album then disc and track`() {
        val tracks = listOf(
            track(1, "Late", artist = "The Band", album = "Second", disc = 1, trackNo = 1),
            track(2, "Opener", artist = "Abba", album = "Hits", disc = 1, trackNo = 1),
            track(3, "Closer", artist = "Abba", album = "Hits", disc = 1, trackNo = 9),
        )
        val sorted = tracks.sortedWith(SortSpec(SortKey.ALBUM_ORDER, ascending = true).trackComparator())
        assertThat(sorted.map { it.title }).containsExactly("Opener", "Closer", "Late").inOrder()
    }

    @Test
    fun `duration sort orders by track length`() {
        val tracks = listOf(track(1, "Long", duration = 300_000), track(2, "Short", duration = 90_000))
        val sorted = tracks.sortedWith(SortSpec(SortKey.DURATION, ascending = true).trackComparator())
        assertThat(sorted.map { it.title }).containsExactly("Short", "Long").inOrder()
    }

    @Test
    fun `play count sort puts most played first when descending`() {
        val tracks = listOf(track(1, "Rare", playCount = 2), track(2, "Favorite", playCount = 40))
        val sorted = tracks.sortedWith(SortSpec(SortKey.PLAY_COUNT, ascending = false).trackComparator())
        assertThat(sorted.first().title).isEqualTo("Favorite")
    }

    @Test
    fun `sortTracks keeps playlist order untouched when ascending`() {
        val stored = listOf(track(3, "C"), track(1, "A"), track(2, "B"))
        val sorted = SortSpec(SortKey.PLAYLIST_ORDER, ascending = true).sortTracks(stored)
        assertThat(sorted).isEqualTo(stored)
    }

    @Test
    fun `sortTracks reverses playlist order when descending without touching ties`() {
        val stored = listOf(track(3, "C"), track(1, "A"), track(2, "B"))
        val sorted = SortSpec(SortKey.PLAYLIST_ORDER, ascending = false).sortTracks(stored)
        assertThat(sorted.map { it.id }).containsExactly(2L, 1L, 3L).inOrder()
    }

    @Test
    fun `sortTracks delegates to the comparator for every other key`() {
        val stored = listOf(track(1, "Zebra"), track(2, "Apple"))
        val sorted = SortSpec(SortKey.ALPHABETICAL, ascending = true).sortTracks(stored)
        assertThat(sorted.map { it.title }).containsExactly("Apple", "Zebra").inOrder()
    }

    @Test
    fun `playlist order comparator keeps duplicates in place (stable all-equal)`() {
        val dupe = track(7, "Same")
        val stored = listOf(dupe, track(1, "Other"), dupe)
        val sorted = stored.sortedWith(SortSpec(SortKey.PLAYLIST_ORDER).trackComparator())
        assertThat(sorted.map { it.id }).containsExactly(7L, 1L, 7L).inOrder()
    }

    @Test
    fun `menu defaults start alphabetical and positional keys ascending, the rest descending`() {
        val ascending = SortKey.entries.filter { it.defaultAscending() }
        assertThat(ascending).containsExactly(
            SortKey.ALPHABETICAL, SortKey.TRACK_NUMBER, SortKey.ALBUM_ORDER, SortKey.PLAYLIST_ORDER,
        )
    }

    @Test
    fun `sortNormalized strips articles only when followed by more text`() {
        assertThat("The Beatles".sortNormalized()).isEqualTo("beatles")
        assertThat("A Perfect Circle".sortNormalized()).isEqualTo("perfect circle")
        assertThat("An Horse".sortNormalized()).isEqualTo("horse")
        assertThat("The ".sortNormalized()).isEqualTo("the")
        assertThat("Them".sortNormalized()).isEqualTo("them")
    }
}
