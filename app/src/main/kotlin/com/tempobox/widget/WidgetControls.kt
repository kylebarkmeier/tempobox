package com.tempobox.widget

import androidx.annotation.DrawableRes
import com.tempobox.R
import com.tempobox.model.RepeatMode
import com.tempobox.model.ShuffleMode

/**
 * Pure state → presentation mapping for the widget's control row.
 *
 * Mirrors the in-app Now Playing transport buttons (`NowPlayingScreen`):
 * shuffle and repeat render "active" (primary tint) whenever their mode is
 * anything but OFF, and repeat ONE gets its own icon. Kept free of Glance so
 * it unit-tests on the JVM.
 */
internal object WidgetControls {

    /** Shuffle shows active for any flavor (ALL / ANTI_REPEAT / RATING_BIASED). */
    fun shuffleActive(mode: ShuffleMode): Boolean = mode != ShuffleMode.OFF

    fun repeatActive(mode: RepeatMode): Boolean = mode != RepeatMode.OFF

    @DrawableRes
    fun repeatIcon(mode: RepeatMode): Int = when (mode) {
        RepeatMode.ONE -> R.drawable.ic_widget_repeat_one
        RepeatMode.ALL, RepeatMode.OFF -> R.drawable.ic_widget_repeat
    }

    @DrawableRes
    fun playPauseIcon(isPlaying: Boolean): Int =
        if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play

    /** Descriptions name the action a tap performs, matching the in-app buttons. */
    fun shuffleDescription(mode: ShuffleMode): String =
        if (mode == ShuffleMode.OFF) "Shuffle on" else "Shuffle off"

    fun repeatDescription(mode: RepeatMode): String = "Repeat mode: $mode"

    fun playPauseDescription(isPlaying: Boolean): String = if (isPlaying) "Pause" else "Play"
}
