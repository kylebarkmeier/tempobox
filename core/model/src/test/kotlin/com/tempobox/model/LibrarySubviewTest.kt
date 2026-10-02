package com.tempobox.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/** Pins each detail view's sort menu contents and default order. */
class LibrarySubviewTest {

    @Test
    fun `every subview offers its own default key`() {
        LibrarySubview.entries.forEach { view ->
            assertThat(view.sortKeys()).contains(view.defaultSort().key)
        }
    }

    @Test
    fun `album tracks default to disc and track number order`() {
        assertThat(LibrarySubview.ALBUM_TRACKS.defaultSort())
            .isEqualTo(SortSpec(SortKey.TRACK_NUMBER, ascending = true))
    }

    @Test
    fun `artist and genre track lists default to album order`() {
        assertThat(LibrarySubview.ARTIST_TRACKS.defaultSort())
            .isEqualTo(SortSpec(SortKey.ALBUM_ORDER, ascending = true))
        assertThat(LibrarySubview.GENRE_TRACKS.defaultSort())
            .isEqualTo(SortSpec(SortKey.ALBUM_ORDER, ascending = true))
    }

    @Test
    fun `artist albums default to release year, oldest first`() {
        assertThat(LibrarySubview.ARTIST_ALBUMS.defaultSort())
            .isEqualTo(SortSpec(SortKey.TAG_DATE, ascending = true))
    }

    @Test
    fun `playlist tracks default to the stored playlist order`() {
        assertThat(LibrarySubview.PLAYLIST_TRACKS.defaultSort())
            .isEqualTo(SortSpec(SortKey.PLAYLIST_ORDER, ascending = true))
        // The stored order must stay reachable from the menu so the user can
        // always get back after sorting.
        assertThat(LibrarySubview.PLAYLIST_TRACKS.sortKeys().first())
            .isEqualTo(SortKey.PLAYLIST_ORDER)
    }

    @Test
    fun `album views offer exactly the standard main-tab keys`() {
        assertThat(LibrarySubview.ARTIST_ALBUMS.sortKeys()).isEqualTo(STANDARD_SORT_KEYS)
        assertThat(LibrarySubview.GENRE_ALBUMS.sortKeys()).isEqualTo(STANDARD_SORT_KEYS)
    }

    @Test
    fun `genre artists leave out the per-track keys the artist comparator ignores`() {
        assertThat(LibrarySubview.GENRE_ARTISTS.sortKeys()).containsExactly(
            SortKey.ALPHABETICAL, SortKey.RECENTLY_ADDED, SortKey.LAST_MODIFIED,
        ).inOrder()
    }

    @Test
    fun `track lists add duration and play count on top of the standard keys`() {
        listOf(LibrarySubview.ARTIST_TRACKS, LibrarySubview.GENRE_TRACKS, LibrarySubview.PLAYLIST_TRACKS)
            .forEach { view ->
                assertThat(view.sortKeys()).containsAtLeastElementsIn(STANDARD_SORT_KEYS)
                assertThat(view.sortKeys()).contains(SortKey.DURATION)
                assertThat(view.sortKeys()).contains(SortKey.PLAY_COUNT)
            }
    }

    @Test
    fun `no subview menu offers playlist order outside playlists`() {
        LibrarySubview.entries.filter { it != LibrarySubview.PLAYLIST_TRACKS }.forEach { view ->
            assertThat(view.sortKeys()).doesNotContain(SortKey.PLAYLIST_ORDER)
        }
    }

    @Test
    fun `sort specs with the new keys survive a JSON round trip`() {
        // SortSpec is @Serializable like the settings types; keys persist by
        // name, so the round trip pins the storage format.
        SortKey.entries.forEach { key ->
            val spec = SortSpec(key, ascending = false)
            val json = Json.encodeToString(SortSpec.serializer(), spec)
            assertThat(Json.decodeFromString(SortSpec.serializer(), json)).isEqualTo(spec)
        }
    }
}
