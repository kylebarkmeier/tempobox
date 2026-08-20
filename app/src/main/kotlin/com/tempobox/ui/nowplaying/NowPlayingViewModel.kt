package com.tempobox.ui.nowplaying

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tempobox.common.IoDispatcher
import com.tempobox.model.NowPlayingState
import com.tempobox.playback.PlayerConnection
import com.tempobox.settings.NowPlayingSettings
import com.tempobox.settings.SettingsRepository
import com.tempobox.tags.TagReader
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Now Playing state + the wallpaper corner-action ("wallpaper integration"):
 * sets the current album art as the device wallpaper.
 */
@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val player: PlayerConnection,
    settingsRepository: SettingsRepository,
    private val tagReader: TagReader,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    val state: StateFlow<NowPlayingState> = player.state

    val nowPlayingSettings: StateFlow<NowPlayingSettings> = settingsRepository.settings
        .map { it.nowPlaying }
        .stateIn(viewModelScope, SharingStarted.Eagerly, NowPlayingSettings())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** SET_AS_WALLPAPER corner action. */
    fun setCurrentArtAsWallpaper() {
        val track = state.value.track ?: return
        viewModelScope.launch {
            val ok = withContext(ioDispatcher) {
                runCatching {
                    val bytes = tagReader.readEmbeddedArtwork(File(track.filePath))
                        ?: return@runCatching false
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: return@runCatching false
                    WallpaperManager.getInstance(context).setBitmap(bitmap)
                    true
                }.getOrDefault(false)
            }
            _messages.tryEmit(if (ok) "Wallpaper updated" else "No usable album art on this track")
        }
    }
}
