package com.tempobox.library

import com.tempobox.model.AudioFormat
import com.tempobox.model.TagData
import com.tempobox.model.ThemeConfig
import com.tempobox.model.Track
import com.tempobox.settings.AppSettings
import com.tempobox.settings.ArtworkSettings
import com.tempobox.settings.BluetoothSettings
import com.tempobox.settings.LastFmSettings
import com.tempobox.settings.LibrarySettings
import com.tempobox.settings.NowPlayingSettings
import com.tempobox.settings.QueueSettings
import com.tempobox.settings.SettingsRepository
import com.tempobox.settings.ShuffleSettings
import com.tempobox.settings.UiSettings
import com.tempobox.tags.TagReader
import com.tempobox.tags.TagWriter
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Test doubles for library tests. The fake tag IO derives "tags" from the
 * file name (`Artist__Album__Title.mp3`) so tests control metadata by simply
 * naming files — no real audio needed.
 */

class FakeTagIO : TagReader, TagWriter {
    /** Paths that should fail to read/write (permission-denied simulation). */
    val failingPaths = mutableSetOf<String>()

    /** Explicit tag overrides applied by writeTags, keyed by path. */
    private val overrides = mutableMapOf<String, TagData>()

    var readCount = 0
        private set

    override fun readTrack(file: File): Track? {
        if (file.absolutePath in failingPaths) return null
        readCount++
        val parts = file.nameWithoutExtension.split("__")
        val artist = parts.getOrNull(0) ?: "Artist"
        val album = parts.getOrNull(1) ?: "Album"
        val title = parts.getOrNull(2) ?: file.nameWithoutExtension
        val base = Track(
            filePath = file.absolutePath,
            title = title,
            artist = artist,
            albumArtist = artist,
            album = album,
            genre = "Rock",
            year = 2000,
            durationMs = 180_000,
            format = AudioFormat.fromExtension(file.extension),
            sizeBytes = file.length(),
            dateModifiedMs = file.lastModified(),
        )
        val o = overrides[file.absolutePath] ?: return base
        return base.copy(
            title = o.title ?: base.title,
            artist = o.artist ?: base.artist,
            albumArtist = o.albumArtist ?: base.albumArtist,
            album = o.album ?: base.album,
            genre = o.genre ?: base.genre,
            year = o.year ?: base.year,
        )
    }

    override fun readEmbeddedArtwork(file: File): ByteArray? = null

    override fun writeTags(file: File, data: TagData): Track? {
        if (file.absolutePath in failingPaths) return null
        val merged = overrides[file.absolutePath]
        overrides[file.absolutePath] = TagData(
            title = data.title ?: merged?.title,
            artist = data.artist ?: merged?.artist,
            albumArtist = data.albumArtist ?: merged?.albumArtist,
            album = data.album ?: merged?.album,
            genre = data.genre ?: merged?.genre,
            year = data.year ?: merged?.year,
        )
        return readTrack(file)
    }

    override fun writeArtwork(file: File, imageBytes: ByteArray, mimeType: String): Boolean =
        file.absolutePath !in failingPaths
}

/** In-memory SettingsRepository for tests. */
class FakeSettingsRepository(
    initial: AppSettings = AppSettings(),
) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings = state

    override suspend fun updateLibrary(transform: (LibrarySettings) -> LibrarySettings) {
        state.value = state.value.copy(library = transform(state.value.library))
    }
    override suspend fun updateUi(transform: (UiSettings) -> UiSettings) {
        state.value = state.value.copy(ui = transform(state.value.ui))
    }
    override suspend fun updateNowPlaying(transform: (NowPlayingSettings) -> NowPlayingSettings) {
        state.value = state.value.copy(nowPlaying = transform(state.value.nowPlaying))
    }
    override suspend fun updateQueue(transform: (QueueSettings) -> QueueSettings) {
        state.value = state.value.copy(queue = transform(state.value.queue))
    }
    override suspend fun updateBluetooth(transform: (BluetoothSettings) -> BluetoothSettings) {
        state.value = state.value.copy(bluetooth = transform(state.value.bluetooth))
    }
    override suspend fun updateShuffle(transform: (ShuffleSettings) -> ShuffleSettings) {
        state.value = state.value.copy(shuffle = transform(state.value.shuffle))
    }
    override suspend fun updateLastFm(transform: (LastFmSettings) -> LastFmSettings) {
        state.value = state.value.copy(lastFm = transform(state.value.lastFm))
    }
    override suspend fun updateArtwork(transform: (ArtworkSettings) -> ArtworkSettings) {
        state.value = state.value.copy(artwork = transform(state.value.artwork))
    }
    override suspend fun updateTheme(transform: (ThemeConfig) -> ThemeConfig) {
        state.value = state.value.copy(theme = transform(state.value.theme))
    }
}
