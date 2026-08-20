package com.tempobox.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
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
import androidx.compose.ui.unit.dp
import com.tempobox.MainActivity
import com.tempobox.R
import com.tempobox.model.NowPlayingState
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
 * Rendered with Glance. [PlaybackService] broadcasts
 * `com.tempobox.action.WIDGET_REFRESH` on track/state changes; the receiver
 * below re-renders every widget instance.
 */
class TempoBoxWidget : GlanceAppWidget() {

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

        val state = player.state.value
        val art: Bitmap? = state.track
            ?.takeIf { it.hasEmbeddedArt }
            ?.let { track ->
                tagReader.readEmbeddedArtwork(File(track.filePath))?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }

        provideContent {
            GlanceTheme {
                WidgetContent(state, art)
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun WidgetContent(state: NowPlayingState, art: Bitmap?) {
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = art?.let { ImageProvider(it) }
                    ?: ImageProvider(R.drawable.ic_launcher_foreground),
                contentDescription = "Album art",
                modifier = GlanceModifier.size(72.dp),
            )
            Column(GlanceModifier.padding(start = 12.dp).fillMaxWidth()) {
                Text(
                    state.track?.title ?: "TempoBox",
                    style = TextStyle(color = GlanceTheme.colors.onSurface),
                    maxLines = 1,
                )
                Text(
                    state.track?.effectiveAlbumArtist ?: "Nothing playing",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ControlText("⇄", actionRunCallback<ShuffleAction>())
                    ControlText("⏮", actionRunCallback<PreviousAction>())
                    ControlText(
                        if (state.isPlaying) "⏸" else "▶",
                        actionRunCallback<PlayPauseAction>(),
                    )
                    ControlText("⏭", actionRunCallback<NextAction>())
                    ControlText("🔁", actionRunCallback<RepeatAction>())
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ControlText(glyph: String, action: androidx.glance.action.Action) {
        Text(
            glyph,
            style = TextStyle(color = GlanceTheme.colors.primary),
            modifier = GlanceModifier
                .padding(horizontal = 8.dp)
                .clickable(action),
        )
    }

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
        const val ACTION_REFRESH = "com.tempobox.action.WIDGET_REFRESH"
    }
}
