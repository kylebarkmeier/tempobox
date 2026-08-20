package com.tempobox.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tempobox.model.NowPlayingState
import com.tempobox.playback.PlayerConnection
import com.tempobox.settings.AppSettings
import com.tempobox.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * App-shell state: user settings (theme, drawer items) and the live
 * now-playing snapshot for the mini player.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    val player: PlayerConnection,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val nowPlaying: StateFlow<NowPlayingState> = player.state
}
