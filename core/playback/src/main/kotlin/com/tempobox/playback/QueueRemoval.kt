package com.tempobox.playback

import androidx.media3.common.Player

/**
 * Removes queue rows within [QueueReorder]'s op budget. Shared by
 * `PlayerConnection` (MediaController side, user-initiated removals and
 * permanent deletes) and `PlaybackService` (player side, pruning entries
 * whose files vanished outside the app), so both obey the same
 * Bluetooth-flood ceiling.
 */
internal object QueueRemoval {

    /**
     * Removes [removedIndices] (valid indices into the current timeline) in at
     * most [QueueReorder.MAX_OPS] timeline operations: contiguous ranges
     * back-to-front while they fit the budget, else a rebuild anchored on the
     * playing item when it survives, or the next surviving item (what
     * auto-advance would reach) when it does not.
     */
    fun removeIndices(player: Player, removedIndices: List<Int>) {
        if (removedIndices.isEmpty()) return
        val ranges = QueueReorder.descendingRanges(removedIndices)
        if (ranges.size <= QueueReorder.MAX_OPS) {
            // Back-to-front, one timeline change per contiguous run (each
            // change rebroadcasts the whole queue — see QueueReorder).
            ranges.forEach { player.removeMediaItems(it.first, it.last + 1) }
            return
        }
        rebuildWithout(player, removedIndices.toHashSet())
    }

    private fun rebuildWithout(player: Player, removedIndices: Set<Int>) {
        val count = player.mediaItemCount
        val survivors = (0 until count)
            .filterNot { it in removedIndices }
            .map { player.getMediaItemAt(it) }
        if (survivors.isEmpty()) {
            player.clearMediaItems()
            return
        }
        val current = player.currentMediaItemIndex
        val anchorIndex = when {
            current in 0 until count && current !in removedIndices -> current
            else ->
                ((current + 1) until count).firstOrNull { it !in removedIndices }
                    ?: (0 until count).first { it !in removedIndices }
        }
        val anchorId = player.getMediaItemAt(anchorIndex).mediaId
        val targetIndex = survivors.indexOfFirst { it.mediaId == anchorId }
        if (anchorIndex != 0) player.moveMediaItem(anchorIndex, 0)
        player.removeMediaItems(1, player.mediaItemCount)
        // Items re-added through a MediaController lose their file URI
        // crossing the binder; the service rebuilds it from the path in the
        // metadata extras (see PlayerConnection.applyOrder). Re-adds on the
        // service's own ExoPlayer keep their URIs.
        player.addMediaItems(survivors.filterIndexed { i, _ -> i != targetIndex })
        if (targetIndex > 0) player.moveMediaItem(0, targetIndex)
    }
}
