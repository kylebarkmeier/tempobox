package com.tempobox.widget

import com.google.common.truth.Truth.assertThat
import com.tempobox.R
import com.tempobox.model.RepeatMode
import com.tempobox.model.ShuffleMode
import org.junit.Test

class WidgetControlsTest {

    @Test
    fun `shuffle is active for every flavor except OFF`() {
        assertThat(WidgetControls.shuffleActive(ShuffleMode.OFF)).isFalse()
        assertThat(WidgetControls.shuffleActive(ShuffleMode.ALL)).isTrue()
        assertThat(WidgetControls.shuffleActive(ShuffleMode.ANTI_REPEAT)).isTrue()
        assertThat(WidgetControls.shuffleActive(ShuffleMode.RATING_BIASED)).isTrue()
    }

    @Test
    fun `repeat is active unless OFF`() {
        assertThat(WidgetControls.repeatActive(RepeatMode.OFF)).isFalse()
        assertThat(WidgetControls.repeatActive(RepeatMode.ALL)).isTrue()
        assertThat(WidgetControls.repeatActive(RepeatMode.ONE)).isTrue()
    }

    @Test
    fun `repeat ONE gets its own icon, ALL and OFF share the plain repeat icon`() {
        assertThat(WidgetControls.repeatIcon(RepeatMode.ONE))
            .isEqualTo(R.drawable.ic_widget_repeat_one)
        assertThat(WidgetControls.repeatIcon(RepeatMode.ALL))
            .isEqualTo(R.drawable.ic_widget_repeat)
        assertThat(WidgetControls.repeatIcon(RepeatMode.OFF))
            .isEqualTo(R.drawable.ic_widget_repeat)
    }

    @Test
    fun `play pause icon follows playing state`() {
        assertThat(WidgetControls.playPauseIcon(isPlaying = true))
            .isEqualTo(R.drawable.ic_widget_pause)
        assertThat(WidgetControls.playPauseIcon(isPlaying = false))
            .isEqualTo(R.drawable.ic_widget_play)
    }

    @Test
    fun `descriptions name the action a tap performs`() {
        assertThat(WidgetControls.shuffleDescription(ShuffleMode.OFF)).isEqualTo("Shuffle on")
        assertThat(WidgetControls.shuffleDescription(ShuffleMode.ANTI_REPEAT))
            .isEqualTo("Shuffle off")
        assertThat(WidgetControls.playPauseDescription(isPlaying = true)).isEqualTo("Pause")
        assertThat(WidgetControls.playPauseDescription(isPlaying = false)).isEqualTo("Play")
        assertThat(WidgetControls.repeatDescription(RepeatMode.ONE)).isEqualTo("Repeat mode: ONE")
    }
}
