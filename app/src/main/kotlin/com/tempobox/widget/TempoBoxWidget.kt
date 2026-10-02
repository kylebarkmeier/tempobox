package com.tempobox.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.tempobox.MainActivity
import com.tempobox.R
import com.tempobox.model.NowPlayingState
import com.tempobox.playback.PlaybackService
import com.tempobox.playback.PlayerConnection
import com.tempobox.tags.TagReader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Home screen widget (product spec): album art, album artist, title, and
 * shuffle / previous / play-pause / next / repeat controls.
 *
 * Rendered with Glance. `com.tempobox.action.WIDGET_REFRESH` is broadcast on
 * track / play-pause / repeat changes ([PlaybackService]) and on app-level
 * shuffle changes ([PlayerConnection] — shuffle is a timeline reorder, not
 * ExoPlayer state, so the service never sees it as a player event); the
 * receiver below re-renders every widget instance.
 */
class TempoBoxWidget : GlanceAppWidget() {

    /** Re-compose with the real widget size so everything scales on resize. */
    override val sizeMode: SizeMode = SizeMode.Exact

    /** Hilt access from non-injectable Glance classes. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun playerConnection(): PlayerConnection
        fun tagReader(): TagReader
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val player = entry.playerConnection()
        val tagReader = entry.tagReader()

        provideContent {
            // Collect INSIDE the composition: a snapshot taken before
            // provideContent would never change on re-render, so the widget
            // would be stuck on whatever was playing when it was first drawn.
            val state by player.state.collectAsState()
            val art: Bitmap? = remember(state.track?.filePath, state.track?.hasEmbeddedArt) {
                state.track
                    ?.takeIf { it.hasEmbeddedArt }
                    ?.let { track ->
                        tagReader.readEmbeddedArtwork(File(track.filePath))
                            ?.let(::decodeScaledBitmap)
                    }
            }
            GlanceTheme {
                WidgetContent(state, art)
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun WidgetContent(state: NowPlayingState, art: Bitmap?) {
        // SizeMode.Exact: LocalSize is the widget's real size, so everything
        // below scales as the user resizes the widget on the home screen.
        val widgetSize = LocalSize.current
        val artSize = (widgetSize.height.value - 28f).coerceIn(56f, 140f).dp
        val controlSize = (widgetSize.height.value * 0.26f).coerceIn(22f, 44f).dp
        val titleSize = (widgetSize.height.value * 0.13f).coerceIn(13f, 20f).sp
        val subtitleSize = (widgetSize.height.value * 0.10f).coerceIn(11f, 16f).sp

        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                // Material You: follows the wallpaper/home theme (and dark
                // mode) on Android 12+; theme surface color before that.
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(24.dp)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = art?.let { ImageProvider(it) }
                    ?: ImageProvider(R.drawable.ic_launcher_foreground),
                contentDescription = "Album art",
                modifier = GlanceModifier.size(artSize),
            )
            Column(GlanceModifier.padding(start = 12.dp).fillMaxWidth()) {
                Text(
                    state.track?.title ?: "TempoBox",
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = titleSize),
                    maxLines = 1,
                )
                Text(
                    state.track?.effectiveAlbumArtist ?: "Nothing playing",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = subtitleSize,
                    ),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(6.dp))
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // defaultWeight() spreads the controls evenly, so each one
                    // is a big tap target that grows with the widget.
                    // Shuffle/repeat mirror the in-app buttons: ON gets a
                    // tonal pill behind the icon (shape, not just hue), OFF
                    // dims to onSurfaceVariant, and repeat ONE swaps in its
                    // own icon (see WidgetControls).
                    ControlIcon(
                        iconRes = R.drawable.ic_widget_shuffle,
                        contentDescription = WidgetControls.shuffleDescription(state.shuffleMode),
                        size = controlSize,
                        action = actionRunCallback<ShuffleAction>(),
                        modifier = GlanceModifier.defaultWeight(),
                        toggled = WidgetControls.shuffleActive(state.shuffleMode),
                    )
                    ControlIcon(
                        iconRes = R.drawable.ic_widget_skip_previous,
                        contentDescription = "Previous",
                        size = controlSize,
                        action = actionRunCallback<PreviousAction>(),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    ControlIcon(
                        iconRes = WidgetControls.playPauseIcon(state.isPlaying),
                        contentDescription = WidgetControls.playPauseDescription(state.isPlaying),
                        size = controlSize,
                        action = actionRunCallback<PlayPauseAction>(),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    ControlIcon(
                        iconRes = R.drawable.ic_widget_skip_next,
                        contentDescription = "Next",
                        size = controlSize,
                        action = actionRunCallback<NextAction>(),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    ControlIcon(
                        iconRes = WidgetControls.repeatIcon(state.repeatMode),
                        contentDescription = WidgetControls.repeatDescription(state.repeatMode),
                        size = controlSize,
                        action = actionRunCallback<RepeatAction>(),
                        modifier = GlanceModifier.defaultWeight(),
                        toggled = WidgetControls.repeatActive(state.repeatMode),
                    )
                }
            }
        }
    }

    /**
     * One control icon; the caller passes a defaultWeight() modifier.
     * [toggled] is null for plain transport buttons, true/false for the
     * shuffle/repeat toggles.
     *
     * Tinted vector drawables, not text glyphs: the old emoji glyphs rendered
     * as fixed-color emoji that ignored the tint entirely, so active/inactive
     * state was invisible (and the mixed emoji/text styles clashed).
     *
     * A toggle's ON state draws a tonal pill behind the icon instead of only
     * re-tinting it: tint alone (primary vs onSurfaceVariant) is invisible to
     * red-green colorblind users when the Material You palette lands in the
     * green or red range. Below Android 12 cornerRadius is a no-op and the
     * pill renders square, which still reads as "on".
     */
    @androidx.compose.runtime.Composable
    private fun ControlIcon(
        @DrawableRes iconRes: Int,
        contentDescription: String,
        size: Dp,
        action: androidx.glance.action.Action,
        modifier: GlanceModifier,
        toggled: Boolean? = null,
    ) {
        Box(
            modifier = modifier.clickable(action),
            contentAlignment = Alignment.Center,
        ) {
            val pill = if (toggled == true) {
                GlanceModifier
                    .background(GlanceTheme.colors.primaryContainer)
                    .cornerRadius(size)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            } else {
                GlanceModifier.padding(horizontal = 6.dp, vertical = 3.dp)
            }
            Box(modifier = pill, contentAlignment = Alignment.Center) {
                Image(
                    provider = ImageProvider(iconRes),
                    contentDescription = contentDescription,
                    colorFilter = ColorFilter.tint(
                        when (toggled) {
                            null -> GlanceTheme.colors.primary
                            true -> GlanceTheme.colors.onPrimaryContainer
                            false -> GlanceTheme.colors.onSurfaceVariant
                        },
                    ),
                    modifier = GlanceModifier.size(size),
                )
            }
        }
    }

}

/**
 * Decodes embedded art capped at ~512px: RemoteViews has a hard per-widget
 * bitmap memory budget, and full-size art can make the host silently drop
 * the update (a widget that "never updates").
 */
private fun decodeScaledBitmap(bytes: ByteArray, maxDim: Int = 512): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= maxDim ||
        bounds.outHeight / (sampleSize * 2) >= maxDim
    ) {
        sampleSize *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

// ---------------------------------------------------------------- actions

private fun playerFrom(context: Context): PlayerConnection =
    EntryPointAccessors.fromApplication(context, TempoBoxWidget.WidgetEntryPoint::class.java)
        .playerConnection()

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        playerFrom(context).togglePlayPause()
    }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        playerFrom(context).next()
    }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        playerFrom(context).previous()
    }
}

class ShuffleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        playerFrom(context).toggleShuffle()
    }
}

class RepeatAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        playerFrom(context).cycleRepeatMode()
    }
}

// ---------------------------------------------------------------- receiver

/** AppWidget receiver; also re-renders on the service's refresh broadcast. */
class TempoBoxWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TempoBoxWidget()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val pending = goAsync()
            scope.launch {
                runCatching { TempoBoxWidget().updateAll(context) }
                pending.finish()
            }
        }
    }

    companion object {
        /** Single source of truth lives in core:playback (also in the manifest). */
        const val ACTION_REFRESH = PlaybackService.ACTION_WIDGET_REFRESH
    }
}
