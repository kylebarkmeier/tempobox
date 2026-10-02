package com.tempobox.playback

/**
 * Plans timeline mutations so a full queue reorder costs O(1) timeline
 * operations instead of one per track.
 *
 * Why this matters: every timeline change makes Media3 rebuild the ENTIRE
 * queue and broadcast it to the platform session, which fans it out to every
 * legacy controller — Bluetooth AVRCP (car head units, headsets), SystemUI,
 * etc. Reordering an n-track queue with n `moveMediaItem` calls therefore
 * parcels ~n² queue items (a 430-track shuffle toggle ≈ 185k items, tens of
 * MB), which can OOM the Bluetooth stack and ANR the app inside
 * `MediaSession.setQueue`. The plans produced here mutate the timeline at most
 * [MAX_OPS] times, never touching the playing item, so playback is never
 * interrupted.
 */
object QueueReorder {

    /** Upper bound on timeline operations any plan may require. */
    const val MAX_OPS = 4

    /** A single `moveMediaItem(from, to)`. */
    data class Move(val from: Int, val to: Int)

    /**
     * Executed in order:
     *  1. [anchorToFront] — move the playing item to index 0 (null when
     *     already there or [anchored] is false)
     *  2. remove every other item: `removeMediaItems(1, count)` when
     *     [anchored], else `removeMediaItems(0, count)`
     *  3. re-add [tail] (uids, in final order, excluding the anchor)
     *  4. [anchorToTarget] — move the anchor from 0 to its final index
     */
    data class Plan(
        val anchored: Boolean,
        val anchorToFront: Move?,
        val tail: List<Long>,
        val anchorToTarget: Move?,
    )

    /**
     * Plans reordering [current] into [target], keeping [anchorUid] (the
     * playing item) in the timeline through every step.
     *
     * Target hygiene (matches the old move-based behavior, which could neither
     * add nor drop items): uids in [target] unknown to [current] are ignored;
     * uids in [current] missing from [target] are appended at the end in their
     * current relative order.
     *
     * Returns null when the queue is already in the target order (no-op).
     */
    fun plan(current: List<Long>, target: List<Long>, anchorUid: Long?): Plan? {
        if (current.isEmpty()) return null
        val currentSet = current.toSet()
        val kept = LinkedHashSet<Long>(target.filter { it in currentSet })
        val effective = kept.toList() + current.filter { it !in kept }
        if (effective == current) return null

        if (anchorUid == null || anchorUid !in currentSet) {
            return Plan(anchored = false, anchorToFront = null, tail = effective, anchorToTarget = null)
        }
        val anchorIdx = current.indexOf(anchorUid)
        val targetIdx = effective.indexOf(anchorUid)
        return Plan(
            anchored = true,
            anchorToFront = Move(anchorIdx, 0).takeIf { anchorIdx != 0 },
            tail = effective.filter { it != anchorUid },
            anchorToTarget = Move(0, targetIdx).takeIf { targetIdx != 0 },
        )
    }

    /**
     * Collapses removal [indices] into contiguous ranges, ordered back-to-front
     * so earlier removals never shift later ones. Turns a bulk queue removal
     * into one `removeMediaItems(first, last + 1)` per contiguous run instead
     * of one timeline change per item (same Bluetooth-flood concern as [plan]).
     */
    fun descendingRanges(indices: Collection<Int>): List<IntRange> {
        val sorted = indices.toSortedSet().toList()
        if (sorted.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var start = sorted.first()
        var prev = start
        for (i in sorted.drop(1)) {
            if (i != prev + 1) {
                ranges += start..prev
                start = i
            }
            prev = i
        }
        ranges += start..prev
        return ranges.asReversed()
    }
}
