package com.tempobox.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScrollbarMathTest {

    // A canonical list: 100 lines of 100 px in a 1000 px viewport, so content
    // is 10000 px, max scroll 9000 px, thumb 10% of the track.
    private val lines = 100
    private val linePx = 100f
    private val viewport = 1000f

    // ------------------------------------------------------------ thumb

    @Test
    fun `thumb at the top has offset 0`() {
        val thumb = ScrollbarMath.thumb(lines, 0, 0f, linePx, viewport)!!
        assertThat(thumb.offsetFraction).isEqualTo(0f)
        assertThat(thumb.sizeFraction).isWithin(1e-4f).of(0.1f)
    }

    @Test
    fun `thumb at the bottom ends exactly at the track end`() {
        // Deepest scroll: first visible line 90, no offset into it.
        val thumb = ScrollbarMath.thumb(lines, 90, 0f, linePx, viewport)!!
        assertThat(thumb.offsetFraction + thumb.sizeFraction).isWithin(1e-4f).of(1f)
    }

    @Test
    fun `thumb midway sits midway through its travel`() {
        // 4500 px of 9000 px scrolled: line 45, no offset.
        val thumb = ScrollbarMath.thumb(lines, 45, 0f, linePx, viewport)!!
        assertThat(thumb.offsetFraction).isWithin(1e-4f).of(0.5f * 0.9f)
    }

    @Test
    fun `partial line offsets move the thumb between lines`() {
        val atLine = ScrollbarMath.thumb(lines, 10, 0f, linePx, viewport)!!
        val between = ScrollbarMath.thumb(lines, 10, 50f, linePx, viewport)!!
        val nextLine = ScrollbarMath.thumb(lines, 11, 0f, linePx, viewport)!!
        assertThat(between.offsetFraction).isGreaterThan(atLine.offsetFraction)
        assertThat(between.offsetFraction).isLessThan(nextLine.offsetFraction)
    }

    @Test
    fun `thumb never shrinks below the minimum grabbable size`() {
        val thumb = ScrollbarMath.thumb(100_000, 0, 0f, linePx, viewport)!!
        assertThat(thumb.sizeFraction).isEqualTo(ScrollbarMath.MIN_THUMB_FRACTION)
    }

    @Test
    fun `thumb offset clamps overscroll readings`() {
        // Layout reports deeper than max scroll (e.g. stale frame): clamp to 1.
        val thumb = ScrollbarMath.thumb(lines, 99, 99f, linePx, viewport)!!
        assertThat(thumb.offsetFraction + thumb.sizeFraction).isWithin(1e-3f).of(1f)
    }

    // ------------------------------------------------------------ degenerate

    @Test
    fun `no thumb for an empty list`() {
        assertThat(ScrollbarMath.thumb(0, 0, 0f, linePx, viewport)).isNull()
    }

    @Test
    fun `no thumb when everything is already visible`() {
        assertThat(ScrollbarMath.thumb(5, 0, 0f, linePx, viewport)).isNull()
        // Exactly filling the viewport scrolls nowhere either.
        assertThat(ScrollbarMath.thumb(10, 0, 0f, linePx, viewport)).isNull()
    }

    @Test
    fun `no thumb before the layout has measured`() {
        assertThat(ScrollbarMath.thumb(lines, 0, 0f, 0f, viewport)).isNull()
        assertThat(ScrollbarMath.thumb(lines, 0, 0f, linePx, 0f)).isNull()
    }

    // ------------------------------------------------------------ drag clamp

    @Test
    fun `dragged thumb stays on the track`() {
        assertThat(ScrollbarMath.draggedThumbOffset(0.5f, -2f, 0.1f)).isEqualTo(0f)
        assertThat(ScrollbarMath.draggedThumbOffset(0.5f, 2f, 0.1f)).isWithin(1e-4f).of(0.9f)
        assertThat(ScrollbarMath.draggedThumbOffset(0.3f, 0.2f, 0.1f)).isWithin(1e-4f).of(0.5f)
    }

    @Test
    fun `dragged thumb handles a thumb larger than the track`() {
        assertThat(ScrollbarMath.draggedThumbOffset(0f, 0.5f, 1.5f)).isEqualTo(0f)
    }

    // ------------------------------------------------------------ drag target

    @Test
    fun `drag to the top targets the first line`() {
        val target = ScrollbarMath.dragTarget(0f, 0.1f, lines, linePx, viewport)
        assertThat(target.line).isEqualTo(0)
        assertThat(target.offsetPx).isEqualTo(0)
    }

    @Test
    fun `drag to the bottom targets the deepest scroll position`() {
        // Thumb top at 0.9 (= 1 - size): max scroll 9000 px = line 90.
        val target = ScrollbarMath.dragTarget(0.9f, 0.1f, lines, linePx, viewport)
        assertThat(target.line).isEqualTo(90)
        assertThat(target.offsetPx).isEqualTo(0)
    }

    @Test
    fun `drag midway targets the middle of the scroll range`() {
        val target = ScrollbarMath.dragTarget(0.45f, 0.1f, lines, linePx, viewport)
        assertThat(target.line).isEqualTo(45)
        assertThat(target.offsetPx).isEqualTo(0)
    }

    @Test
    fun `drag between lines splits into line plus pixel offset`() {
        // Scroll fraction 0.505 of 9000 px = 4545 px = line 45 + 45 px.
        val target = ScrollbarMath.dragTarget(0.4545f, 0.1f, lines, linePx, viewport)
        assertThat(target.line).isEqualTo(45)
        assertThat(target.offsetPx).isEqualTo(45)
    }

    @Test
    fun `drag target clamps past both ends`() {
        assertThat(ScrollbarMath.dragTarget(-0.5f, 0.1f, lines, linePx, viewport))
            .isEqualTo(ScrollbarMath.DragTarget(0, 0))
        val past = ScrollbarMath.dragTarget(5f, 0.1f, lines, linePx, viewport)
        assertThat(past.line).isEqualTo(90)
    }

    @Test
    fun `drag target is safe on degenerate input`() {
        assertThat(ScrollbarMath.dragTarget(0.5f, 0.1f, 0, linePx, viewport))
            .isEqualTo(ScrollbarMath.DragTarget(0, 0))
        assertThat(ScrollbarMath.dragTarget(0.5f, 0.1f, lines, 0f, viewport))
            .isEqualTo(ScrollbarMath.DragTarget(0, 0))
        // Thumb filling the track: nothing to scroll, stay at the top.
        assertThat(ScrollbarMath.dragTarget(0f, 1f, lines, linePx, viewport))
            .isEqualTo(ScrollbarMath.DragTarget(0, 0))
    }

    @Test
    fun `thumb and drag target round-trip`() {
        for (line in listOf(0, 7, 23, 50, 77, 90)) {
            val thumb = ScrollbarMath.thumb(lines, line, 0f, linePx, viewport)!!
            val target = ScrollbarMath.dragTarget(
                thumb.offsetFraction, thumb.sizeFraction, lines, linePx, viewport,
            )
            assertThat(target.line).isEqualTo(line)
        }
    }

    // ------------------------------------------------------------ grid lines

    @Test
    fun `line count rounds up partial grid rows`() {
        assertThat(ScrollbarMath.lineCount(10, 3)).isEqualTo(4)
        assertThat(ScrollbarMath.lineCount(9, 3)).isEqualTo(3)
        assertThat(ScrollbarMath.lineCount(1, 3)).isEqualTo(1)
    }

    @Test
    fun `line count is safe on degenerate input`() {
        assertThat(ScrollbarMath.lineCount(0, 3)).isEqualTo(0)
        assertThat(ScrollbarMath.lineCount(10, 0)).isEqualTo(0)
    }
}
