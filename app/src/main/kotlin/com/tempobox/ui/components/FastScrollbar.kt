package com.tempobox.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * [LazyColumn] with a draggable fast scrollbar overlaid at the right edge.
 * Drop-in for plain LazyColumn call sites; [modifier] sizes the whole overlay
 * box. Attach only where lists can grow with the library; short fixed lists
 * (settings, dialogs) don't need it.
 */
@Composable
fun FastScrollLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyListScope.() -> Unit,
) {
    Box(modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = contentPadding,
            content = content,
        )
        FastScrollbar(
            adapter = remember(state) { ListScrollbarAdapter(state) },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

/** [LazyVerticalGrid] twin of [FastScrollLazyColumn]; the thumb maps to rows. */
@Composable
fun FastScrollLazyVerticalGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyGridScope.() -> Unit,
) {
    Box(modifier) {
        LazyVerticalGrid(
            columns = columns,
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = horizontalArrangement,
            verticalArrangement = verticalArrangement,
            content = content,
        )
        FastScrollbar(
            adapter = remember(state) { GridScrollbarAdapter(state) },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

/**
 * What the scrollbar needs from a lazy layout, in [ScrollbarMath]'s "line"
 * terms (item for a list, row for a grid). Properties read Compose snapshot
 * state, so observers recompose as the layout scrolls.
 */
internal interface ScrollbarAdapter {
    val totalLines: Int
    val firstVisibleLine: Int
    val firstLineOffsetPx: Float
    val avgLinePx: Float
    val viewportPx: Float
    val isScrolling: Boolean
    suspend fun scrollToLine(line: Int, offsetPx: Int)
}

internal class ListScrollbarAdapter(private val state: LazyListState) : ScrollbarAdapter {
    override val totalLines: Int get() = state.layoutInfo.totalItemsCount
    override val firstVisibleLine: Int get() = state.firstVisibleItemIndex
    override val firstLineOffsetPx: Float get() = state.firstVisibleItemScrollOffset.toFloat()
    override val avgLinePx: Float
        get() {
            val visible = state.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) return 0f
            return visible.sumOf { it.size }.toFloat() / visible.size +
                state.layoutInfo.mainAxisItemSpacing
        }
    override val viewportPx: Float
        get() = (state.layoutInfo.viewportEndOffset - state.layoutInfo.viewportStartOffset).toFloat()
    override val isScrolling: Boolean get() = state.isScrollInProgress

    override suspend fun scrollToLine(line: Int, offsetPx: Int) = state.scrollToItem(line, offsetPx)
}

internal class GridScrollbarAdapter(private val state: LazyGridState) : ScrollbarAdapter {
    private val columns: Int
        get() = (state.layoutInfo.visibleItemsInfo.maxOfOrNull { it.column } ?: 0) + 1

    override val totalLines: Int
        get() = ScrollbarMath.lineCount(state.layoutInfo.totalItemsCount, columns)
    override val firstVisibleLine: Int get() = state.firstVisibleItemIndex / columns
    override val firstLineOffsetPx: Float get() = state.firstVisibleItemScrollOffset.toFloat()
    override val avgLinePx: Float
        get() {
            val rows = state.layoutInfo.visibleItemsInfo.groupBy { it.row }.values
            if (rows.isEmpty()) return 0f
            val avgRow = rows.sumOf { row -> row.maxOf { it.size.height } }.toFloat() / rows.size
            return avgRow + state.layoutInfo.mainAxisItemSpacing
        }
    override val viewportPx: Float
        get() = (state.layoutInfo.viewportEndOffset - state.layoutInfo.viewportStartOffset).toFloat()
    override val isScrolling: Boolean get() = state.isScrollInProgress

    override suspend fun scrollToLine(line: Int, offsetPx: Int) =
        state.scrollToItem(line * columns, offsetPx)
}

private const val HIDE_DELAY_MS = 2_000L

/**
 * The scrollbar overlay: a capsule thumb at the right edge that mirrors the
 * viewport position/size and can be dragged to jump through the list. Shown on
 * scroll, hidden again [HIDE_DELAY_MS] after the last movement. While hidden it
 * is not composed at all, so it never intercepts row gestures (swipe-to-remove,
 * long-press multi-select) or the Now Playing queue's nested-scroll hand-off;
 * dragging the thumb drives [ScrollbarAdapter.scrollToLine] directly, which
 * also bypasses nested scroll, so the sheet stays put. Geometry and drag
 * mapping delegate to [ScrollbarMath].
 */
@Composable
internal fun FastScrollbar(adapter: ScrollbarAdapter, modifier: Modifier = Modifier) {
    val thumb by remember(adapter) {
        derivedStateOf {
            ScrollbarMath.thumb(
                totalLines = adapter.totalLines,
                firstVisibleLine = adapter.firstVisibleLine,
                firstLineOffsetPx = adapter.firstLineOffsetPx,
                avgLinePx = adapter.avgLinePx,
                viewportPx = adapter.viewportPx,
            )
        }
    }

    var dragging by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    val scrolling = adapter.isScrolling
    val hasThumb = thumb != null
    LaunchedEffect(scrolling, dragging, hasThumb) {
        when {
            !hasThumb -> shown = false
            scrolling || dragging -> shown = true
            shown -> {
                delay(HIDE_DELAY_MS)
                shown = false
            }
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(if (shown) 100 else 400),
        label = "fastScrollerAlpha",
    )

    val current = thumb
    if (current == null || (!shown && alpha < 0.01f)) return

    val scope = rememberCoroutineScope()
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    var dragOffsetFraction by remember { mutableFloatStateOf(0f) }

    Box(
        modifier
            .fillMaxHeight()
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical))
            .padding(vertical = 4.dp)
            .width(20.dp)
            .onSizeChanged { trackHeightPx = it.height.toFloat() }
            .graphicsLayer { this.alpha = alpha }
            .testTag("fastScroller"),
    ) {
        val displayedOffset = if (dragging) dragOffsetFraction else current.offsetFraction
        // The whole 20 dp strip at thumb height is grabbable; the capsule
        // inside is the visible part.
        Box(
            Modifier
                .offset { IntOffset(0, (displayedOffset * trackHeightPx).roundToInt()) }
                .fillMaxWidth()
                .fillMaxHeight(current.sizeFraction)
                .pointerInput(adapter) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            dragOffsetFraction = thumb?.offsetFraction ?: 0f
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, dragAmount ->
                        change.consume()
                        val t = thumb ?: return@detectVerticalDragGestures
                        if (trackHeightPx <= 0f) return@detectVerticalDragGestures
                        dragOffsetFraction = ScrollbarMath.draggedThumbOffset(
                            startOffsetFraction = dragOffsetFraction,
                            dragDeltaFraction = dragAmount / trackHeightPx,
                            thumbSizeFraction = t.sizeFraction,
                        )
                        val target = ScrollbarMath.dragTarget(
                            thumbOffsetFraction = dragOffsetFraction,
                            thumbSizeFraction = t.sizeFraction,
                            totalLines = adapter.totalLines,
                            avgLinePx = adapter.avgLinePx,
                            viewportPx = adapter.viewportPx,
                        )
                        scope.launch { adapter.scrollToLine(target.line, target.offsetPx) }
                    }
                }
                .testTag("fastScrollerThumb"),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier
                    .padding(end = 4.dp)
                    .width(if (dragging) 8.dp else 5.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (dragging) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        },
                    ),
            )
        }
    }
}
