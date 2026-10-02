package com.tempobox.ui.nowplaying

/** Resting positions of the Now Playing sheet. */
enum class SheetStage { COLLAPSED, EXPANDED }

/** Which layer a vertical drag moves: the sheet itself or the queue above it. */
enum class SheetDragTarget { SHEET, QUEUE }

/** What the back button should do given the sheet's current state. */
enum class SheetBackAction { CLOSE_QUEUE, COLLAPSE, NONE }

/**
 * Pure geometry and policy for the Now Playing sheet gestures: anchor offsets,
 * drag routing between the sheet and its queue layer, settle targets, the
 * pill/full cross-fade mapping, and back-button precedence. No Android or
 * Compose types so the whole gesture model unit-tests on the JVM.
 *
 * Conventions: offsets are the layer's downward translation in pixels, so 0 is
 * fully up (expanded/shown) and the collapsed/hidden anchor is positive.
 * Positive deltas and velocities point down.
 */
object SheetMath {

    /** Sheet offset at the collapsed anchor: everything but the pill offscreen. */
    fun collapsedOffset(containerHeightPx: Float, pillHeightPx: Float): Float =
        (containerHeightPx - pillHeightPx).coerceAtLeast(0f)

    /** 0 at the collapsed anchor, 1 fully expanded. 0 while geometry is unknown. */
    fun expandFraction(offsetPx: Float, collapsedOffsetPx: Float): Float =
        if (collapsedOffsetPx <= 0f) 0f else (1f - offsetPx / collapsedOffsetPx).coerceIn(0f, 1f)

    /** The pill fades out over the first quarter of the drag up. */
    fun pillAlpha(expandFraction: Float): Float =
        (1f - expandFraction / 0.25f).coerceIn(0f, 1f)

    /** The full layout fades in over the remaining three quarters. */
    fun expandedAlpha(expandFraction: Float): Float =
        ((expandFraction - 0.25f) / 0.75f).coerceIn(0f, 1f)

    /**
     * Routes one drag delta. The queue owns the gesture while it is visible at
     * all; otherwise the sheet moves, except that dragging up on a fully
     * expanded sheet starts revealing the queue. Routing per delta is what
     * lets one continuous gesture close the queue and then keep pulling the
     * sheet down to the pill.
     */
    fun routeDrag(
        deltaY: Float,
        sheetOffsetPx: Float,
        queueOffsetPx: Float,
        queueHiddenOffsetPx: Float,
    ): SheetDragTarget = when {
        queueOffsetPx < queueHiddenOffsetPx - OFFSET_EPSILON_PX -> SheetDragTarget.QUEUE
        sheetOffsetPx > OFFSET_EPSILON_PX -> SheetDragTarget.SHEET
        deltaY < 0f -> SheetDragTarget.QUEUE
        else -> SheetDragTarget.SHEET
    }

    /**
     * Where the sheet settles when the finger lifts: a fling past the velocity
     * threshold wins in its direction, otherwise the nearest anchor.
     */
    fun settleStage(
        offsetPx: Float,
        velocityPxPerS: Float,
        collapsedOffsetPx: Float,
        velocityThresholdPxPerS: Float,
    ): SheetStage = when {
        velocityPxPerS <= -velocityThresholdPxPerS -> SheetStage.EXPANDED
        velocityPxPerS >= velocityThresholdPxPerS -> SheetStage.COLLAPSED
        offsetPx < collapsedOffsetPx / 2f -> SheetStage.EXPANDED
        else -> SheetStage.COLLAPSED
    }

    /** Same settle rule for the queue layer; true means the queue stays shown. */
    fun settleQueueShown(
        offsetPx: Float,
        velocityPxPerS: Float,
        hiddenOffsetPx: Float,
        velocityThresholdPxPerS: Float,
    ): Boolean = when {
        velocityPxPerS <= -velocityThresholdPxPerS -> true
        velocityPxPerS >= velocityThresholdPxPerS -> false
        else -> offsetPx < hiddenOffsetPx / 2f
    }

    /** Back closes the queue first, then collapses the sheet, then falls through. */
    fun backAction(expanded: Boolean, queueShown: Boolean): SheetBackAction = when {
        queueShown -> SheetBackAction.CLOSE_QUEUE
        expanded -> SheetBackAction.COLLAPSE
        else -> SheetBackAction.NONE
    }

    /** Tolerance for treating a layer as resting exactly on an anchor. */
    const val OFFSET_EPSILON_PX = 0.5f
}
