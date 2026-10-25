package com.tempobox.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tempobox.model.NowPlayingState
import com.tempobox.model.QueueItem
import com.tempobox.model.filterQueueItems
import com.tempobox.playback.PlayerConnection
import com.tempobox.settings.SettingsRepository
import com.tempobox.ui.library.LibraryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Queue screen state: the live queue + multi-selection (null = off; entering
 * via the toolbar toggle or a long-press).
 */
@HiltViewModel
class QueueViewModel @Inject constructor(
    private val player: PlayerConnection,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val queue: StateFlow<List<QueueItem>> = player.queue
    val state: StateFlow<NowPlayingState> = player.state

    /**
     * Free-form search over the queue list. View-only and transient: it
     * narrows [visibleQueue], never the actual player queue, and the panel
     * clears it on dispose. Playback, removal, and selection all address
     * items by uid, so they stay correct while the view is filtered.
     */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** The queue as displayed: the full queue narrowed by the search query. */
    val visibleQueue: StateFlow<List<QueueItem>> =
        combine(queue, _searchQuery) { items, query -> items.filterQueueItems(query) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, queue.value)

    /** Selected uids, or null when multi-select is off. */
    private val _selection = MutableStateFlow<Set<Long>?>(null)
    val selection: StateFlow<Set<Long>?> = _selection.asStateFlow()

    init {
        // Rows can vanish underneath a selection (a permanent delete prunes
        // the queue, another controller edits it); keep only uids that still
        // exist so the count and the bulk actions stay truthful.
        viewModelScope.launch {
            queue.collect { items ->
                val current = _selection.value ?: return@collect
                val present = items.mapTo(HashSet()) { it.uid }
                val pruned = current.filterTo(mutableSetOf()) { it in present }
                if (pruned.size != current.size) _selection.value = pruned
            }
        }
    }

    val confirmClear: StateFlow<Boolean> = settingsRepository.settings
        .map { it.queue.confirmClearQueue }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // ------------------------------------------------------------------ playback

    fun playItem(uid: Long) = player.playQueueItem(uid)

    fun remove(uid: Long) = player.removeQueueItems(listOf(uid))

    fun clearQueue() {
        player.clearQueue()
        _selection.value = null
    }

    // ------------------------------------------------------------------ selection

    fun toggleMultiSelect() {
        _selection.value = if (_selection.value == null) emptySet() else null
    }

    fun startMultiSelect(uid: Long) {
        _selection.value = (_selection.value ?: emptySet()) + uid
    }

    fun toggleSelected(uid: Long) {
        val current = _selection.value ?: return
        _selection.value = if (uid in current) current - uid else current + uid
    }

    fun removeSelected() {
        val selected = _selection.value.orEmpty()
        if (selected.isNotEmpty()) player.removeQueueItems(selected)
        _selection.value = emptySet()
    }

    /** Selected tracks wrapped for the shared action layer (bulk operations). */
    fun selectedItem(): LibraryItem? {
        val selected = _selection.value.orEmpty()
        val tracks = queue.value.filter { it.uid in selected }.map { it.track }
        if (tracks.isEmpty()) return null
        val label = if (tracks.size == 1) tracks.first().title else "${tracks.size} tracks"
        return LibraryItem.TracksItem(label, tracks)
    }

    companion object {
        /**
         * Where the playing track sits in the displayed (possibly filtered)
         * list, or -1 when it is not visible. The panel scrolls here when it
         * opens, so the view starts at the current track whatever the queue
         * order is (shuffle off moves it mid-list).
         */
        fun scrollTargetIndex(visible: List<QueueItem>, queue: List<QueueItem>, queueIndex: Int): Int {
            val uid = queue.getOrNull(queueIndex)?.uid ?: return -1
            return visible.indexOfFirst { it.uid == uid }
        }
    }
}
