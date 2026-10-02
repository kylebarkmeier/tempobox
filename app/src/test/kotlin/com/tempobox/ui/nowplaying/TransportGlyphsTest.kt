package com.tempobox.ui.nowplaying

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.filled.RepeatOneOn
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ShuffleOn
import com.google.common.truth.Truth.assertThat
import com.tempobox.model.RepeatMode
import com.tempobox.model.ShuffleMode
import org.junit.Test

class TransportGlyphsTest {

    @Test
    fun `shuffle uses the boxed ON glyph for every flavor except OFF`() {
        assertThat(TransportGlyphs.shuffleIcon(ShuffleMode.OFF))
            .isSameInstanceAs(Icons.Filled.Shuffle)
        assertThat(TransportGlyphs.shuffleIcon(ShuffleMode.ALL))
            .isSameInstanceAs(Icons.Filled.ShuffleOn)
        assertThat(TransportGlyphs.shuffleIcon(ShuffleMode.ANTI_REPEAT))
            .isSameInstanceAs(Icons.Filled.ShuffleOn)
        assertThat(TransportGlyphs.shuffleIcon(ShuffleMode.RATING_BIASED))
            .isSameInstanceAs(Icons.Filled.ShuffleOn)
    }

    @Test
    fun `repeat uses the boxed ON glyphs for ALL and ONE, plain for OFF`() {
        assertThat(TransportGlyphs.repeatIcon(RepeatMode.OFF))
            .isSameInstanceAs(Icons.Filled.Repeat)
        assertThat(TransportGlyphs.repeatIcon(RepeatMode.ALL))
            .isSameInstanceAs(Icons.Filled.RepeatOn)
        assertThat(TransportGlyphs.repeatIcon(RepeatMode.ONE))
            .isSameInstanceAs(Icons.Filled.RepeatOneOn)
    }

    @Test
    fun `on and off glyphs differ, so state never depends on tint alone`() {
        assertThat(TransportGlyphs.shuffleIcon(ShuffleMode.ALL))
            .isNotEqualTo(TransportGlyphs.shuffleIcon(ShuffleMode.OFF))
        assertThat(TransportGlyphs.repeatIcon(RepeatMode.ALL))
            .isNotEqualTo(TransportGlyphs.repeatIcon(RepeatMode.OFF))
        assertThat(TransportGlyphs.repeatIcon(RepeatMode.ONE))
            .isNotEqualTo(TransportGlyphs.repeatIcon(RepeatMode.ALL))
    }
}
