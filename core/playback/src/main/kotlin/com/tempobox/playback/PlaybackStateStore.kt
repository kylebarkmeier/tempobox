package com.tempobox.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tempobox.model.RepeatMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists everything needed to restore playback after process death or
 * device restart (Settings ▸ Queue ▸ "Restore queue", default ON):
 * the queue's track ids in play order, current index/position, and the
 * shuffle/repeat modes.
 *
 * Uses its own small DataStore file — deliberately separate from user
 * settings, since this is app state, not preferences.
 */
@Singleton
class PlaybackStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Note: shuffle *mode* is intentionally not persisted — the persisted
     * queue order already reflects any shuffle that was applied.
     */
    @Serializable
    data class Snapshot(
        val trackIds: List<Long> = emptyList(),
        val currentIndex: Int = 0,
        val positionMs: Long = 0,
        val repeatMode: RepeatMode = RepeatMode.OFF,
    )

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun save(snapshot: Snapshot) {
        context.playbackDataStore.edit { prefs ->
            prefs[KEY] = json.encodeToString(Snapshot.serializer(), snapshot)
        }
    }

    suspend fun load(): Snapshot? =
        context.playbackDataStore.data.first()[KEY]?.let { raw ->
            runCatching { json.decodeFromString(Snapshot.serializer(), raw) }.getOrNull()
        }

    suspend fun clear() {
        context.playbackDataStore.edit { it.remove(KEY) }
    }

    private companion object {
        val KEY = stringPreferencesKey("queue_snapshot")
        val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore("tempobox_playback")
    }
}
