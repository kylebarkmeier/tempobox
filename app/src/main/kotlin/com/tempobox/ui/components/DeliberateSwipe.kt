package com.tempobox.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.abs

/**
 * A [SwipeToDismissBoxState] that only triggers on a *deliberate* swipe: the
 * row must actually be dragged at least [fraction] of its width. Stock
 * SwipeToDismissBox also fires on a fast flick after a tiny offset (its fixed
 * velocity threshold), which made every swipe row trigger while scrolling.
 *
 * Apply [DeliberateSwipeState.sizeModifier] to the SwipeToDismissBox so the
 * row width is known.
 */
class DeliberateSwipeState internal constructor(
    val state: SwipeToDismissBoxState,
    private val widthPx: IntArray,
) {
    val sizeModifier: Modifier
        get() = Modifier.onSizeChanged { widthPx[0] = it.width }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberDeliberateSwipeState(
    fraction: Float = 0.5f,
    confirmDismiss: (SwipeToDismissBoxValue) -> Boolean = { true },
): DeliberateSwipeState {
    val widthPx = remember { intArrayOf(0) }
    val stateHolder = remember { arrayOfNulls<SwipeToDismissBoxState>(1) }
    val state = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * fraction },
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.Settled) {
                true
            } else {
                val offset = stateHolder[0]
                    ?.let { s -> runCatching { abs(s.requireOffset()) }.getOrNull() }
                    ?: 0f
                val width = widthPx[0]
                val draggedFarEnough = width <= 0 || offset >= width * fraction
                draggedFarEnough && confirmDismiss(value)
            }
        },
    )
    stateHolder[0] = state
    return remember(state) { DeliberateSwipeState(state, widthPx) }
}
