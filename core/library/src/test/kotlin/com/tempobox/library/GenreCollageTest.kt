package com.tempobox.library

import com.google.common.truth.Truth.assertThat
import com.tempobox.database.pojo.GenreAlbumArtRow
import org.junit.Test

/** Pure JVM tests for the genre collage's top-album selection. */
class GenreCollageTest {

    private fun row(
        genre: String = "Rock",
        album: String,
        artist: String = "Artist",
        path: String = "/$album.mp3",
        plays: Long = 0,
        tracks: Int = 10,
    ) = GenreAlbumArtRow(
        genreName = genre,
        album = album,
        albumArtist = artist,
        artworkTrackPath = path,
        playCount = plays,
        trackCount = tracks,
    )

    @Test
    fun `most played albums come first`() {
        val paths = GenreCollage.topArtPathsByGenre(
            listOf(
                row(album = "Quiet", plays = 1),
                row(album = "Loud", plays = 9),
                row(album = "Mid", plays = 5),
            ),
        ).getValue("Rock")
        assertThat(paths).containsExactly("/Loud.mp3", "/Mid.mp3", "/Quiet.mp3").inOrder()
    }

    @Test
    fun `at most four paths per genre`() {
        val rows = (1..6).map { row(album = "A$it", plays = it.toLong()) }
        val paths = GenreCollage.topArtPathsByGenre(rows).getValue("Rock")
        assertThat(paths).hasSize(4)
        assertThat(paths.first()).isEqualTo("/A6.mp3") // highest play count leads
    }

    @Test
    fun `zero play counts fall back to track count, then album name`() {
        val paths = GenreCollage.topArtPathsByGenre(
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
        val paths = GenreCollage.topArtPathsByGenre(
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
    fun `genres are ranked independently`() {
        val result = GenreCollage.topArtPathsByGenre(
            listOf(
                row(genre = "Rock", album = "R", plays = 1),
                row(genre = "Jazz", album = "J", plays = 99),
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
        val forward = GenreCollage.topArtPathsByGenre(rows).getValue("Rock")
        val backward = GenreCollage.topArtPathsByGenre(rows.reversed()).getValue("Rock")
        assertThat(forward).isEqualTo(backward)
    }

    @Test
    fun `no rows yields no entry, leaving the genre art empty`() {
        assertThat(GenreCollage.topArtPathsByGenre(emptyList())).isEmpty()
    }
}
