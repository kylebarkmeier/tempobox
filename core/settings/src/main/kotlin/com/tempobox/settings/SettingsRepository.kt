package com.tempobox.settings

import com.tempobox.model.ThemeConfig
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for user preferences.
 *
 * UI reads [settings]; writes go through the per-group `update*` functions
 * (read-modify-write of one group, atomic within DataStore). Nothing outside
 * this module reads DataStore directly — see CLAUDE.md rule 5.
 */
interface SettingsRepository {

    /** Hot flow of the full settings snapshot; emits on every change. */
    val settings: Flow<AppSettings>

    suspend fun updateLibrary(transform: (LibrarySettings) -> LibrarySettings)
    suspend fun updateUi(transform: (UiSettings) -> UiSettings)
    suspend fun updateNowPlaying(transform: (NowPlayingSettings) -> NowPlayingSettings)
    suspend fun updateQueue(transform: (QueueSettings) -> QueueSettings)
    suspend fun updateBluetooth(transform: (BluetoothSettings) -> BluetoothSettings)
    suspend fun updateShuffle(transform: (ShuffleSettings) -> ShuffleSettings)
    suspend fun updateLastFm(transform: (LastFmSettings) -> LastFmSettings)
    suspend fun updateTheme(transform: (ThemeConfig) -> ThemeConfig)
}
