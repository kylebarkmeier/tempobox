package com.tempobox.ui.components

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Pure geometry for the fast scrollbar: thumb position/size from the lazy
 * layout's visible window, and the reverse mapping from a dragged thumb back
 * to a list position. Item heights vary, so everything works on an average
 * visible line height; the approximation is exact for uniform lists and stays
 * monotonic for mixed ones. No Android or Compose types, so it unit-tests on
 * the JVM (CLAUDE.md rule 4).
 *
 * A "line" is one scrollable row: an item in a LazyColumn, a full row in a
 * LazyVerticalGrid. Fractions are of the track height, 0 at the top.
 */
object ScrollbarMath {

    /** Thumb geometry: top edge and height as fractions of the track. */
    data class Thumb(val offsetFraction: Float, val sizeFraction: Float)

    /** A drag's list target: first visible line plus pixels scrolled into it. */
    data class DragTarget(val line: Int, val offsetPx: Int)

    /** Keeps the thumb grabbable on long lists. */
    const val MIN_THUMB_FRACTION = 0.10f

    /** Grid rows for [totalItems] laid out [columns] wide; 0 when unknown. */
    fun lineCount(totalItems: Int, columns: Int): Int =
        if (columns <= 0 || totalItems <= 0) 0 else ceil(totalItems / columns.toFloat()).toInt()

    /**
     * Thumb for the current scroll position, or null when there is nothing to
     * scroll (empty list, all lines already visible, geometry unknown).
     */
    fun thumb(
        totalLines: Int,
        firstVisibleLine: Int,
        firstLineOffsetPx: Float,
        avgLinePx: Float,
        viewportPx: Float,
    ): Thumb? {
        if (totalLines <= 0 || avgLinePx <= 0f || viewportPx <= 0f) return null
        val contentPx = totalLines * avgLinePx
        val maxScrollPx = contentPx - viewportPx
        if (maxScrollPx < 1f) return null
        val size = (viewportPx / contentPx).coerceIn(MIN_THUMB_FRACTION, 1f)
        val scrolledPx = firstVisibleLine * avgLinePx + firstLineOffsetPx
        val scrollFraction = (scrolledPx / maxScrollPx).coerceIn(0f, 1f)
        return Thumb(offsetFraction = scrollFraction * (1f - size), sizeFraction = size)
    }

    /** New thumb top after a drag, clamped so the thumb stays on the track. */
    fun draggedThumbOffset(
        startOffsetFraction: Float,
        dragDeltaFraction: Float,
        thumbSizeFraction: Float,
    ): Float = (startOffsetFraction + dragDeltaFraction)
        .coerceIn(0f, (1f - thumbSizeFraction).coerceAtLeast(0f))

    /**
     * Maps a thumb top back to the scroll position it stands for. The top of
     * the track is the first line, the bottom of the travel range is the
     * deepest reachable scroll (last lines filling the viewport).
     */
    fun dragTarget(
        thumbOffsetFraction: Float,
        thumbSizeFraction: Float,
        totalLines: Int,
        avgLinePx: Float,
        viewportPx: Float,
    ): DragTarget {
        if (totalLines <= 0 || avgLinePx <= 0f) return DragTarget(0, 0)
        val travel = 1f - thumbSizeFraction
        val scrollFraction =
            if (travel <= 0f) 0f else (thumbOffsetFraction / travel).coerceIn(0f, 1f)
        val maxScrollPx = (totalLines * avgLinePx - viewportPx).coerceAtLeast(0f)
        val scrolledPx = scrollFraction * maxScrollPx
        // Epsilon keeps float round-trips (thumb -> fraction -> target) from
        // landing a hair under a line boundary and flooring one line short.
        val line = floor(scrolledPx / avgLinePx + 1e-4f).toInt().coerceIn(0, totalLines - 1)
        val offsetPx = (scrolledPx - line * avgLinePx).toInt().coerceAtLeast(0)
        return DragTarget(line, offsetPx)
    }
}
