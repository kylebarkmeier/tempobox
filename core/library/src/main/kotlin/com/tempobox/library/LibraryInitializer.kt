package com.tempobox.library

import com.tempobox.common.ApplicationScope
import com.tempobox.database.dao.TrackDao
import com.tempobox.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Wires the library lifecycle together at app start (called from the
 * Application class):
 *
 *  1. Rescans on startup when "auto rescan & watch" is enabled (default ON).
 *  2. Starts/stops the [LibraryWatcher] as the setting or locations change.
 *  3. Imports playlist files found in the locations after each scan.
 *  4. Keeps smart-playlist `.m3u8` exports fresh as the library changes.
 */
@Singleton
class LibraryInitializer @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val scanner: MediaScanner,
    private val watcher: LibraryWatcher,
    private val playlistRepository: PlaylistRepository,
    private val trackDao: TrackDao,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private var watcherJob: Job? = null

    fun onAppStart() {
        // 1. Startup rescan (+ playlist file import) if enabled.
        appScope.launch {
            val library = settingsRepository.settings.first().library
            if (library.autoRescanAndWatch && library.locations.isNotEmpty()) {
                scanner.scan(library.locations)
                playlistRepository.importPlaylistFiles(library.locations)
            }
        }

        // 2. (Re)start the watcher whenever the setting or locations change.
        settingsRepository.settings
            .map { it.library.autoRescanAndWatch to it.library.locations }
            .distinctUntilChanged()
            .onEach { (watch, locations) ->
                watcherJob?.cancel()
                watcher.stop()
                if (watch && locations.isNotEmpty()) {
                    watcherJob = appScope.launch { watcher.start(locations, this) }
                }
            }
            .launchIn(appScope)

        // 3. Debounced smart-playlist re-export on any library change.
        trackDao.observeAll()
            .map { rows -> rows.size to rows.sumOf { it.dateModifiedMs } } // cheap change signature
            .distinctUntilChanged()
            .drop(1) // skip initial emission at startup
            .sample(5.seconds)
            .onEach { playlistRepository.refreshSmartExports() }
            .launchIn(appScope)
    }

    /** Manual "Rescan library" from Settings. */
    fun rescan() {
        appScope.launch {
            val locations = settingsRepository.settings.first().library.locations
            if (locations.isNotEmpty()) {
                scanner.scan(locations)
                playlistRepository.importPlaylistFiles(locations)
            }
        }
    }

    val scanState = scanner.state
}
