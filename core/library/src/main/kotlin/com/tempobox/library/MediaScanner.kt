package com.tempobox.library

import android.util.Log
import com.tempobox.common.IoDispatcher
import com.tempobox.database.dao.TrackDao
import com.tempobox.database.entity.toEntity
import com.tempobox.model.AudioFormat
import com.tempobox.tags.TagReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scans the configured library locations into the database.
 *
 * Efficiency: tags are only (re-)read for files that are new or whose
 * (mtime, size) changed since the last scan — an unchanged 10k-track library
 * rescans in one directory walk with zero tag reads. User data (rating, play
 * count, date added) survives rescans via [TrackDao.upsertKeepingUserData].
 *
 * Concurrency: a [Mutex] collapses overlapping scan requests (e.g. startup
 * rescan + file-watcher trigger) into sequential runs.
 */
@Singleton
class MediaScanner @Inject constructor(
    private val trackDao: TrackDao,
    private val tagReader: TagReader,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    /**
     * Full scan of [locations]. Returns the resulting [ScanState.Done].
     * Safe to call from anywhere; concurrent calls queue behind the mutex.
     */
    suspend fun scan(locations: List<String>): ScanState.Done = mutex.withLock {
        withContext(ioDispatcher) {
            _state.value = ScanState.Scanning(0, 0)

            // 1. Enumerate every supported audio file under the locations.
            val filesOnDisk = locations
                .map(::File)
                .filter { it.isDirectory }
                .flatMap { root ->
                    root.walkTopDown()
                        .onEnter { dir -> !File(dir, ".nomedia").exists() } // honor .nomedia
                        .filter { it.isFile && it.extension.lowercase() in AudioFormat.SUPPORTED_EXTENSIONS }
                        .toList()
                }
                .distinctBy { it.absolutePath }

            _state.value = ScanState.Scanning(0, filesOnDisk.size)

            // 2. Diff against the database using (path → mtime/size).
            val known = trackDao.getAllScanMeta().associateBy { it.filePath }
            var added = 0
            var updated = 0
            val now = System.currentTimeMillis()
            val toUpsert = buildList {
                filesOnDisk.forEachIndexed { index, file ->
                    val existing = known[file.absolutePath]
                    val changed = existing == null ||
                        existing.dateModifiedMs != file.lastModified() ||
                        existing.sizeBytes != file.length()
                    if (changed) {
                        // 3. Read tags only for new/changed files.
                        tagReader.readTrack(file)?.let { track ->
                            add(track.copy(dateAddedMs = existing?.dateAddedMs ?: now).toEntity())
                            if (existing == null) added++ else updated++
                        }
                    }
                    if (index % PROGRESS_STEP == 0) {
                        _state.value = ScanState.Scanning(index + 1, filesOnDisk.size)
                    }
                }
            }
            trackDao.upsertKeepingUserData(toUpsert)

            // 4. Drop rows whose files vanished (chunked: SQLite's 999-var limit).
            val diskPaths = filesOnDisk.mapTo(HashSet()) { it.absolutePath }
            val missing = known.keys.filterNot { it in diskPaths }
            missing.chunked(DELETE_CHUNK).forEach { trackDao.deleteByPaths(it) }

            Log.i(TAG, "Scan done: +$added ~$updated -${missing.size} (of ${filesOnDisk.size} files)")
            ScanState.Done(added = added, updated = updated, removed = missing.size)
                .also { _state.value = it }
        }
    }

    /**
     * Incremental scan of specific [paths] (from the file watcher): re-reads
     * changed files and prunes deleted ones without walking the whole library.
     */
    suspend fun scanPaths(paths: Collection<String>) = mutex.withLock {
        withContext(ioDispatcher) {
            val now = System.currentTimeMillis()
            val gone = mutableListOf<String>()
            val entities = paths.mapNotNull { path ->
                val file = File(path)
                if (!file.isFile) {
                    gone += path
                    null
                } else if (file.extension.lowercase() in AudioFormat.SUPPORTED_EXTENSIONS) {
                    tagReader.readTrack(file)?.let { it.copy(dateAddedMs = now).toEntity() }
                } else {
                    null
                }
            }
            trackDao.upsertKeepingUserData(entities)
            gone.chunked(DELETE_CHUNK).forEach { trackDao.deleteByPaths(it) }
        }
    }

    companion object {
        private const val TAG = "MediaScanner"
        private const val PROGRESS_STEP = 25
        private const val DELETE_CHUNK = 500
    }
}
