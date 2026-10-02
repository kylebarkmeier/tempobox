package com.tempobox.ui.nowplaying

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SheetMathTest {

    private val container = 2000f
    private val pill = 200f
    private val collapsed = 1800f // container - pill
    private val queueHidden = 2000f
    private val vThreshold = 300f

    // ------------------------------------------------------------ anchors

    @Test
    fun `collapsed offset leaves exactly the pill on screen`() {
        assertThat(SheetMath.collapsedOffset(container, pill)).isEqualTo(collapsed)
    }

    @Test
    fun `collapsed offset never goes negative`() {
        assertThat(SheetMath.collapsedOffset(100f, 500f)).isEqualTo(0f)
    }

    // ------------------------------------------------------------ fractions

    @Test
    fun `expand fraction maps collapsed to 0 and expanded to 1`() {
        assertThat(SheetMath.expandFraction(collapsed, collapsed)).isEqualTo(0f)
        assertThat(SheetMath.expandFraction(0f, collapsed)).isEqualTo(1f)
        assertThat(SheetMath.expandFraction(collapsed / 2f, collapsed)).isEqualTo(0.5f)
    }

    @Test
    fun `expand fraction is 0 while geometry is unknown`() {
        assertThat(SheetMath.expandFraction(0f, 0f)).isEqualTo(0f)
    }

    @Test
    fun `expand fraction clamps overdrag`() {
        assertThat(SheetMath.expandFraction(-50f, collapsed)).isEqualTo(1f)
        assertThat(SheetMath.expandFraction(collapsed + 50f, collapsed)).isEqualTo(0f)
    }

    @Test
    fun `pill and full layouts never show fully at the same time`() {
        assertThat(SheetMath.pillAlpha(0f)).isEqualTo(1f)
        assertThat(SheetMath.pillAlpha(0.25f)).isEqualTo(0f)
        assertThat(SheetMath.pillAlpha(1f)).isEqualTo(0f)
        assertThat(SheetMath.expandedAlpha(0f)).isEqualTo(0f)
        assertThat(SheetMath.expandedAlpha(0.25f)).isEqualTo(0f)
        assertThat(SheetMath.expandedAlpha(1f)).isEqualTo(1f)
        // Midway: pill gone, full layout partially in.
        assertThat(SheetMath.pillAlpha(0.5f)).isEqualTo(0f)
        assertThat(SheetMath.expandedAlpha(0.5f)).isWithin(1e-4f).of(1f / 3f)
    }

    // ------------------------------------------------------------ drag routing

    @Test
    fun `queue owns the drag while it is visible at all`() {
        // Fully shown.
        assertThat(SheetMath.routeDrag(50f, 0f, 0f, queueHidden))
            .isEqualTo(SheetDragTarget.QUEUE)
        // Partially dismissed, dragging back up.
        assertThat(SheetMath.routeDrag(-50f, 0f, 1200f, queueHidden))
            .isEqualTo(SheetDragTarget.QUEUE)
    }

    @Test
    fun `sheet moves while it is between anchors`() {
        assertThat(SheetMath.routeDrag(-50f, 900f, queueHidden, queueHidden))
            .isEqualTo(SheetDragTarget.SHEET)
        assertThat(SheetMath.routeDrag(50f, 900f, queueHidden, queueHidden))
            .isEqualTo(SheetDragTarget.SHEET)
    }

    @Test
    fun `dragging up on the expanded sheet starts revealing the queue`() {
        assertThat(SheetMath.routeDrag(-50f, 0f, queueHidden, queueHidden))
            .isEqualTo(SheetDragTarget.QUEUE)
    }

    @Test
    fun `dragging down on the expanded sheet collapses it`() {
        assertThat(SheetMath.routeDrag(50f, 0f, queueHidden, queueHidden))
            .isEqualTo(SheetDragTarget.SHEET)
    }

    @Test
    fun `dragging up from the pill expands the sheet, not the queue`() {
        assertThat(SheetMath.routeDrag(-50f, collapsed, queueHidden, queueHidden))
            .isEqualTo(SheetDragTarget.SHEET)
    }

    // ------------------------------------------------------------ settling

    @Test
    fun `slow release settles the sheet to the nearest anchor`() {
        assertThat(SheetMath.settleStage(400f, 0f, collapsed, vThreshold))
            .isEqualTo(SheetStage.EXPANDED)
        assertThat(SheetMath.settleStage(1400f, 0f, collapsed, vThreshold))
            .isEqualTo(SheetStage.COLLAPSED)
    }

    @Test
    fun `a fling beats position`() {
        // Barely moved down from expanded but flung hard: collapse.
        assertThat(SheetMath.settleStage(100f, vThreshold, collapsed, vThreshold))
            .isEqualTo(SheetStage.COLLAPSED)
        // Barely moved up from collapsed but flung hard: expand.
        assertThat(SheetMath.settleStage(1700f, -vThreshold, collapsed, vThreshold))
            .isEqualTo(SheetStage.EXPANDED)
    }

    @Test
    fun `sub-threshold velocity does not override position`() {
        assertThat(SheetMath.settleStage(400f, vThreshold - 1f, collapsed, vThreshold))
            .isEqualTo(SheetStage.EXPANDED)
    }

    @Test
    fun `queue settles by the same rule`() {
        assertThat(SheetMath.settleQueueShown(400f, 0f, queueHidden, vThreshold)).isTrue()
        assertThat(SheetMath.settleQueueShown(1600f, 0f, queueHidden, vThreshold)).isFalse()
        assertThat(SheetMath.settleQueueShown(100f, vThreshold, queueHidden, vThreshold)).isFalse()
        assertThat(SheetMath.settleQueueShown(1900f, -vThreshold, queueHidden, vThreshold)).isTrue()
    }

    // ------------------------------------------------------------ back precedence

    @Test
    fun `back closes the queue before collapsing the sheet`() {
        assertThat(SheetMath.backAction(expanded = true, queueShown = true))
            .isEqualTo(SheetBackAction.CLOSE_QUEUE)
        assertThat(SheetMath.backAction(expanded = true, queueShown = false))
            .isEqualTo(SheetBackAction.COLLAPSE)
        assertThat(SheetMath.backAction(expanded = false, queueShown = false))
            .isEqualTo(SheetBackAction.NONE)
    }
}
