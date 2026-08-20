package com.tempobox.playback

/**
 * Detects triple presses of a volume key from the stream-volume change events
 * the service observes (Settings ▸ Bluetooth ▸ Volume buttons).
 *
 * Pure state machine — timestamps are injected so unit tests control time.
 * Three same-direction changes, each within [windowMs] of the previous,
 * trigger once (then the sequence resets).
 *
 * Known limitation (documented in Settings): a press at min/max volume emits
 * no change event, so a sequence can't start at the volume boundary.
 */
class VolumeTripleTapDetector(
    private val windowMs: Long = 900,
    private val requiredTaps: Int = 3,
) {
    enum class Direction { UP, DOWN }

    private var lastDirection: Direction? = null
    private var lastAtMs: Long = 0
    private var count = 0

    /** Taps in the current sequence (1 right after a sequence starts). */
    val currentCount: Int get() = count

    /**
     * Feed one volume change. Returns the [Direction] whose triple-tap just
     * completed, or null.
     */
    fun onVolumeChange(direction: Direction, nowMs: Long): Direction? {
        count = if (direction == lastDirection && nowMs - lastAtMs <= windowMs) count + 1 else 1
        lastDirection = direction
        lastAtMs = nowMs

        return if (count >= requiredTaps) {
            reset()
            direction
        } else {
            null
        }
    }

    fun reset() {
        lastDirection = null
        lastAtMs = 0
        count = 0
    }
}
