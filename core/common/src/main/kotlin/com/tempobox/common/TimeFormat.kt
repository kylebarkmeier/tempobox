package com.tempobox.common

import java.util.Locale

/**
 * Duration formatting used everywhere a track time is shown
 * (mini player, now playing, queue rows, album summaries).
 */
object TimeFormat {

    /**
     * Millis → "m:ss" (or "h:mm:ss" for 1h+). Negative input clamps to 0:00.
     *
     * Examples: 0 → "0:00", 61_000 → "1:01", 3_600_000 → "1:00:00".
     */
    fun duration(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0)) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /**
     * Remaining time shown when the user taps the elapsed counter in Now
     * Playing: "-m:ss" counting down. Never returns below "-0:00".
     */
    fun remaining(positionMs: Long, durationMs: Long): String =
        "-" + duration((durationMs - positionMs).coerceAtLeast(0))
}
