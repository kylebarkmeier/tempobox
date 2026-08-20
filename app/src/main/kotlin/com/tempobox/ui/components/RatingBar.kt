package com.tempobox.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tempobox.model.Track

/**
 * 1–5 star rating row. Read-only when [onRate] is null. Tapping the current
 * rating's star again clears the rating (back to 0).
 */
@Composable
fun RatingBar(
    rating: Int,
    modifier: Modifier = Modifier,
    starSize: Dp = 20.dp,
    onRate: ((Int) -> Unit)? = null,
) {
    Row(modifier = modifier) {
        for (star in 1..Track.MAX_RATING) {
            val filled = star <= rating
            Icon(
                imageVector = if (filled) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = "$star star${if (star > 1) "s" else ""}",
                tint = if (filled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
                modifier = Modifier
                    .size(starSize)
                    .let { m ->
                        if (onRate != null) {
                            m.clickable { onRate(if (star == rating) 0 else star) }
                        } else {
                            m
                        }
                    },
            )
        }
    }
}
