package com.tempobox.ui.nowplaying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.tempobox.model.NowPlayingState
import com.tempobox.playback.PlayerConnection
import com.tempobox.ui.queue.QueuePanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Drag state for the Now Playing sheet and its queue layer. Two vertical
 * offsets (sheet: 0 = expanded, [collapsedOffsetPx] = pill; queue: 0 = shown,
 * [queueHiddenOffsetPx] = hidden below), moved by one shared drag dispatcher.
 * All routing, settle and fade decisions delegate to [SheetMath]; this class
 * only owns the animatables and the layout-measured geometry.
 */
@Stable
class NowPlayingSheetState(
    initialStage: SheetStage,
    initialQueueShown: Boolean,
    private val scope: CoroutineScope,
    private val settleVelocityThresholdPx: Float,
) {
    /** The anchor the sheet currently rests at or settles toward. */
    var stage by mutableStateOf(initialStage)
        private set

    /** Whether the queue layer rests at or settles toward its shown anchor. */
    var queueShown by mutableStateOf(initialQueueShown)
        private set

    val sheetOffsetPx = Animatable(0f)
    val queueOffsetPx = Animatable(0f)

    var collapsedOffsetPx by mutableFloatStateOf(0f)
        private set
    var queueHiddenOffsetPx by mutableFloatStateOf(0f)
        private set
    var pillHeightPx by mutableFloatStateOf(0f)
        private set
    private var containerHeightPx = 0f

    /** False until the first measured geometry has seated the offsets. */
    var placed by mutableStateOf(false)
        private set

    private var dragging = false
    private var lastDragTarget: SheetDragTarget? = null

    /**
     * Before the first layout the offsets are not meaningful, so the fraction
     * falls back to the logical stage; afterwards it tracks the drag.
     */
    val expandFraction: Float
        get() = if (!placed) {
            if (stage == SheetStage.EXPANDED) 1f else 0f
        } else {
            SheetMath.expandFraction(sheetOffsetPx.value, collapsedOffsetPx)
        }

    /** Sheet translation, parked offscreen until the geometry is known. */
    val displaySheetOffsetPx: Float
        get() = when {
            placed -> sheetOffsetPx.value
            stage == SheetStage.EXPANDED -> 0f
            else -> containerHeightPx
        }

    /** True while the sheet occupies more of the screen than the pill. */
    val isVisibleBeyondPill: Boolean
        get() = stage == SheetStage.EXPANDED || expandFraction > 0f

    val isAnimating: Boolean
        get() = sheetOffsetPx.isRunning || queueOffsetPx.isRunning

    fun onContainerHeight(px: Float) {
        if (px != containerHeightPx) {
            containerHeightPx = px
            refreshGeometry()
        }
    }

    fun onPillHeight(px: Float) {
        if (px > 0f && px != pillHeightPx) {
            pillHeightPx = px
            refreshGeometry()
        }
    }

    private fun refreshGeometry() {
        if (containerHeightPx <= 0f) return
        collapsedOffsetPx = SheetMath.collapsedOffset(containerHeightPx, pillHeightPx)
        queueHiddenOffsetPx = containerHeightPx
        sheetOffsetPx.updateBounds(0f, collapsedOffsetPx)
        queueOffsetPx.updateBounds(0f, queueHiddenOffsetPx)
        // Re-seat the layers on their (possibly moved) anchors: resting layers
        // snap, a running settle retargets smoothly, an active finger drag is
        // left alone (the new bounds already clamp it).
        if (!dragging) {
            scope.launch {
                val sheetJob = launch {
                    val anchor = if (stage == SheetStage.EXPANDED) 0f else collapsedOffsetPx
                    if (sheetOffsetPx.isRunning) sheetOffsetPx.animateTo(anchor)
                    else sheetOffsetPx.snapTo(anchor)
                }
                val queueJob = launch {
                    val anchor = if (queueShown) 0f else queueHiddenOffsetPx
                    if (queueOffsetPx.isRunning) queueOffsetPx.animateTo(anchor)
                    else queueOffsetPx.snapTo(anchor)
                }
                sheetJob.join()
                queueJob.join()
                placed = true
            }
        }
    }

    fun onDragStarted() {
        dragging = true
    }

    fun onDragDelta(deltaY: Float) {
        val target = SheetMath.routeDrag(
            deltaY = deltaY,
            sheetOffsetPx = sheetOffsetPx.value,
            queueOffsetPx = queueOffsetPx.value,
            queueHiddenOffsetPx = queueHiddenOffsetPx,
        )
        lastDragTarget = target
        scope.launch {
            when (target) {
                SheetDragTarget.SHEET -> sheetOffsetPx.snapTo(sheetOffsetPx.value + deltaY)
                SheetDragTarget.QUEUE -> queueOffsetPx.snapTo(queueOffsetPx.value + deltaY)
            }
        }
    }

    fun onDragStopped(velocityY: Float) {
        dragging = false
        val target = lastDragTarget
        lastDragTarget = null
        // Velocity only counts for the layer the gesture was actually moving;
        // the other layer settles to its nearest anchor by position alone.
        val sheetVelocity = if (target == SheetDragTarget.SHEET) velocityY else 0f
        val queueVelocity = if (target == SheetDragTarget.QUEUE) velocityY else 0f
        animateTo(
            targetStage = SheetMath.settleStage(
                sheetOffsetPx.value, sheetVelocity, collapsedOffsetPx, settleVelocityThresholdPx,
            ),
            targetQueueShown = SheetMath.settleQueueShown(
                queueOffsetPx.value, queueVelocity, queueHiddenOffsetPx, settleVelocityThresholdPx,
            ),
            sheetVelocity = sheetVelocity,
            queueVelocity = queueVelocity,
        )
    }

    fun expand() = animateTo(SheetStage.EXPANDED, queueShown)

    fun collapse() = animateTo(SheetStage.COLLAPSED, targetQueueShown = false)

    fun showQueue() = animateTo(SheetStage.EXPANDED, targetQueueShown = true)

    fun hideQueue() = animateTo(stage, targetQueueShown = false)

    private fun animateTo(
        targetStage: SheetStage,
        targetQueueShown: Boolean,
        sheetVelocity: Float = 0f,
        queueVelocity: Float = 0f,
    ) {
        stage = targetStage
        queueShown = targetQueueShown
        scope.launch {
            sheetOffsetPx.animateTo(
                if (targetStage == SheetStage.EXPANDED) 0f else collapsedOffsetPx,
                initialVelocity = sheetVelocity,
            )
        }
        scope.launch {
            queueOffsetPx.animateTo(
                if (targetQueueShown) 0f else queueHiddenOffsetPx,
                initialVelocity = queueVelocity,
            )
        }
    }

    /**
     * Lets the queue's LazyColumn hand leftover scroll to the queue layer:
     * dragging past the top pulls the queue down; dragging up re-reveals a
     * partially dismissed queue before the list scrolls.
     */
    val queueNestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val delta = available.y
            if (delta < 0f && queueOffsetPx.value > 0f) {
                return Offset(0f, dragQueueBy(delta))
            }
            return Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            val delta = available.y
            if (delta > 0f) {
                return Offset(0f, dragQueueBy(delta))
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            // Mid-drag between anchors: settle instead of letting the list fling.
            if (queueOffsetPx.value > 0f && queueOffsetPx.value < queueHiddenOffsetPx) {
                settleQueue(available.y)
                return available
            }
            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            settleQueue(available.y)
            return Velocity.Zero
        }
    }

    private fun dragQueueBy(delta: Float): Float {
        val old = queueOffsetPx.value
        val new = (old + delta).coerceIn(0f, queueHiddenOffsetPx)
        scope.launch { queueOffsetPx.snapTo(new) }
        return new - old
    }

    private fun settleQueue(velocityY: Float) {
        val shown = SheetMath.settleQueueShown(
            queueOffsetPx.value, velocityY, queueHiddenOffsetPx, settleVelocityThresholdPx,
        )
        if (shown != queueShown || queueOffsetPx.value != (if (shown) 0f else queueHiddenOffsetPx)) {
            animateTo(stage, shown, queueVelocity = velocityY)
        }
    }

    companion object {
        fun saver(scope: CoroutineScope, settleVelocityThresholdPx: Float) =
            listSaver<NowPlayingSheetState, Any>(
                save = { listOf(it.stage.name, it.queueShown) },
                restore = {
                    NowPlayingSheetState(
                        initialStage = SheetStage.valueOf(it[0] as String),
                        initialQueueShown = it[1] as Boolean,
                        scope = scope,
                        settleVelocityThresholdPx = settleVelocityThresholdPx,
                    )
                },
            )
    }
}

@Composable
fun rememberNowPlayingSheetState(): NowPlayingSheetState {
    val scope = rememberCoroutineScope()
    // Matches the Material sheets' 125 dp/s settle-direction threshold.
    val velocityThresholdPx = with(LocalDensity.current) { 125.dp.toPx() }
    return rememberSaveable(
        scope,
        saver = NowPlayingSheetState.saver(scope, velocityThresholdPx),
    ) {
        NowPlayingSheetState(
            initialStage = SheetStage.COLLAPSED,
            initialQueueShown = false,
            scope = scope,
            settleVelocityThresholdPx = velocityThresholdPx,
        )
    }
}

/**
 * The persistent Now Playing sheet drawn over the app scaffold. Collapsed it
 * is the mini-player pill docked at the bottom; dragging (or tapping the pill)
 * expands it into the full Now Playing layout, and dragging up on the expanded
 * layout reveals the queue panel, which drags back down the same way. One
 * vertical [draggable] on the sheet moves whichever layer [SheetMath.routeDrag]
 * picks, so a single gesture can close the queue and keep collapsing the sheet.
 *
 * Gesture conflicts: the seek slider and the track-skip swipe on the artwork
 * are horizontal, so the vertical drag coexists with them; the zoomed artwork
 * consumes its events (see ZoomableArt); the queue's LazyColumn consumes
 * vertical drags itself and hands the sheet its overscroll through
 * [NowPlayingSheetState.queueNestedScrollConnection].
 */
@Composable
fun NowPlayingSheet(
    state: NowPlayingSheetState,
    nowPlaying: NowPlayingState,
    player: PlayerConnection,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (albumArtist: String, album: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = nowPlaying.track
    // Nothing loaded and resting collapsed: no pill, no sheet. The expanded
    // empty state (reached from the drawer) still composes.
    if (track == null && !state.isVisibleBeyondPill) return

    BackHandler(enabled = state.stage == SheetStage.EXPANDED || state.queueShown) {
        when (SheetMath.backAction(state.stage == SheetStage.EXPANDED, state.queueShown)) {
            SheetBackAction.CLOSE_QUEUE -> state.hideQueue()
            SheetBackAction.COLLAPSE -> state.collapse()
            SheetBackAction.NONE -> Unit
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { state.onContainerHeight(it.height.toFloat()) },
    ) {
        Surface(
            tonalElevation = 4.dp,
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, state.displaySheetOffsetPx.roundToInt()) }
                // Invisible until measured, so the first frame can't flash the
                // sheet at the wrong anchor.
                .graphicsLayer {
                    alpha = if (state.placed || state.stage == SheetStage.EXPANDED) 1f else 0f
                }
                .draggable(
                    state = rememberDraggableState(state::onDragDelta),
                    orientation = Orientation.Vertical,
                    startDragImmediately = state.isAnimating,
                    onDragStarted = { state.onDragStarted() },
                    onDragStopped = { velocity -> state.onDragStopped(velocity) },
                )
                .testTag("nowPlayingSheet"),
        ) {
            Box(Modifier.fillMaxSize()) {
                val fraction = state.expandFraction
                val expandedAlpha = SheetMath.expandedAlpha(fraction)
                if (expandedAlpha > 0f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = SheetMath.expandedAlpha(state.expandFraction) }
                            .testTag("nowPlayingExpanded"),
                    ) {
                        NowPlayingContent(
                            onCollapse = state::collapse,
                            onOpenQueue = state::showQueue,
                            onOpenArtist = onOpenArtist,
                            onOpenAlbum = onOpenAlbum,
                        )
                    }
                }

                val pillAlpha = SheetMath.pillAlpha(fraction)
                if (track != null && pillAlpha > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .graphicsLayer { alpha = SheetMath.pillAlpha(state.expandFraction) }
                            .onSizeChanged { state.onPillHeight(it.height.toFloat()) }
                            .testTag("nowPlayingPill"),
                    ) {
                        MiniPlayer(state = nowPlaying, player = player, onOpen = state::expand)
                    }
                }

                val queueVisible = state.queueShown ||
                    (state.placed &&
                        state.queueOffsetPx.value < state.queueHiddenOffsetPx - SheetMath.OFFSET_EPSILON_PX)
                if (queueVisible) {
                    Surface(
                        tonalElevation = 8.dp,
                        modifier = Modifier
                            .fillMaxSize()
                            .offset { IntOffset(0, state.queueOffsetPx.value.roundToInt()) }
                            .nestedScroll(state.queueNestedScrollConnection)
                            .testTag("nowPlayingQueue"),
                    ) {
                        // The sheet sits outside the Scaffold, so the gesture
                        // bar inset is applied here, not by content padding.
                        Box(Modifier.navigationBarsPadding()) {
                            QueuePanel(onBack = state::hideQueue, hostActionDialogs = false)
                        }
                    }
                }
            }
        }
    }
}
