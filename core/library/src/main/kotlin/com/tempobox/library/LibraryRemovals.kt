package com.tempobox.library

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Broadcasts track ids whose rows were removed because their FILES are gone
 * (the scanner's full-scan removal phase and the watcher's incremental scan).
 * The playback service listens and drops the matching queue entries: a
 * vanished file would otherwise sit in the queue until playback reaches it
 * and fails with FILE_NOT_FOUND.
 *
 * Deliberately NOT emitted by remove-from-library (the file exists and stays
 * playable; queue entries are kept by design) or by resetLibrary (files
 * exist; the reset only forgets rows). In-app permanent deletion prunes the
 * queue directly through its own result, not through this flow.
 */
@Singleton
class LibraryRemovals @Inject constructor() {

    private val _vanished = MutableSharedFlow<List<Long>>(extraBufferCapacity = 16)

    /** Ids removed because their file disappeared, in batches per scan pass. */
    val vanished: SharedFlow<List<Long>> = _vanished.asSharedFlow()

    /**
     * Suspending emit, so a full buffer backpressures the scanner instead of
     * discarding a batch. With no subscriber (service not running) the value
     * is dropped without suspending, which is right: no service, no queue.
     */
    suspend fun onVanished(trackIds: List<Long>) {
        if (trackIds.isNotEmpty()) _vanished.emit(trackIds)
    }
}
