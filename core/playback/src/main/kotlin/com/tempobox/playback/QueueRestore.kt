package com.tempobox.playback

/**
 * Pure helpers for rebuilding the persisted queue after process death, when
 * some stored rows may no longer exist in the library.
 */
object QueueRestore {

    /**
     * Where the stored current item sits after rows with missing ids were
     * dropped from [storedIds]: the count of surviving entries before
     * [storedIndex]. When the current item itself vanished, this lands on the
     * next surviving entry, which is what auto-advance would have reached.
     * Positional, so duplicate ids in the stored queue stay correct. Callers
     * still clamp to the rebuilt list's bounds.
     */
    fun remapIndex(storedIds: List<Long>, existingIds: Collection<Long>, storedIndex: Int): Int {
        val existing = existingIds.toHashSet()
        return storedIds.take(storedIndex.coerceAtLeast(0)).count { it in existing }
    }
}
