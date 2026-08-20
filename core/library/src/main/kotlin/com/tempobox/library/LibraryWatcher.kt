package com.tempobox.library

import android.os.Build
import android.os.FileObserver
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Watches library locations for file changes while the app runs (enabled by
 * Settings ▸ Library ▸ "Rescan on start & watch locations", default ON).
 *
 * inotify (FileObserver) is not recursive, so one observer is registered per
 * directory in each location's tree. Events are debounced (file copies emit
 * many CLOSE_WRITE/MOVED events) and then handed to [MediaScanner.scanPaths].
 */
@Singleton
class LibraryWatcher @Inject constructor(
    private val scanner: MediaScanner,
) {
    private var observers: List<FileObserver> = emptyList()

    /** Paths that changed since the last debounce window. */
    private val dirty = MutableSharedFlow<String>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val pending = LinkedHashSet<String>()

    /** Begins watching [locations]; cancels any previous watch first. */
    fun start(locations: List<String>, scope: CoroutineScope) {
        stop()

        val directories = locations.map(::File).filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isDirectory }.toList() }

        observers = directories.map { dir -> newObserver(dir) }
        observers.forEach { it.startWatching() }
        Log.i(TAG, "Watching ${observers.size} directories")

        synchronized(pending) { pending.clear() }

        // Debounce: batch changes, rescan 2s after the last event settles.
        dirty
            .onEach { path -> synchronized(pending) { pending += path } }
            .sample(2.seconds)
            .onEach {
                val batch = synchronized(pending) {
                    val copy = pending.toList()
                    pending.clear()
                    copy
                }
                if (batch.isNotEmpty()) scanner.scanPaths(batch)
            }
            .launchIn(scope)
    }

    fun stop() {
        observers.forEach { it.stopWatching() }
        observers = emptyList()
    }

    @Suppress("DEPRECATION") // String-path constructor needed below API 29
    private fun newObserver(dir: File): FileObserver {
        // Named handler (not `onEvent`) to avoid recursively calling the override.
        val handle: (Int, String?) -> Unit = { event, childName ->
            if (childName != null && event and WATCH_MASK != 0) {
                val changed = File(dir, childName)
                if (changed.isDirectory) {
                    // New folder (e.g. an album copied in): watch its whole
                    // tree and queue every audio file inside it for scanning.
                    val subDirs = changed.walkTopDown().filter { it.isDirectory }.toList()
                    val newObservers = subDirs.map { newObserver(it) }
                    newObservers.forEach { it.startWatching() }
                    synchronized(this) { observers = observers + newObservers }
                    changed.walkTopDown().filter { it.isFile }
                        .forEach { dirty.tryEmit(it.absolutePath) }
                } else {
                    dirty.tryEmit(changed.absolutePath)
                }
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, WATCH_MASK) {
                override fun onEvent(event: Int, path: String?) = handle(event, path)
            }
        } else {
            object : FileObserver(dir.absolutePath, WATCH_MASK) {
                override fun onEvent(event: Int, path: String?) = handle(event, path)
            }
        }
    }

    companion object {
        private const val TAG = "LibraryWatcher"
        private const val WATCH_MASK =
            FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
                FileObserver.MOVED_FROM or FileObserver.DELETE or FileObserver.CREATE
    }
}
