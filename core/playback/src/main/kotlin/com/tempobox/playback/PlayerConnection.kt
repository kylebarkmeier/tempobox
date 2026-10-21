package com.tempobox.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.tempobox.common.resultOf
import com.tempobox.model.AudioOutputDevice
import com.tempobox.model.NetworkDevice
import com.tempobox.model.NowPlayingState
import com.tempobox.model.QueueItem
import com.tempobox.model.RepeatMode
import com.tempobox.model.ShuffleMode
import com.tempobox.model.Track
import com.tempobox.remoteplay.RemotePlayController
import com.tempobox.remoteplay.RemotePlayEvent
import com.tempobox.remoteplay.RemotePlaybackState
import com.tempobox.settings.SettingsRepository
import com.tempobox.settings.ShuffleSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single playback API for UI, ViewModels, and the home-screen widget.
 *
 * Wraps an async [MediaController] connected to [PlaybackService] and exposes
 * hot [StateFlow]s ([state], [queue]) plus imperative commands. Composables
 * never see a raw Player (CLAUDE.md rule) — they collect these flows.
 *
 * The ExoPlayer timeline IS the queue; this class only re-orders/mutates it.
 * ExoPlayer's built-in shuffle stays disabled — shuffle is applied by
 * explicitly reordering the timeline with [ShuffleEngine], which is what makes
 * anti-repeat and rating-biased modes possible.
 */
@Singleton
class PlayerConnection internal constructor(
    private val context: Context,
    private val shuffleEngine: ShuffleEngine,
    private val settingsRepository: SettingsRepository,
    private val remotePlay: RemotePlayController,
    controllerOverride: ListenableFuture<MediaController>?,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        shuffleEngine: ShuffleEngine,
        settingsRepository: SettingsRepository,
        remotePlay: RemotePlayController,
    ) : this(context, shuffleEngine, settingsRepository, remotePlay, controllerOverride = null)

    /** Controller calls must happen on the thread it connected on (main). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val controllerFuture = controllerOverride ?: MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
    ).buildAsync()

    private val _state = MutableStateFlow(NowPlayingState())

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /**
     * One-shot, user-readable playback problems (player errors, failed output
     * switches). Collected into the app-wide snackbar; no auto-skip or other
     * recovery is attempted here.
     */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _shuffleMode = MutableStateFlow(ShuffleMode.OFF)

    /**
     * Shuffle is overlaid from [_shuffleMode] at combine time, not copied in by
     * [refresh]: the UI and the home-screen widget see a mode change the
     * instant it is set (e.g. playTracks forcing OFF), never a stale value
     * from before the controller's next event round-trip.
     *
     * While a network renderer session is active, play state and position are
     * overlaid from [RemotePlayController.state] the same way: the local
     * player is paused then, and its clock stands still.
     */
    val state: StateFlow<NowPlayingState> =
        combine(_state, _shuffleMode, remotePlay.state) { s, mode, remote ->
            val base = s.copy(shuffleMode = mode)
            if (remote.active) {
                base.copy(
                    isPlaying = remote.isPlaying,
                    positionMs = remote.positionMs,
                    durationMs = if (remote.durationMs > 0) remote.durationMs else base.durationMs,
                )
            } else {
                base
            }
        }.stateIn(scope, SharingStarted.Eagerly, NowPlayingState())

    /** Queue order before shuffle was applied (uids); restored on shuffle-off. */
    private var originalOrderUids: List<Long> = emptyList()
    private var nextUid: Long = 1

    init {
        // Shuffle is applied by reordering the timeline, so it is NOT an
        // ExoPlayer state change the service's listener (or any controller)
        // can observe — nudge the home-screen widget from here whenever the
        // mode flips, exactly like PlaybackService does for repeat/track/
        // play-pause changes. StateFlow conflation makes this fire only on
        // real changes; drop(1) skips the initial OFF.
        scope.launch {
            _shuffleMode.drop(1).collect {
                context.sendBroadcast(
                    Intent(PlaybackService.ACTION_WIDGET_REFRESH).setPackage(context.packageName),
                )
            }
        }

        scope.launch {
            remotePlay.events.collect { event ->
                when (event) {
                    RemotePlayEvent.TrackEnded -> onRemoteTrackEnded()
                    is RemotePlayEvent.SessionLost -> onRemoteSessionLost(event.rendererName)
                }
            }
        }

        scope.launch {
            val controller = controller()
            controller.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (events.containsAny(
                            Player.EVENT_TIMELINE_CHANGED,
                            Player.EVENT_MEDIA_ITEM_TRANSITION,
                            Player.EVENT_IS_PLAYING_CHANGED,
                            Player.EVENT_PLAYBACK_STATE_CHANGED,
                            Player.EVENT_REPEAT_MODE_CHANGED,
                            Player.EVENT_POSITION_DISCONTINUITY,
                        )
                    ) {
                        refresh(controller)
                    }
                    if (remotePlay.state.value.active) {
                        // The local timeline stays the queue while casting:
                        // whatever changes the current item (next/previous,
                        // queue taps, new queues) is mirrored to the renderer.
                        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                            val play = remotePlay.state.value.isPlaying || pendingRemoteAdvance
                            pendingRemoteAdvance = false
                            scope.launch { loadCurrentOnRenderer(positionMs = 0, play = play) }
                        }
                        // Play reaching the local player while casting (the
                        // notification, Bluetooth, playTracks): keep the phone
                        // silent and route it to the renderer. Pausing the
                        // paused-anyway player never fights audio focus.
                        if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && controller.isPlaying) {
                            controller.pause()
                            scope.launch { remotePlay.play() }
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // The service logs the full stack; here we make it visible.
                    val title = controller.currentMediaItem
                        ?.mediaMetadata?.title?.toString()
                    _errors.tryEmit(
                        if (title.isNullOrBlank()) {
                            "Playback failed (${error.errorCodeName})"
                        } else {
                            "Can't play \"$title\" (${error.errorCodeName})"
                        },
                    )
                }
            })
            refresh(controller)

            // Smooth progress: tick the position while playing.
            while (isActive) {
                if (controller.isPlaying) refresh(controller)
                delay(POSITION_TICK_MS)
            }
        }
    }

    private suspend fun controller(): MediaController = controllerFuture.await()

    // ------------------------------------------------------------------ playback commands

    /**
     * Replaces the queue with [tracks] and starts playing at [startIndex]
     * (the library "Play" buttons). Turns shuffle off — the chosen order wins.
     */
    fun playTracks(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        scope.launch {
            val controller = controller()
            val items = tracks.map { newItem(it) }
            originalOrderUids = items.map { it.mediaId.toLong() }
            _shuffleMode.value = ShuffleMode.OFF
            controller.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0)
            controller.prepare()
            controller.play()
        }
    }

    /**
     * Replaces the queue with [tracks] in shuffled order using the user's
     * configured shuffle flavor (the "Shuffle" menu actions).
     */
    fun playShuffled(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        scope.launch {
            val mode = defaultShuffleMode(settingsRepository.settings.first().shuffle)
            val order = shuffleEngine.order(tracks, mode)
            val controller = controller()
            val items = order.map { newItem(tracks[it]) }
            // "Original" order is the unshuffled input, so shuffle-off restores it.
            val uidByTrackIndex = order.zip(items).associate { (trackIdx, item) ->
                trackIdx to item.mediaId.toLong()
            }
            originalOrderUids = tracks.indices.mapNotNull { uidByTrackIndex[it] }
            _shuffleMode.value = mode
            controller.setMediaItems(items, 0, 0)
            controller.prepare()
            controller.play()
        }
    }

    /**
     * Appends [tracks] to the end of the queue (starts the service if needed).
     * Returns how many were actually added — 0 when duplicate filtering
     * dropped everything, so the caller can say "already in queue" instead of
     * claiming an addition that never happened.
     */
    suspend fun addToQueue(tracks: List<Track>): Int {
        if (tracks.isEmpty()) return 0
        return withContext(Dispatchers.Main.immediate) {
            val controller = controller()
            val allowDuplicates = settingsRepository.settings.first().queue.allowDuplicates
            val existingIds = _queue.value.map { it.track.id }.toSet()
            val toAdd = if (allowDuplicates) tracks else tracks.filter { it.id !in existingIds }
            if (toAdd.isEmpty()) return@withContext 0
            val items = toAdd.map { newItem(it) }
            originalOrderUids = originalOrderUids + items.map { it.mediaId.toLong() }
            controller.addMediaItems(items)
            if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
            toAdd.size
        }
    }

    /** Inserts [tracks] right after the current one. */
    fun playNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        scope.launch {
            val controller = controller()
            val items = tracks.map { newItem(it) }
            val at = if (controller.mediaItemCount == 0) 0 else controller.currentMediaItemIndex + 1
            controller.addMediaItems(at, items)
            originalOrderUids = currentUids(controller) // manual edit redefines the order
            if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
        }
    }

    fun togglePlayPause() {
        if (remotePlay.state.value.active) {
            scope.launch {
                if (remotePlay.state.value.isPlaying) remotePlay.pause() else remotePlay.play()
            }
            return
        }
        withController { c ->
            when {
                c.mediaItemCount == 0 -> Unit
                c.playbackState == Player.STATE_IDLE -> { c.prepare(); c.play() }
                c.isPlaying -> c.pause()
                else -> c.play()
            }
        }
    }

    // next/previous move the local timeline even while casting: the paused
    // player is still the queue, and the item-transition listener mirrors the
    // new current track onto the renderer.
    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { it.seekToPreviousMediaItem() }

    fun seekTo(positionMs: Long) {
        if (remotePlay.state.value.active) {
            scope.launch { remotePlay.seekTo(positionMs) }
            return
        }
        withController { it.seekTo(positionMs) }
    }

    fun stop() = withController { it.stop() }

    /** Jump to a specific queue entry and play it. */
    fun playQueueItem(uid: Long) = withController { c ->
        val index = indexOfUid(c, uid) ?: return@withController
        c.seekTo(index, 0)
        c.play()
    }

    // ------------------------------------------------------------------ queue mutation

    fun removeQueueItems(uids: Collection<Long>) = withController { c ->
        // Contiguous ranges, back-to-front: one timeline change per run instead
        // of per item (each change rebroadcasts the whole queue — see QueueReorder).
        val indices = uids.mapNotNull { indexOfUid(c, it) }
        QueueReorder.descendingRanges(indices).forEach { range ->
            c.removeMediaItems(range.first, range.last + 1)
        }
        originalOrderUids = currentUids(c)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) = withController { c ->
        if (fromIndex in 0 until c.mediaItemCount && toIndex in 0 until c.mediaItemCount) {
            c.moveMediaItem(fromIndex, toIndex)
            originalOrderUids = currentUids(c)
        }
    }

    /** Clears everything and stops playback (queue view's Clear button). */
    fun clearQueue() = withController { c ->
        c.clearMediaItems()
        c.stop()
        originalOrderUids = emptyList()
    }

    // ------------------------------------------------------------------ shuffle & repeat

    /**
     * Applies [mode] to the existing queue *without interrupting playback*
     * (timeline moves only). OFF restores the pre-shuffle order.
     */
    fun setShuffleMode(mode: ShuffleMode) {
        scope.launch {
            val controller = controller()
            if (controller.mediaItemCount == 0) {
                _shuffleMode.value = mode
                refresh(controller) // keep state.shuffleMode (UI + widget) in sync
                return@launch
            }
            val currentUid = controller.currentMediaItem?.mediaId?.toLongOrNull()
            val targetOrder: List<Long> = if (mode == ShuffleMode.OFF) {
                val present = currentUids(controller).toSet()
                originalOrderUids.filter { it in present }
                    .ifEmpty { currentUids(controller) }
            } else {
                if (_shuffleMode.value == ShuffleMode.OFF) {
                    originalOrderUids = currentUids(controller) // remember for un-shuffle
                }
                val tracks = _queue.value
                val byUid = tracks.associateBy { it.uid }
                val order = shuffleEngine.order(tracks.map { it.track }, mode)
                val shuffled = order.map { tracks[it].uid }
                // Keep the playing track first so nothing audibly changes.
                if (currentUid != null && currentUid in byUid) {
                    listOf(currentUid) + shuffled.filter { it != currentUid }
                } else {
                    shuffled
                }
            }
            applyOrder(controller, targetOrder)
            _shuffleMode.value = mode
            refresh(controller)
        }
    }

    /** Cycles OFF → configured default → OFF … (mini-player & corner button). */
    fun toggleShuffle() {
        scope.launch {
            val mode = if (_shuffleMode.value == ShuffleMode.OFF) {
                defaultShuffleMode(settingsRepository.settings.first().shuffle)
            } else {
                ShuffleMode.OFF
            }
            setShuffleMode(mode)
        }
    }

    // ------------------------------------------------------------------ audio output

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _audioOutputs = MutableStateFlow(currentOutputs())

    /** Connected outputs the user can route playback to (live-updated). */
    val audioOutputs: StateFlow<List<AudioOutputDevice>> = _audioOutputs.asStateFlow()

    private val _selectedOutputId = MutableStateFlow<Int?>(null)

    /** The output chosen in the picker; null means automatic system routing. */
    val selectedOutputId: StateFlow<Int?> = _selectedOutputId.asStateFlow()

    init {
        audioManager.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = onChanged()
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = onChanged()
                private fun onChanged() {
                    val outputs = currentOutputs()
                    _audioOutputs.value = outputs
                    // A picked device that disconnected falls back to automatic
                    // routing (the player does the same with a stale preference).
                    if (_selectedOutputId.value?.let { id -> outputs.none { it.id == id } } == true) {
                        _selectedOutputId.value = null
                    }
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    private fun currentOutputs(): List<AudioOutputDevice> =
        AudioOutputs.pickable(
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList(),
        )

    /**
     * Routes playback to output [id], or back to automatic when null. Picking
     * a local output always ends an active network renderer session.
     */
    fun selectAudioOutput(id: Int?) {
        scope.launch {
            if (remotePlay.state.value.active) leaveRemotePlayback()
            val c = controller()
            val args = Bundle().apply {
                putInt(
                    PlaybackService.EXTRA_AUDIO_DEVICE_ID,
                    id ?: PlaybackService.AUDIO_DEVICE_AUTO,
                )
            }
            val previous = _selectedOutputId.value
            // Optimistic so the picker reflects the tap immediately…
            _selectedOutputId.value = id
            val result = resultOf {
                c.sendCustomCommand(
                    SessionCommand(PlaybackService.ACTION_SET_AUDIO_OUTPUT, Bundle.EMPTY),
                    args,
                ).await()
            }.onFailure {
                Log.w(TAG, "Audio output switch to $id failed", it)
            }.getOrNull()
            // …but reverted when the service refuses (stale device id, no
            // session command): the UI must not claim a route it never got.
            if (result == null || result.resultCode != SessionResult.RESULT_SUCCESS) {
                if (result != null) {
                    Log.w(TAG, "Audio output switch to $id rejected (code ${result.resultCode})")
                }
                _selectedOutputId.value = previous
                _errors.tryEmit("Could not switch the audio output")
            }
            refresh(c)
        }
    }

    // ------------------------------------------------------------------ network output (DLNA)

    /** Renderers found on the WLAN while discovery runs (output picker). */
    val networkOutputs: StateFlow<List<NetworkDevice>> = remotePlay.renderers

    /** Live renderer-session state (active renderer id, volume) for the picker. */
    val remotePlayback: StateFlow<RemotePlaybackState> = remotePlay.state

    /** Set while a remote track-end advance waits for its item transition. */
    private var pendingRemoteAdvance = false

    /** SSDP discovery runs only while a picker/browse screen is visible. */
    fun startNetworkDiscovery() = remotePlay.startDiscovery()
    fun stopNetworkDiscovery() = remotePlay.stopDiscovery()

    /** True while the OS refuses discovery sends (Android 17 local network permission). */
    val networkDiscoveryBlocked: StateFlow<Boolean> = remotePlay.discoveryBlocked

    /**
     * Routes playback to the renderer [rendererId], or back to this device
     * when null. The local player pauses (it already holds the queue) and the
     * current track continues on the renderer from the same position.
     */
    fun selectNetworkOutput(rendererId: String?) {
        scope.launch {
            if (rendererId == null) {
                leaveRemotePlayback()
                return@launch
            }
            val c = controller()
            val positionMs = c.currentPosition.coerceAtLeast(0)
            val wasPlaying = c.isPlaying || remotePlay.state.value.isPlaying
            c.pause()
            if (!remotePlay.connect(rendererId)) {
                _errors.tryEmit("Couldn't connect to the network device")
                return@launch
            }
            loadCurrentOnRenderer(positionMs = positionMs, play = wasPlaying)
        }
    }

    /** Renderer volume (RenderingControl), 0..100; no-op without a session. */
    fun setNetworkVolume(percent: Int) {
        scope.launch { remotePlay.setVolume(percent) }
    }

    /** Ends the renderer session; playback stays paused locally at the handoff spot. */
    private suspend fun leaveRemotePlayback() {
        val remote = remotePlay.state.value
        remotePlay.disconnect()
        if (remote.active) {
            val c = controller()
            // Carry the renderer's position back to the local player.
            if (remote.positionMs > 0) c.seekTo(remote.positionMs)
            refresh(c)
        }
    }

    /** Pushes the local current item to the renderer (handoff and transitions). */
    private suspend fun loadCurrentOnRenderer(positionMs: Long, play: Boolean) {
        val c = controller()
        val track = c.currentMediaItem?.let { MediaItems.toTrack(it) } ?: return
        if (!remotePlay.load(track, positionMs, play)) {
            // Failure counting and the SessionLost fallback live in the
            // controller; this is just the immediate-feedback path.
            Log.w(TAG, "Failed to load '${track.title}' on the renderer")
        }
    }

    /** The renderer finished its track: advance the queue like ExoPlayer would. */
    private suspend fun onRemoteTrackEnded() {
        val c = controller()
        if (c.mediaItemCount == 0) return
        val repeatOne = c.repeatMode == Player.REPEAT_MODE_ONE
        val wrapsToItself = c.mediaItemCount == 1 && c.repeatMode == Player.REPEAT_MODE_ALL
        if (repeatOne || wrapsToItself) {
            loadCurrentOnRenderer(positionMs = 0, play = true)
            return
        }
        if (!c.hasNextMediaItem()) return // end of queue, repeat off: stay stopped
        pendingRemoteAdvance = true
        c.seekToNextMediaItem() // the transition listener does the renderer load
    }

    /** Renderer died or vanished: fall back to this device and keep playing. */
    private suspend fun onRemoteSessionLost(rendererName: String) {
        _errors.tryEmit("Lost connection to $rendererName; playing on this device")
        val c = controller()
        if (lastRemotePositionMs > 0) c.seekTo(lastRemotePositionMs)
        if (c.mediaItemCount > 0) {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
        refresh(c)
    }

    /**
     * Last position reported by the renderer; [RemotePlaybackState] resets on
     * teardown, so the fallback path needs its own copy to resume from.
     */
    private var lastRemotePositionMs = 0L

    init {
        scope.launch {
            remotePlay.state.collect { remote ->
                if (remote.active && remote.positionMs > 0) {
                    lastRemotePositionMs = remote.positionMs
                }
            }
        }
    }

    fun setRepeatMode(mode: RepeatMode) = withController { c ->
        c.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    /** Cycles OFF → ALL → ONE → OFF (repeat button). */
    fun cycleRepeatMode() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // ------------------------------------------------------------------ internals

    private fun newItem(track: Track): MediaItem = MediaItems.toMediaItem(track, nextUid++)

    private fun currentUids(c: MediaController): List<Long> =
        (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).mediaId.toLongOrNull() }

    private fun indexOfUid(c: MediaController, uid: Long): Int? =
        (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == uid.toString() }

    /**
     * Reorders the timeline to [targetUids] without interrupting playback, in
     * at most [QueueReorder.MAX_OPS] timeline operations (NEVER one move per
     * track — see [QueueReorder] for the Bluetooth-flood rationale).
     *
     * Items are re-added via the controller, which strips their file URI
     * crossing the binder; PlaybackService.onAddMediaItems rebuilds it from the
     * path stashed in the metadata extras, same as every other enqueue path.
     */
    private fun applyOrder(c: MediaController, targetUids: List<Long>) {
        val items = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) }
        val itemsByUid = items.associateBy { it.mediaId.toLongOrNull() }
        val plan = QueueReorder.plan(
            current = items.mapNotNull { it.mediaId.toLongOrNull() },
            target = targetUids,
            anchorUid = c.currentMediaItem?.mediaId?.toLongOrNull(),
        ) ?: return // already in order

        plan.anchorToFront?.let { c.moveMediaItem(it.from, it.to) }
        val keepFrom = if (plan.anchored) 1 else 0
        if (c.mediaItemCount > keepFrom) c.removeMediaItems(keepFrom, c.mediaItemCount)
        c.addMediaItems(plan.tail.mapNotNull { itemsByUid[it] })
        plan.anchorToTarget?.let { c.moveMediaItem(it.from, it.to) }
    }

    private fun refresh(c: MediaController) {
        val items = (0 until c.mediaItemCount).map { MediaItems.toQueueItem(c.getMediaItemAt(it)) }
        _queue.value = items

        // Seed the uid counter above anything already in the restored queue.
        items.maxOfOrNull { it.uid }?.let { maxUid -> if (maxUid >= nextUid) nextUid = maxUid + 1 }
        val knownUids = originalOrderUids.toSet()
        if (knownUids.isEmpty() && items.isNotEmpty()) {
            originalOrderUids = items.map { it.uid }
        } else if (items.isNotEmpty() && items.none { it.uid in knownUids }) {
            // The queue was replaced outside this connection (Android Auto's
            // tap-to-queue, playback resumption): none of our bookkept uids
            // survive. Adopt the new order and drop the stale shuffle flag,
            // or the widget would keep showing the old queue's mode.
            originalOrderUids = items.map { it.uid }
            _shuffleMode.value = ShuffleMode.OFF
        }

        val currentTrack = c.currentMediaItem?.let { MediaItems.toTrack(it) }
        _state.value = NowPlayingState(
            track = currentTrack,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: currentTrack?.durationMs ?: 0,
            // shuffleMode is overlaid from _shuffleMode in the public [state].
            repeatMode = when (c.repeatMode) {
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                else -> RepeatMode.OFF
            },
            queueIndex = c.currentMediaItemIndex,
            queueSize = c.mediaItemCount,
        )
    }

    private fun withController(block: (MediaController) -> Unit) {
        scope.launch {
            val c = controller()
            block(c)
            refresh(c)
        }
    }

    companion object {
        private const val TAG = "PlayerConnection"
        private const val POSITION_TICK_MS = 500L

        /** The shuffle flavor "shuffle on" means, given the user's settings. */
        fun defaultShuffleMode(settings: ShuffleSettings): ShuffleMode = when {
            settings.ratingBias -> ShuffleMode.RATING_BIASED
            settings.antiRepeat -> ShuffleMode.ANTI_REPEAT
            else -> ShuffleMode.ALL
        }
    }
}
