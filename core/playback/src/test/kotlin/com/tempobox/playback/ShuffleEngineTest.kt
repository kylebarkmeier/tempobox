package com.tempobox.playback

import com.google.common.truth.Truth.assertThat
import com.tempobox.model.ShuffleMode
import com.tempobox.model.Track
import org.junit.Test
import kotlin.random.Random

class ShuffleEngineTest {

    private val engine = ShuffleEngine()

    private fun pool(artists: Int, perArtist: Int, rating: (Int) -> Int = { 0 }): List<Track> =
        (0 until artists).flatMap { a ->
            (0 until perArtist).map { t ->
                Track(
                    filePath = "/m/a$a/t$t.mp3",
                    title = "Track $t",
                    artist = "Artist $a",
                    albumArtist = "Artist $a",
                    album = "Album $a",
                    rating = rating(a),
                )
            }
        }

    // ------------------------------------------------------------------ basics

    @Test
    fun `OFF returns natural order`() {
        val tracks = pool(3, 4)
        assertThat(engine.order(tracks, ShuffleMode.OFF)).isEqualTo(tracks.indices.toList())
    }

    @Test
    fun `every mode returns a valid permutation`() {
        val tracks = pool(4, 5, rating = { it + 1 })
        for (mode in ShuffleMode.entries) {
            val order = engine.order(tracks, mode, Random(7))
            assertThat(order).containsExactlyElementsIn(tracks.indices.toList())
        }
    }

    @Test
    fun `ALL is deterministic under a fixed seed`() {
        val tracks = pool(3, 5)
        assertThat(engine.order(tracks, ShuffleMode.ALL, Random(1)))
            .isEqualTo(engine.order(tracks, ShuffleMode.ALL, Random(1)))
    }

    @Test
    fun `empty and single-track pools are handled`() {
        assertThat(engine.order(emptyList(), ShuffleMode.ANTI_REPEAT)).isEmpty()
        val one = pool(1, 1)
        assertThat(engine.order(one, ShuffleMode.RATING_BIASED)).containsExactly(0)
    }

    // ------------------------------------------------------------------ anti-repeat

    @Test
    fun `anti-repeat spreads artists far better than plain random`() {
        // 18 tracks / 3 artists: plain random averages ~5.3 adjacent same-artist
        // pairs per run; balanced shuffle simulates to ~1.7. Averaging 50 seeded
        // runs keeps the assertion statistically watertight (>5σ margin at 3.5).
        val tracks = pool(3, 6)
        var totalAdjacent = 0
        repeat(50) { seed ->
            val order = engine.order(tracks, ShuffleMode.ANTI_REPEAT, Random(seed))
            totalAdjacent += countAdjacentSameArtist(tracks, order)
        }
        assertThat(totalAdjacent).isLessThan((3.5 * 50).toInt())
    }

    @Test
    fun `anti-repeat with two artists beats plain random alternation`() {
        // 2 artists × 8: plain random ⇒ 7 adjacent pairs on average; balanced
        // shuffle simulates to ~3.5. Assert the 50-run average stays below 5.
        val tracks = pool(2, 8)
        var totalAdjacent = 0
        repeat(50) { seed ->
            val order = engine.order(tracks, ShuffleMode.ANTI_REPEAT, Random(seed))
            totalAdjacent += countAdjacentSameArtist(tracks, order)
        }
        assertThat(totalAdjacent).isLessThan(5 * 50)
    }

    // ------------------------------------------------------------------ rating bias

    @Test
    fun `rating bias plays five-star tracks earlier on average`() {
        // Artist ids double as ratings: artist 0 → 1★, artist 4 → 5★.
        val tracks = pool(5, 4, rating = { it + 1 })
        val fiveStar = tracks.indices.filter { tracks[it].rating == 5 }.toSet()
        val oneStar = tracks.indices.filter { tracks[it].rating == 1 }.toSet()

        var fiveStarPos = 0L
        var oneStarPos = 0L
        repeat(100) { seed ->
            val order = engine.order(tracks, ShuffleMode.RATING_BIASED, Random(seed))
            order.forEachIndexed { position, trackIdx ->
                if (trackIdx in fiveStar) fiveStarPos += position
                if (trackIdx in oneStar) oneStarPos += position
            }
        }
        // 16× the weight ⇒ a decisive gap over 100 runs.
        assertThat(fiveStarPos).isLessThan(oneStarPos)
    }

    @Test
    fun `rating weights follow the documented curve`() {
        assertThat(ShuffleEngine.ratingWeight(0)).isEqualTo(1.0) // unrated = neutral
        assertThat(ShuffleEngine.ratingWeight(3)).isEqualTo(1.0)
        assertThat(ShuffleEngine.ratingWeight(1)).isEqualTo(0.25)
        assertThat(ShuffleEngine.ratingWeight(5)).isEqualTo(4.0)
    }

    // ------------------------------------------------------------------ helpers

    private fun countAdjacentSameArtist(tracks: List<Track>, order: List<Int>): Int =
        order.zipWithNext().count { (a, b) -> tracks[a].artist == tracks[b].artist }
}
