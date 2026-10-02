package com.tempobox.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tempobox.artwork.ArtistImageRepository
import com.tempobox.library.LibraryInitializer
import com.tempobox.library.LibraryPermissions
import com.tempobox.library.LibraryRepository
import com.tempobox.library.ScanState
import com.tempobox.model.ThemeConfig
import com.tempobox.settings.AppSettings
import com.tempobox.settings.ArtworkSettings
import com.tempobox.settings.BluetoothSettings
import com.tempobox.settings.ScrobbleSettings
import com.tempobox.settings.LibrarySettings
import com.tempobox.settings.NowPlayingSettings
import com.tempobox.settings.QueueSettings
import com.tempobox.settings.SettingsRepository
import com.tempobox.settings.ShuffleSettings
import com.tempobox.settings.UiSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One ViewModel for all settings screens: exposes the live [AppSettings]
 * snapshot, typed per-group updaters, and the imperative operations settings
 * can trigger (rescan, reset, cache clear).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val libraryInitializer: LibraryInitializer,
    private val libraryRepository: LibraryRepository,
    private val artistImageRepository: ArtistImageRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val scanState: StateFlow<ScanState> = libraryInitializer.scanState

    // ------------------------------------------------------------------ updaters

    fun updateLibrary(transform: (LibrarySettings) -> LibrarySettings) =
        launch { settingsRepository.updateLibrary(transform) }

    fun updateUi(transform: (UiSettings) -> UiSettings) =
        launch { settingsRepository.updateUi(transform) }

    fun updateNowPlaying(transform: (NowPlayingSettings) -> NowPlayingSettings) =
        launch { settingsRepository.updateNowPlaying(transform) }

    fun updateQueue(transform: (QueueSettings) -> QueueSettings) =
        launch { settingsRepository.updateQueue(transform) }

    fun updateBluetooth(transform: (BluetoothSettings) -> BluetoothSettings) =
        launch { settingsRepository.updateBluetooth(transform) }

    fun updateShuffle(transform: (ShuffleSettings) -> ShuffleSettings) =
        launch { settingsRepository.updateShuffle(transform) }

    fun updateScrobble(transform: (ScrobbleSettings) -> ScrobbleSettings) =
        launch { settingsRepository.updateScrobble(transform) }

    fun updateArtwork(transform: (ArtworkSettings) -> ArtworkSettings) =
        launch { settingsRepository.updateArtwork(transform) }

    fun updateTheme(transform: (ThemeConfig) -> ThemeConfig) =
        launch { settingsRepository.updateTheme(transform) }

    // ------------------------------------------------------------------ operations

    /** Settings ▸ Library ▸ "Rescan library". */
    fun rescanLibrary() = libraryInitializer.rescan()

    /** Settings ▸ Library ▸ "Reset library" (after two confirmations). */
    fun resetLibrary() = launch { libraryRepository.resetLibrary() }

    fun addLibraryLocation(path: String) = updateLibrary { current ->
        val normalized = path.trim().trimEnd('/')
        if (normalized.isBlank() || normalized in current.locations) current
        else current.copy(locations = current.locations + normalized)
    }

    fun removeLibraryLocation(path: String) = updateLibrary { current ->
        current.copy(locations = current.locations - path)
    }

    /** True when tag edits / deletes can touch arbitrary library folders. */
    fun hasAllFilesAccess(): Boolean = LibraryPermissions.hasAllFilesAccess()

    fun allFilesAccessIntent() = LibraryPermissions.allFilesAccessIntent(context)

    fun clearArtistImageCache() = launch { artistImageRepository.clearCache() }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
