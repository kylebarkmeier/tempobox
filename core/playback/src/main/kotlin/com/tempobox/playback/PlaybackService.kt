package com.tempobox.playback

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.tempobox.common.IoDispatcher
import com.tempobox.library.LibraryRepository
import com.tempobox.model.MediaButton
import com.tempobox.model.MediaButtonAction
import com.tempobox.model.RepeatMode
import com.tempobox.model.VolumeTapAction
import com.tempobox.scrobble.Scrobbler
import com.tempobox.settings.AppSettings
import com.tempobox.settings.SettingsRepository
import com.tempobox.tags.TagReader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.inject.Inject

/**
 * Foreground media service: owns the [ExoPlayer] and [MediaSession].
 *
 * Responsibilities beyond vanilla Media3 wiring:
 *  - lockscreen/notification media controls with embedded-tag artwork
 *    ([ArtworkBitmapLoader]) — this is the "lockscreen integration"
 *  - play counting + Last.fm scrobbling via [PlaybackStatsListener]
 *  - queue persistence/restore across process death ([PlaybackStateStore])
 *  - Bluetooth: start-on-connect, media button remapping, volume triple-tap
 *    gestures with screen off
 *
 * Format support: ExoPlayer's bundled decoders play MP3, FLAC and OGG
 * (Vorbis/Opus); ALAC (.m4a) decodes through the device MediaCodec (present on
 * API 31+ and most vendor builds). Bluetooth codec selection (LDAC, aptX HD,
 * AAC, LC3/LE Audio) is negotiated by the OS — the service outputs lossless
 * PCM so the stack can use the best codec the headset offers.
 */
@AndroidEntryPoint
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var libraryRepository: LibraryRepository
    @Inject lateinit var scrobbler: Scrobbler
    @Inject lateinit var stateStore: PlaybackStateStore
    @Inject lateinit var tagReader: TagReader
    @Inject @IoDispatcher lateinit var ioDispatcher: CoroutineDispatcher

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private lateinit var settingsState: StateFlow<AppSettings?>

    private var bluetoothReceiver: BroadcastReceiver? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private val tripleTap = VolumeTripleTapDetector()
    private var volumeBeforeSequence = -1
    private var saveJob: Job? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()

        settingsState = settingsRepository.settings
            .stateIn(scope, SharingStarted.Eagerly, null)

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones unplug
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        player.addAnalyticsListener(
            PlaybackStatsListener(/* keepHistory = */ false) { eventTime, stats ->
                // Fires once per finished playback session of a media item.
                val window = androidx.media3.common.Timeline.Window()
                val item = runCatching {
                    eventTime.timeline.getWindow(eventTime.windowIndex, window).mediaItem
                }.getOrNull() ?: return@PlaybackStatsListener
                onPlaybackSessionEnded(item, stats.totalPlayTimeMs)
            },
        )

        player.addListener(playerListener)

        session = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .setBitmapLoader(
                CacheBitmapLoader(ArtworkBitmapLoader(tagReader, scope, ioDispatcher)),
            )
            .build()

        restoreQueue()
        registerBluetoothReceiver()
        registerVolumeReceiver()
        startPeriodicSnapshots()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing in the background; stop only when idle/paused.
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        runBlocking { saveSnapshot() } // final flush — cheap (one DataStore write)
        bluetoothReceiver?.let(::unregisterReceiver)
        volumeReceiver?.let(::unregisterReceiver)
        session?.release()
        session = null
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ session callback

    private val sessionCallback = object : MediaSession.Callback {

        /**
         * MediaItems arrive from controllers without their file URI (Media3
         * strips localConfiguration crossing the binder) — rebuild it from the
         * path we stash in the metadata extras.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val path = item.mediaMetadata.extras?.getString("path")
                if (path.isNullOrBlank()) item
                else item.buildUpon().setUri(Uri.fromFile(File(path))).build()
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }

        /** Bluetooth/headset media-button remapping (Settings ▸ Bluetooth). */
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val keyEvent: KeyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) ?: return false
            if (keyEvent.action != KeyEvent.ACTION_DOWN) return false

            val button = when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK,
                -> MediaButton.PLAY_PAUSE
                KeyEvent.KEYCODE_MEDIA_NEXT -> MediaButton.NEXT
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MediaButton.PREVIOUS
                KeyEvent.KEYCODE_MEDIA_STOP -> MediaButton.STOP
                else -> return false
            }
            val remap = settingsState.value?.bluetooth?.buttonRemap?.get(button)
                ?: MediaButtonAction.DEFAULT
            if (remap == MediaButtonAction.DEFAULT) return false // stock behavior

            when (remap) {
                MediaButtonAction.PLAY_PAUSE -> if (player.isPlaying) player.pause() else player.play()
                MediaButtonAction.NEXT_TRACK -> player.seekToNextMediaItem()
                MediaButtonAction.PREVIOUS_TRACK -> player.seekToPreviousMediaItem()
                MediaButtonAction.STOP -> player.stop()
                MediaButtonAction.NONE, MediaButtonAction.DEFAULT -> Unit
            }
            return true
        }
    }

    // ------------------------------------------------------------------ play counting & scrobbling

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem ?: return
            scope.launch { scrobbler.updateNowPlaying(MediaItems.toTrack(mediaItem)) }
            scheduleSnapshot()
            refreshWidget()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            scheduleSnapshot()
            refreshWidget()
        }
    }

    /** Tells the app's home-screen widget to re-render (receiver in :app). */
    private fun refreshWidget() {
        sendBroadcast(
            Intent(WIDGET_REFRESH_ACTION).setPackage(packageName),
        )
    }

    private fun onPlaybackSessionEnded(item: MediaItem, playedMs: Long) {
        val track = MediaItems.toTrack(item)
        if (!PlayedThreshold.shouldCount(track.durationMs, playedMs)) return
        // Wall-clock start estimate (PlaybackStats times are elapsed-realtime,
        // which Last.fm would reject as a ~1970 timestamp).
        val startedAtEpochSec = (System.currentTimeMillis() - playedMs) / 1000
        scope.launch {
            if (track.id > 0) libraryRepository.incrementPlayCount(track.id)
            scrobbler.scrobble(track, startedAtEpochSec = startedAtEpochSec)
        }
    }

    // ------------------------------------------------------------------ queue persistence

    private fun restoreQueue() {
        scope.launch {
            val snapshot = stateStore.load() ?: return@launch
            val persist = settingsState.value?.queue?.persistQueue ?: true
            if (!persist || snapshot.trackIds.isEmpty() || player.mediaItemCount > 0) return@launch
            val tracks = libraryRepository.getTracksByIds(snapshot.trackIds)
            if (tracks.isEmpty()) return@launch
            val items = tracks.mapIndexed { i, t -> MediaItems.toMediaItem(t, uid = i.toLong()) }
            player.setMediaItems(
                items,
                snapshot.currentIndex.coerceIn(0, items.lastIndex),
                snapshot.positionMs,
            )
            player.repeatMode = when (snapshot.repeatMode) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            }
            player.prepare() // ready to play, but don't autoplay on restore
            Log.i(TAG, "Restored queue of ${items.size} tracks")
        }
    }

    private fun scheduleSnapshot() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1_000) // debounce bursts of events
            saveSnapshot()
        }
    }

    private fun startPeriodicSnapshots() {
        scope.launch {
            while (isActive) {
                delay(15_000)
                if (player.isPlaying) saveSnapshot()
            }
        }
    }

    private suspend fun saveSnapshot() {
        if (settingsState.value?.queue?.persistQueue == false) return
        val ids = (0 until player.mediaItemCount).map { i ->
            MediaItems.toTrack(player.getMediaItemAt(i)).id
        }
        stateStore.save(
            PlaybackStateStore.Snapshot(
                trackIds = ids,
                currentIndex = player.currentMediaItemIndex,
                positionMs = player.currentPosition.coerceAtLeast(0),
                repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    else -> RepeatMode.OFF
                },
            ),
        )
    }

    // ------------------------------------------------------------------ bluetooth glue

    private fun registerBluetoothReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // ACTION_HEADSET_PLUG is sticky: registration replays the last
                // plug event immediately — ignore it, only react to real ones.
                if (isInitialStickyBroadcast) return
                val startOnConnect = settingsState.value?.bluetooth?.startOnConnect ?: false
                if (!startOnConnect) return
                val isConnect = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED ||
                    (intent.action == AudioManager.ACTION_HEADSET_PLUG &&
                        intent.getIntExtra("state", 0) == 1)
                if (isConnect && player.mediaItemCount > 0) {
                    // Small delay: the audio route needs a moment to switch over.
                    scope.launch {
                        delay(1_500)
                        player.prepare()
                        player.play()
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(AudioManager.ACTION_HEADSET_PLUG)
            },
            ContextCompat.RECEIVER_EXPORTED, // system broadcasts
        )
        bluetoothReceiver = receiver
    }

    // ------------------------------------------------------------------ volume triple-tap

    private fun registerVolumeReceiver() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
                // Gestures are for pocket/lockscreen control: only with screen off.
                if (powerManager.isInteractive) {
                    tripleTap.reset()
                    return
                }
                val value = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)
                val prev = intent.getIntExtra(EXTRA_PREV_VOLUME_STREAM_VALUE, -1)
                if (value < 0 || prev < 0 || value == prev) return

                val direction = if (value > prev) {
                    VolumeTripleTapDetector.Direction.UP
                } else {
                    VolumeTripleTapDetector.Direction.DOWN
                }
                val fired = tripleTap.onVolumeChange(direction, System.currentTimeMillis())
                if (tripleTap.currentCount == 1) {
                    // New sequence — remember the volume to restore on trigger.
                    volumeBeforeSequence = prev
                }
                if (fired != null) {
                    // Undo the volume drift the three taps caused.
                    if (volumeBeforeSequence >= 0) {
                        audioManager.setStreamVolume(
                            AudioManager.STREAM_MUSIC, volumeBeforeSequence, 0,
                        )
                    }
                    volumeBeforeSequence = -1
                    performVolumeAction(
                        when (fired) {
                            VolumeTripleTapDetector.Direction.UP ->
                                settingsState.value?.bluetooth?.volumeUpTripleTap
                            VolumeTripleTapDetector.Direction.DOWN ->
                                settingsState.value?.bluetooth?.volumeDownTripleTap
                        } ?: VolumeTapAction.NONE,
                    )
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(VOLUME_CHANGED_ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
        volumeReceiver = receiver
    }

    private fun performVolumeAction(action: VolumeTapAction) {
        when (action) {
            VolumeTapAction.NEXT_TRACK -> player.seekToNextMediaItem()
            VolumeTapAction.PREVIOUS_TRACK -> player.seekToPreviousMediaItem()
            VolumeTapAction.PLAY_PAUSE -> if (player.isPlaying) player.pause() else player.play()
            VolumeTapAction.STOP -> player.stop()
            VolumeTapAction.NONE -> Unit
        }
    }

    companion object {
        private const val TAG = "PlaybackService"

        /** Must match TempoBoxWidgetReceiver.ACTION_REFRESH in :app. */
        private const val WIDGET_REFRESH_ACTION = "com.tempobox.action.WIDGET_REFRESH"

        // Hidden-but-stable AudioManager broadcast constants.
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
    }
}
