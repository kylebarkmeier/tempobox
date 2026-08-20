package com.tempobox.playback

import com.tempobox.model.ShuffleMode
import com.tempobox.model.Track
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Produces shuffle orders. Pure & deterministic given a seeded [Random], so
 * every mode is unit-tested (spacing guarantees, bias distributions).
 *
 * Modes:
 *  - [ShuffleMode.ALL]           uniform Fisher–Yates shuffle.
 *  - [ShuffleMode.ANTI_REPEAT]   "balanced shuffle": recursively spreads
 *    artist → album → title groups so identical artists/albums/tracks land as
 *    far apart as the pool allows (product default).
 *  - [ShuffleMode.RATING_BIASED] weighted sampling without replacement
 *    (5★ ≈ 4× the weight of 3★/unrated) combined with an anti-repeat penalty
 *    against recently picked artists/albums.
 */
@Singleton
class ShuffleEngine @Inject constructor() {

    /**
     * Returns a permutation of `tracks.indices` in play order.
     * [ShuffleMode.OFF] returns the natural order unchanged.
     */
    fun order(tracks: List<Track>, mode: ShuffleMode, random: Random = Random.Default): List<Int> =
        when (mode) {
            ShuffleMode.OFF -> tracks.indices.toList()
            ShuffleMode.ALL -> tracks.indices.shuffled(random)
            ShuffleMode.ANTI_REPEAT -> balancedOrder(
                tracks.indices.toList(),
                keys = listOf(
                    { i: Int -> tracks[i].effectiveAlbumArtist.lowercase() },
                    { i: Int -> tracks[i].effectiveAlbum.lowercase() },
                    { i: Int -> tracks[i].title.lowercase() },
                ),
                random = random,
            )
            ShuffleMode.RATING_BIASED -> ratingBiasedOrder(tracks, random)
        }

    // ------------------------------------------------------------- anti-repeat

    /**
     * Balanced shuffle (after M. Fiedler's algorithm): group by the first key,
     * recursively order each group by the remaining keys, then merge groups by
     * assigning each group's k-th element the fractional position
     * `(k + r_k) / groupSize` and sorting. A group of size m thus spreads
     * ~uniformly across the whole list — maximal distance between repeats.
     */
    private fun balancedOrder(
        items: List<Int>,
        keys: List<(Int) -> String>,
        random: Random,
    ): List<Int> {
        if (items.size <= 2 || keys.isEmpty()) return items.shuffled(random)
        val key = keys.first()
        val groups = items.groupBy(key).values
            .map { balancedOrder(it, keys.drop(1), random) }
            .shuffled(random)

        data class Placed(val item: Int, val position: Double)
        return groups.flatMap { group ->
            group.mapIndexed { k, item ->
                Placed(item, (k + random.nextDouble()) / group.size)
            }
        }.sortedBy { it.position }.map { it.item }
    }

    // ------------------------------------------------------------- rating bias

    /**
     * Sequential weighted sampling without replacement. Weight of a candidate:
     * `2^(rating-3)` (unrated counts as 3★ → neutral), multiplied by 0.25 when
     * its artist, or 0.5 when its album, occurred within the last
     * [ANTI_REPEAT_WINDOW] picks.
     */
    private fun ratingBiasedOrder(tracks: List<Track>, random: Random): List<Int> {
        val remaining = tracks.indices.toMutableList()
        val result = ArrayList<Int>(tracks.size)
        val recentArtists = ArrayDeque<String>()
        val recentAlbums = ArrayDeque<String>()

        while (remaining.isNotEmpty()) {
            val weights = remaining.map { i ->
                val t = tracks[i]
                var w = ratingWeight(t.rating)
                if (t.effectiveAlbumArtist.lowercase() in recentArtists) w *= 0.25
                if ("${t.effectiveAlbumArtist}|${t.effectiveAlbum}".lowercase() in recentAlbums) w *= 0.5
                w
            }
            val pickIdx = weightedPick(weights, random)
            val picked = remaining.removeAt(pickIdx)
            result += picked

            val t = tracks[picked]
            recentArtists.addLast(t.effectiveAlbumArtist.lowercase())
            recentAlbums.addLast("${t.effectiveAlbumArtist}|${t.effectiveAlbum}".lowercase())
            while (recentArtists.size > ANTI_REPEAT_WINDOW) recentArtists.removeFirst()
            while (recentAlbums.size > ANTI_REPEAT_WINDOW) recentAlbums.removeFirst()
        }
        return result
    }

    private fun weightedPick(weights: List<Double>, random: Random): Int {
        val total = weights.sum()
        if (total <= 0) return random.nextInt(weights.size)
        var roll = random.nextDouble() * total
        weights.forEachIndexed { index, w ->
            roll -= w
            if (roll <= 0) return index
        }
        return weights.lastIndex
    }

    companion object {
        private const val ANTI_REPEAT_WINDOW = 8

        /** 1★→0.25 … 3★/unrated→1.0 … 5★→4.0 */
        fun ratingWeight(rating: Int): Double {
            val effective = if (rating == 0) 3 else rating.coerceIn(1, 5)
            return Math.pow(2.0, (effective - 3).toDouble())
        }
    }
}
