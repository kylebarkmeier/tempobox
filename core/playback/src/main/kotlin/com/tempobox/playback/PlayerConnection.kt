package com.tempobox.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.tempobox.model.NowPlayingState
import com.tempobox.model.QueueItem
import com.tempobox.model.RepeatMode
import com.tempobox.model.ShuffleMode
import com.tempobox.model.Track
import com.tempobox.settings.SettingsRepository
import com.tempobox.settings.ShuffleSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
class PlayerConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shuffleEngine: ShuffleEngine,
    private val settingsRepository: SettingsRepository,
) {
    /** Controller calls must happen on the thread it connected on (main). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val controllerFuture = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
    ).buildAsync()

    private val _state = MutableStateFlow(NowPlayingState())
    val state: StateFlow<NowPlayingState> = _state.asStateFlow()

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue.asStateFlow()

    private val _shuffleMode = MutableStateFlow(ShuffleMode.OFF)

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

    /** Appends [tracks] to the end of the queue (starts the service if needed). */
    fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        scope.launch {
            val controller = controller()
            val allowDuplicates = settingsRepository.settings.first().queue.allowDuplicates
            val existingIds = _queue.value.map { it.track.id }.toSet()
            val toAdd = if (allowDuplicates) tracks else tracks.filter { it.id !in existingIds }
            if (toAdd.isEmpty()) return@launch
            val items = toAdd.map { newItem(it) }
            originalOrderUids = originalOrderUids + items.map { it.mediaId.toLong() }
            controller.addMediaItems(items)
            if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
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

    fun togglePlayPause() = withController { c ->
        when {
            c.mediaItemCount == 0 -> Unit
            c.playbackState == Player.STATE_IDLE -> { c.prepare(); c.play() }
            c.isPlaying -> c.pause()
            else -> c.play()
        }
    }

    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { it.seekToPreviousMediaItem() }
    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }
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
        if (originalOrderUids.isEmpty() && items.isNotEmpty()) {
            originalOrderUids = items.map { it.uid }
        }

        val currentTrack = c.currentMediaItem?.let { MediaItems.toTrack(it) }
        _state.value = NowPlayingState(
            track = currentTrack,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: currentTrack?.durationMs ?: 0,
            shuffleMode = _shuffleMode.value,
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
        private const val POSITION_TICK_MS = 500L

        /** The shuffle flavor "shuffle on" means, given the user's settings. */
        fun defaultShuffleMode(settings: ShuffleSettings): ShuffleMode = when {
            settings.ratingBias -> ShuffleMode.RATING_BIASED
            settings.antiRepeat -> ShuffleMode.ANTI_REPEAT
            else -> ShuffleMode.ALL
        }
    }
}
