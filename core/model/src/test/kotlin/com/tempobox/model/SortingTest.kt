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
    ) = Track(
        id = id, filePath = "/m/$id.mp3", title = title,
        dateAddedMs = added, dateModifiedMs = modified, rating = rating, year = year,
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
    fun `sortNormalized strips articles only when followed by more text`() {
        assertThat("The Beatles".sortNormalized()).isEqualTo("beatles")
        assertThat("A Perfect Circle".sortNormalized()).isEqualTo("perfect circle")
        assertThat("An Horse".sortNormalized()).isEqualTo("horse")
        assertThat("The ".sortNormalized()).isEqualTo("the")
        assertThat("Them".sortNormalized()).isEqualTo("them")
    }
}
