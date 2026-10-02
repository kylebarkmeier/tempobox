package com.tempobox.ui.nowplaying

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.RepeatOneOn
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ShuffleOn
import androidx.compose.ui.graphics.vector.ImageVector
import com.tempobox.model.RepeatMode
import com.tempobox.model.ShuffleMode

/**
 * Pure state → glyph mapping for the Now Playing shuffle and repeat toggles.
 *
 * Active modes use the Material "on" glyph variants (icon inside a filled
 * box) so the state is readable as a shape, not just a tint: a tint-only
 * active state disappears for red-green colorblind users whenever the theme
 * primary is green or red (two of the preset swatches, and any Material You
 * wallpaper in that range). Kept free of composables so it unit-tests on
 * the JVM.
 */
internal object TransportGlyphs {

    /** Any shuffle flavor (ALL / ANTI_REPEAT / RATING_BIASED) counts as on. */
    fun shuffleIcon(mode: ShuffleMode): ImageVector =
        if (mode != ShuffleMode.OFF) Icons.Filled.ShuffleOn else Icons.Filled.Shuffle

    fun repeatIcon(mode: RepeatMode): ImageVector = when (mode) {
        RepeatMode.OFF -> Icons.Filled.Repeat
        RepeatMode.ALL -> Icons.Filled.RepeatOn
        RepeatMode.ONE -> Icons.Filled.RepeatOneOn
    }
}
