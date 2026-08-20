package com.tempobox.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tempobox.model.ThemeConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preferences-DataStore-backed [SettingsRepository].
 *
 * Each settings *group* is stored as one JSON value under one key. Decoding is
 * defensive: a missing or unreadable group falls back to its defaults, so a
 * malformed value (or a schema change) can never crash the app.
 */
@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    private val json = Json {
        ignoreUnknownKeys = true // tolerate fields removed in future versions
        encodeDefaults = true    // stable output, simpler debugging
    }

    private object Keys {
        val LIBRARY = stringPreferencesKey("library")
        val UI = stringPreferencesKey("ui")
        val NOW_PLAYING = stringPreferencesKey("now_playing")
        val QUEUE = stringPreferencesKey("queue")
        val BLUETOOTH = stringPreferencesKey("bluetooth")
        val SHUFFLE = stringPreferencesKey("shuffle")
        val LAST_FM = stringPreferencesKey("last_fm")
        val THEME = stringPreferencesKey("theme")
    }

    override val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            library = prefs.decode(Keys.LIBRARY, LibrarySettings.serializer()) ?: LibrarySettings(),
            ui = prefs.decode(Keys.UI, UiSettings.serializer()) ?: UiSettings(),
            nowPlaying = prefs.decode(Keys.NOW_PLAYING, NowPlayingSettings.serializer()) ?: NowPlayingSettings(),
            queue = prefs.decode(Keys.QUEUE, QueueSettings.serializer()) ?: QueueSettings(),
            bluetooth = prefs.decode(Keys.BLUETOOTH, BluetoothSettings.serializer()) ?: BluetoothSettings(),
            shuffle = prefs.decode(Keys.SHUFFLE, ShuffleSettings.serializer()) ?: ShuffleSettings(),
            lastFm = prefs.decode(Keys.LAST_FM, LastFmSettings.serializer()) ?: LastFmSettings(),
            theme = prefs.decode(Keys.THEME, ThemeConfig.serializer()) ?: ThemeConfig(),
        )
    }

    override suspend fun updateLibrary(transform: (LibrarySettings) -> LibrarySettings) =
        updateGroup(Keys.LIBRARY, LibrarySettings.serializer(), { LibrarySettings() }, transform)

    override suspend fun updateUi(transform: (UiSettings) -> UiSettings) =
        updateGroup(Keys.UI, UiSettings.serializer(), { UiSettings() }, transform)

    override suspend fun updateNowPlaying(transform: (NowPlayingSettings) -> NowPlayingSettings) =
        updateGroup(Keys.NOW_PLAYING, NowPlayingSettings.serializer(), { NowPlayingSettings() }, transform)

    override suspend fun updateQueue(transform: (QueueSettings) -> QueueSettings) =
        updateGroup(Keys.QUEUE, QueueSettings.serializer(), { QueueSettings() }, transform)

    override suspend fun updateBluetooth(transform: (BluetoothSettings) -> BluetoothSettings) =
        updateGroup(Keys.BLUETOOTH, BluetoothSettings.serializer(), { BluetoothSettings() }, transform)

    override suspend fun updateShuffle(transform: (ShuffleSettings) -> ShuffleSettings) =
        updateGroup(Keys.SHUFFLE, ShuffleSettings.serializer(), { ShuffleSettings() }, transform)

    override suspend fun updateLastFm(transform: (LastFmSettings) -> LastFmSettings) =
        updateGroup(Keys.LAST_FM, LastFmSettings.serializer(), { LastFmSettings() }, transform)

    override suspend fun updateTheme(transform: (ThemeConfig) -> ThemeConfig) =
        updateGroup(Keys.THEME, ThemeConfig.serializer(), { ThemeConfig() }, transform)

    // ------------------------------------------------------------------ helpers

    private fun <T> Preferences.decode(key: Preferences.Key<String>, serializer: KSerializer<T>): T? =
        this[key]?.let { raw -> runCatching { json.decodeFromString(serializer, raw) }.getOrNull() }

    private suspend fun <T> updateGroup(
        key: Preferences.Key<String>,
        serializer: KSerializer<T>,
        defaults: () -> T,
        transform: (T) -> T,
    ) {
        dataStore.edit { prefs ->
            val current = prefs.decode(key, serializer) ?: defaults()
            prefs[key] = json.encodeToString(serializer, transform(current))
        }
    }
}
