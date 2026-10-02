package com.tempobox.ui.library

import com.google.common.truth.Truth.assertThat
import com.tempobox.model.LibrarySubview
import com.tempobox.model.SortKey
import com.tempobox.model.SortSpec
import com.tempobox.model.defaultSort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Session sort holder for the detail views (the subview analogue of the per-tab map). */
class SubviewSortStateTest {

    private val state = SubviewSortState()

    @Test
    fun `untouched views report their own defaults`() = runTest {
        LibrarySubview.entries.forEach { view ->
            assertThat(state.current(view)).isEqualTo(view.defaultSort())
            assertThat(state.sortFlow(view).first()).isEqualTo(view.defaultSort())
        }
    }

    @Test
    fun `setting a sort only affects that view`() = runTest {
        val spec = SortSpec(SortKey.RATING, ascending = false)
        state.set(LibrarySubview.ALBUM_TRACKS, spec)

        assertThat(state.current(LibrarySubview.ALBUM_TRACKS)).isEqualTo(spec)
        assertThat(state.sortFlow(LibrarySubview.ALBUM_TRACKS).first()).isEqualTo(spec)
        // Session scope is per view type: every other view keeps its default.
        LibrarySubview.entries.filter { it != LibrarySubview.ALBUM_TRACKS }.forEach { view ->
            assertThat(state.current(view)).isEqualTo(view.defaultSort())
        }
    }

    @Test
    fun `the latest set wins`() {
        state.set(LibrarySubview.PLAYLIST_TRACKS, SortSpec(SortKey.ALPHABETICAL))
        state.set(LibrarySubview.PLAYLIST_TRACKS, SortSpec(SortKey.DURATION, ascending = false))
        assertThat(state.current(LibrarySubview.PLAYLIST_TRACKS))
            .isEqualTo(SortSpec(SortKey.DURATION, ascending = false))
    }
}
