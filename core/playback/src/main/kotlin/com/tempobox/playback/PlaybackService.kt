package com.tempobox.playback

import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
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
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.tempobox.common.IoDispatcher
import com.tempobox.library.LibraryRepository
import com.tempobox.model.MediaButton
import com.tempobox.model.MediaButtonAction
import com.tempobox.model.RepeatMode
import com.tempobox.model.Track
import com.tempobox.model.VolumeTapAction
import com.tempobox.remoteplay.RemotePlayController
import com.tempobox.scrobble.Scrobbler
import com.tempobox.settings.AppSettings
import com.tempobox.settings.SettingsRepository
import com.tempobox.settings.ShuffleSettings
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Foreground media service: owns the [ExoPlayer] and the
 * [MediaLibraryService.MediaLibrarySession].
 *
 * Responsibilities beyond vanilla Media3 wiring:
 *  - lockscreen/notification media controls with embedded-tag artwork
 *    ([ArtworkBitmapLoader]) — this is the "lockscreen integration"
 *  - the Android Auto browse tree ([LibraryBrowseTree]) and playback
 *    resumption from the persisted queue
 *  - play counting + Last.fm scrobbling via [PlaybackStatsListener]
 *  - queue persistence/restore across process death ([PlaybackStateStore])
 *  - Bluetooth: start-on-connect, media button remapping, volume triple-tap
 *    gestures with screen off
 *  - call guarding: automatic playback (start-on-connect, spurious resumes,
 *    AVRCP PLAY from car head units) is suppressed while a call is active
 *    ([CallGuard])
 *
 * Format support: ExoPlayer's bundled decoders play MP3, FLAC and OGG
 * (Vorbis/Opus); ALAC (.m4a) decodes through the device MediaCodec (present on
 * API 31+ and most vendor builds). Bluetooth codec selection (LDAC, aptX HD,
 * AAC, LC3/LE Audio) is negotiated by the OS — the service outputs lossless
 * PCM so the stack can use the best codec the headset offers.
 */
@AndroidEntryPoint
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var libraryRepository: LibraryRepository
    @Inject lateinit var scrobbler: Scrobbler
    @Inject lateinit var stateStore: PlaybackStateStore
    @Inject lateinit var tagReader: TagReader
    @Inject lateinit var browseTree: LibraryBrowseTree
    @Inject lateinit var voiceSearch: VoiceSearch
    @Inject lateinit var shuffleEngine: ShuffleEngine
    @Inject lateinit var remotePlay: RemotePlayController
    @Inject @IoDispatcher lateinit var ioDispatcher: CoroutineDispatcher

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private lateinit var settingsState: StateFlow<AppSettings?>
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    /**
     * Uid allocator for queue items the service builds itself (Android Auto
     * tap-to-queue, resumption). Epoch-seeded so these never collide with
     * PlayerConnection's small counter uids: on a collision, shuffle-off there
     * could restore a bogus "original" order.
     */
    private val serviceUid = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

    private fun nextServiceUid(): Long = serviceUid.getAndIncrement()

    /** One browse/search result page. Long math: hosts may page with pageSize = Int.MAX_VALUE. */
    private fun <T> List<T>.page(page: Int, pageSize: Int): List<T> {
        val from = page.toLong() * pageSize
        return when {
            from >= size -> emptyList()
            else -> subList(from.toInt(), minOf(size.toLong(), from + pageSize).toInt())
        }
    }

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
                }.onFailure {
                    Log.w(TAG, "Could not resolve the finished item from the timeline; play not counted", it)
                }.getOrNull() ?: return@PlaybackStatsListener
                onPlaybackSessionEnded(item, stats.totalPlayTimeMs)
            },
        )

        player.addListener(playerListener)

        session = MediaLibrarySession.Builder(this, player, sessionCallback)
            .setBitmapLoader(
                CacheBitmapLoader(ArtworkBitmapLoader(tagReader, scope, ioDispatcher)),
            )
            .apply { launchAppPendingIntent()?.let(::setSessionActivity) }
            .build()

        restoreQueue()
        registerBluetoothReceiver()
        registerVolumeReceiver()
        startPeriodicSnapshots()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /**
     * Content intent for the media notification and lockscreen session: tapping
     * them opens the app. Resolved via the launcher intent because this module
     * must not depend on :app (where MainActivity lives).
     */
    private fun launchAppPendingIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            /* requestCode = */ 0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing in the background; stop only when idle/paused.
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        runBlocking { saveSnapshot() } // final flush — cheap (one DataStore write)
        // Renderer session, stream server, discovery, multicast lock: all gone
        // with the service (non-blocking; see RemotePlayController.shutdown).
        remotePlay.shutdown()
        bluetoothReceiver?.let(::unregisterReceiver)
        volumeReceiver?.let(::unregisterReceiver)
        session?.release()
        session = null
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ session callback

    private val sessionCallback = object : MediaLibrarySession.Callback {

        /**
         * Default commands for everyone; the app's own commands (audio output
         * selection) only for the app's own controllers. The service is
         * exported for Android Auto, so any app can connect, and output
         * routing must not be theirs to command.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (controller.packageName != packageName) {
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                        .buildUpon()
                        .add(SessionCommand(ACTION_SET_AUDIO_OUTPUT, Bundle.EMPTY))
                        .build(),
                )
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != ACTION_SET_AUDIO_OUTPUT) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            val deviceId = args.getInt(EXTRA_AUDIO_DEVICE_ID, AUDIO_DEVICE_AUTO)
            val device = if (deviceId == AUDIO_DEVICE_AUTO) {
                null // back to automatic system routing
            } else {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.id == deviceId }
                    ?: return Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE),
                    )
            }
            player.setPreferredAudioDevice(device)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // ------------------------------------------------------ browse tree (Android Auto)

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(browseTree.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            scope.loggedFuture(TAG, "onGetChildren($parentId)") {
                val children = browseTree.children(parentId)
                    ?: return@loggedFuture LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                LibraryResult.ofItemList(ImmutableList.copyOf(children.page(page, pageSize)), params)
            }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            scope.loggedFuture(TAG, "onGetItem($mediaId)") {
                browseTree.item(mediaId)
                    ?.let { LibraryResult.ofItem(it, null) }
                    ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            }

        /**
         * A track tapped in a browse list arrives as one item whose id names
         * the track and the node it was browsed under — queue that whole
         * context (album, playlist) with the tapped track first, like tapping
         * a row in the app does.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Assistant / Gemini "play X on TempoBox" arrives as one item whose
            // request metadata carries the recognized text (Media3's mapping of
            // the legacy playFromSearch).
            val searchQuery = mediaItems.singleOrNull()?.requestMetadata?.searchQuery
            if (searchQuery != null) {
                return scope.loggedFuture(TAG, "voiceQueue($searchQuery)") { voiceQueue(searchQuery) }
            }

            val node = mediaItems.singleOrNull()?.let { BrowseIds.decode(it.mediaId) }
            if (node !is BrowseIds.Node.Track) {
                return super.onSetMediaItems(
                    mediaSession, controller, mediaItems, startIndex, startPositionMs,
                )
            }
            return scope.loggedFuture(TAG, "onSetMediaItems(${node.parent})") {
                val tracks = browseTree.tracksFor(node)
                // A stale cached id (deleted playlist, retagged album): fail the
                // request so the user's current queue is left alone.
                if (tracks.isEmpty()) {
                    throw UnsupportedOperationException("browse context ${node.parent} is empty")
                }
                val items = tracks.map { MediaItems.toMediaItem(it, uid = nextServiceUid()) }
                // Prefer the tapped row's position (exact for duplicate playlist
                // entries); fall back to the first copy, then to the context's
                // start when the track itself vanished in a rescan.
                val start = node.index
                    ?.takeIf { it in tracks.indices && tracks[it].id == node.trackId }
                    ?: tracks.indexOfFirst { it.id == node.trackId }.coerceAtLeast(0)
                MediaSession.MediaItemsWithStartPosition(items, start, 0)
            }
        }

        /**
         * Play button on Android Auto / the notification before the app ran:
         * bring back the persisted queue instead of doing nothing.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            scope.loggedFuture(TAG, "onPlaybackResumption") {
                resumptionQueue() ?: throw UnsupportedOperationException("nothing to resume")
            }

        // ------------------------------------------------------ voice search

        /** Android Auto's search screen: resolve, then announce the count. */
        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.loggedFuture(TAG, "onSearch($query)") {
            val resolution = voiceSearch.resolve(query)
            Log.i(TAG, "onSearch '$query' -> ${resolution?.description ?: "no match"}")
            session.notifySearchResultChanged(
                browser, query, resolution?.tracks?.size ?: 0, params,
            )
            LibraryResult.ofVoid(params)
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            scope.loggedFuture(TAG, "onGetSearchResult($query)") {
                val tracks = voiceSearch.resolve(query)?.tracks.orEmpty()
                val items = browseTree.searchResultItems(tracks).page(page, pageSize)
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            }

        /**
         * MediaItems arrive from controllers without their file URI (Media3
         * strips localConfiguration crossing the binder). Rebuild it from the
         * path stashed in the metadata extras (the app's own items), or from
         * the library for browse-tree leaf ids, which carry no URI at all and
         * can arrive here through multi-item sets or add-to-queue from any
         * MediaBrowser client.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.loggedFuture(TAG, "onAddMediaItems") {
            val leafIds = mediaItems.mapNotNull {
                (BrowseIds.decode(it.mediaId) as? BrowseIds.Node.Track)?.trackId
            }
            val tracksById =
                if (leafIds.isEmpty()) emptyMap()
                else libraryRepository.getTracksByIds(leafIds).associateBy { it.id }
            mediaItems.mapNotNull { item ->
                val path = item.mediaMetadata.extras?.getString("path")
                val leaf = BrowseIds.decode(item.mediaId) as? BrowseIds.Node.Track
                when {
                    !path.isNullOrBlank() ->
                        item.buildUpon().setUri(MediaItems.playbackUri(path)).build()
                    leaf != null ->
                        // Drop leaves the library no longer has instead of
                        // handing ExoPlayer an unplayable URI-less item.
                        tracksById[leaf.trackId]
                            ?.let { MediaItems.toMediaItem(it, uid = nextServiceUid()) }
                    else -> item
                }
            }.toMutableList()
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
            val track = MediaItems.toTrack(mediaItem)
            // Network-server tracks are not library rows: no scrobbling.
            if (!track.isNetworkStream) {
                scope.launch { scrobbler.updateNowPlaying(track) }
            }
            scheduleSnapshot()
            refreshWidget()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            scheduleSnapshot()
            refreshWidget()
            // Cars can re-start playback into an active call (spurious focus
            // gains, AVRCP PLAY on source switches). Re-check after a settle
            // delay: when a call ENDS, focus can return a beat before the
            // audio mode drops back to NORMAL, and pausing then would kill
            // the legitimate auto-resume.
            if (CallGuard.shouldPauseNow(isPlaying, audioManager.mode)) {
                scope.launch {
                    delay(CallGuard.RECHECK_DELAY_MS)
                    if (CallGuard.shouldPauseNow(player.isPlaying, audioManager.mode)) {
                        Log.i(TAG, "Pausing playback that started during a call")
                        player.pause()
                    }
                }
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            // Repeat is part of the persisted snapshot and shown on the
            // widget's repeat button, whichever controller changed it.
            scheduleSnapshot()
            refreshWidget()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // Visibility only, no auto-skip: the same callback reaches every
            // controller, and PlayerConnection surfaces it to the UI snackbar.
            Log.e(
                TAG,
                "Player error ${error.errorCodeName} on " +
                    "${player.currentMediaItem?.let { MediaItems.toTrack(it).filePath } ?: "<no item>"}",
                error,
            )
        }
    }

    /**
     * Queue for a voice query: the resolved match (shuffled with the user's
     * flavor when asked), or, for an empty/unmatched query, the persisted
     * queue, or the whole library shuffled. Voice must always play something.
     */
    private suspend fun voiceQueue(query: String): MediaSession.MediaItemsWithStartPosition {
        val resolution = voiceSearch.resolve(query)
        Log.i(TAG, "Voice query '$query' -> ${resolution?.description ?: "no match, falling back"}")
        if (resolution == null) {
            resumptionQueue()?.let { return it }
            val all = libraryRepository.observeTracks().first()
            if (all.isEmpty()) throw UnsupportedOperationException("library is empty")
            return MediaSession.MediaItemsWithStartPosition(
                orderForVoice(all, shuffle = true)
                    .map { MediaItems.toMediaItem(it, uid = nextServiceUid()) },
                0,
                0,
            )
        }
        val ordered = orderForVoice(resolution.tracks, resolution.shuffle)
        return MediaSession.MediaItemsWithStartPosition(
            ordered.map { MediaItems.toMediaItem(it, uid = nextServiceUid()) },
            0,
            0,
        )
    }

    private fun orderForVoice(tracks: List<Track>, shuffle: Boolean): List<Track> {
        if (!shuffle) return tracks
        val mode = PlayerConnection.defaultShuffleMode(
            settingsState.value?.shuffle ?: ShuffleSettings(),
        )
        return shuffleEngine.order(tracks, mode).map { tracks[it] }
    }

    /**
     * The persisted queue as a resumption payload, or null when persistence is
     * off (a stale snapshot must not come back through the car's play button)
     * or nothing usable was saved. Also restores the saved repeat mode.
     */
    private suspend fun resumptionQueue(): MediaSession.MediaItemsWithStartPosition? {
        if (settingsState.value?.queue?.persistQueue == false) return null
        val snapshot = stateStore.load() ?: return null
        val tracks = libraryRepository.getTracksByIds(snapshot.trackIds)
        if (tracks.isEmpty()) return null
        val items = tracks.map { MediaItems.toMediaItem(it, uid = nextServiceUid()) }
        player.repeatMode = when (snapshot.repeatMode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        return MediaSession.MediaItemsWithStartPosition(
            items,
            snapshot.currentIndex.coerceIn(0, items.lastIndex),
            snapshot.positionMs,
        )
    }

    /** Tells the app's home-screen widget to re-render (receiver in :app). */
    private fun refreshWidget() {
        sendBroadcast(
            Intent(ACTION_WIDGET_REFRESH).setPackage(packageName),
        )
    }

    private fun onPlaybackSessionEnded(item: MediaItem, playedMs: Long) {
        val track = MediaItems.toTrack(item)
        if (track.isNetworkStream) return // not a library row: no count, no scrobble
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
            val snapshot = stateStore.load()
            if (snapshot == null) {
                Log.i(TAG, "No queue snapshot to restore")
                return@launch
            }
            val persist = settingsState.value?.queue?.persistQueue ?: true
            if (!persist) {
                Log.i(TAG, "Queue persistence is off; not restoring")
                return@launch
            }
            if (snapshot.trackIds.isEmpty()) {
                Log.i(TAG, "Queue snapshot is empty; nothing to restore")
                return@launch
            }
            if (player.mediaItemCount > 0) {
                Log.i(TAG, "Player already has a queue; skipping restore")
                return@launch
            }
            val tracks = libraryRepository.getTracksByIds(snapshot.trackIds)
            if (tracks.isEmpty()) {
                Log.i(TAG, "No snapshot track still exists in the library; nothing to restore")
                return@launch
            }
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
        // Network-server tracks are skipped: the snapshot stores library track
        // ids, and a NAS URL may not resolve after a restart anyway. The index
        // shifts to the count of local tracks before the current one, which is
        // the current track itself when it is local.
        val tracks = (0 until player.mediaItemCount).map { i ->
            MediaItems.toTrack(player.getMediaItemAt(i))
        }
        val current = player.currentMediaItemIndex
        val currentIsLocal = tracks.getOrNull(current)?.isNetworkStream == false
        stateStore.save(
            PlaybackStateStore.Snapshot(
                trackIds = tracks.filterNot { it.isNetworkStream }.map { it.id },
                currentIndex = tracks.take(current.coerceAtLeast(0))
                    .count { !it.isNetworkStream },
                positionMs = if (currentIsLocal) player.currentPosition.coerceAtLeast(0) else 0,
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
                val isConnect = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED ||
                    (intent.action == AudioManager.ACTION_HEADSET_PLUG &&
                        intent.getIntExtra("state", 0) == 1)
                val autoplay = CallGuard.shouldAutoplay(
                    startOnConnect = settingsState.value?.bluetooth?.startOnConnect ?: false,
                    isConnectEvent = isConnect,
                    hasQueue = player.mediaItemCount > 0,
                    audioMode = audioManager.mode,
                )
                if (autoplay) {
                    // Small delay: the audio route needs a moment to switch over.
                    scope.launch {
                        delay(1_500)
                        // A car answering a call also fires connect events —
                        // never autoplay into the call.
                        if (CallGuard.isCallMode(audioManager.mode)) return@launch
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

        /**
         * Re-render request for the :app home-screen widget. Referenced by
         * TempoBoxWidgetReceiver and declared literally in its manifest
         * intent-filter; also sent by [PlayerConnection] when the app-level
         * shuffle mode changes (not a player event the service can observe).
         */
        const val ACTION_WIDGET_REFRESH = "com.tempobox.action.WIDGET_REFRESH"

        /**
         * Custom session command: route playback to one output device.
         * [EXTRA_AUDIO_DEVICE_ID] holds the AudioDeviceInfo id, or
         * [AUDIO_DEVICE_AUTO] to return to automatic system routing.
         */
        const val ACTION_SET_AUDIO_OUTPUT = "com.tempobox.action.SET_AUDIO_OUTPUT"
        const val EXTRA_AUDIO_DEVICE_ID = "deviceId"
        const val AUDIO_DEVICE_AUTO = -1

        // Hidden-but-stable AudioManager broadcast constants.
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
    }
}
