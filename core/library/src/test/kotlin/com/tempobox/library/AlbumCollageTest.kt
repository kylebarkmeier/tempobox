package com.tempobox.library

import com.google.common.truth.Truth.assertThat
import com.tempobox.database.pojo.CollageAlbumRow
import org.junit.Test

/** Pure JVM tests for the collage's top-album selection (genres and artists). */
class AlbumCollageTest {

    private fun row(
        key: String = "Rock",
        album: String,
        artist: String = "Artist",
        path: String = "/$album.mp3",
        plays: Long = 0,
        tracks: Int = 10,
    ) = CollageAlbumRow(
        groupKey = key,
        album = album,
        albumArtist = artist,
        artworkTrackPath = path,
        playCount = plays,
        trackCount = tracks,
    )

    @Test
    fun `most played albums come first`() {
        val paths = AlbumCollage.topArtPathsByKey(
            listOf(
                row(album = "Quiet", plays = 1),
                row(album = "Loud", plays = 9),
                row(album = "Mid", plays = 5),
            ),
        ).getValue("Rock")
        assertThat(paths).containsExactly("/Loud.mp3", "/Mid.mp3", "/Quiet.mp3").inOrder()
    }

    @Test
    fun `at most four paths per group`() {
        val rows = (1..6).map { row(album = "A$it", plays = it.toLong()) }
        val paths = AlbumCollage.topArtPathsByKey(rows).getValue("Rock")
        assertThat(paths).hasSize(4)
        assertThat(paths.first()).isEqualTo("/A6.mp3") // highest play count leads
    }

    @Test
    fun `zero play counts fall back to track count, then album name`() {
        val paths = AlbumCollage.topArtPathsByKey(
            listOf(
                row(album = "Single", tracks = 1),
                row(album = "Zebra", tracks = 12),
                row(album = "Apple", tracks = 12),
            ),
        ).getValue("Rock")
        // Same (zero) plays: more tracks win; equal tracks sort alphabetically.
        assertThat(paths).containsExactly("/Apple.mp3", "/Zebra.mp3", "/Single.mp3").inOrder()
    }

    @Test
    fun `alphabetical fallback is article-aware`() {
        val paths = AlbumCollage.topArtPathsByKey(
            listOf(
                row(album = "The Bends"),
                row(album = "Amnesiac"),
                row(album = "Creep"),
            ),
        ).getValue("Rock")
        // "The Bends" sorts under B, between Amnesiac and Creep.
        assertThat(paths)
            .containsExactly("/Amnesiac.mp3", "/The Bends.mp3", "/Creep.mp3")
            .inOrder()
    }

    @Test
    fun `groups are ranked independently`() {
        val result = AlbumCollage.topArtPathsByKey(
            listOf(
                row(key = "Rock", album = "R", plays = 1),
                row(key = "Jazz", album = "J", plays = 99),
            ),
        )
        assertThat(result.getValue("Rock")).containsExactly("/R.mp3")
        assertThat(result.getValue("Jazz")).containsExactly("/J.mp3")
    }

    @Test
    fun `selection is stable across input order`() {
        val rows = listOf(
            row(album = "B", plays = 2),
            row(album = "A", plays = 2),
            row(album = "C", plays = 2),
        )
        val forward = AlbumCollage.topArtPathsByKey(rows).getValue("Rock")
        val backward = AlbumCollage.topArtPathsByKey(rows.reversed()).getValue("Rock")
        assertThat(forward).isEqualTo(backward)
    }

    @Test
    fun `no rows yields no entry, leaving the group art empty`() {
        assertThat(AlbumCollage.topArtPathsByKey(emptyList())).isEmpty()
    }
}
