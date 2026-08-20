package com.tempobox.scrobble

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A scrobble waiting for connectivity. */
@Serializable
data class PendingScrobble(
    val artist: String,
    val title: String,
    val album: String,
    val timestampSec: Long,
    val durationSec: Long,
)

/**
 * Durable FIFO of scrobbles that couldn't be sent (offline, API hiccup).
 * Stored as a small JSON file in app-private storage; capped so a long
 * offline stretch can't grow unbounded.
 */
@Singleton
class PendingScrobbleStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val file = File(context.filesDir, "pending_scrobbles.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PendingScrobble.serializer())
    private val lock = Any()

    fun all(): List<PendingScrobble> = synchronized(lock) { readAll() }

    fun add(item: PendingScrobble) = synchronized(lock) {
        val list = (readAll() + item).takeLast(MAX_PENDING)
        writeAll(list)
    }

    fun remove(item: PendingScrobble) = synchronized(lock) {
        writeAll(readAll() - item)
    }

    fun clear() = synchronized(lock) { writeAll(emptyList()) }

    private fun readAll(): List<PendingScrobble> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString(serializer, file.readText()) }
            .onFailure { Log.w(TAG, "Corrupt pending-scrobble file, resetting", it) }
            .getOrElse {
                file.delete()
                emptyList()
            }
    }

    private fun writeAll(list: List<PendingScrobble>) {
        runCatching { file.writeText(json.encodeToString(serializer, list)) }
            .onFailure { Log.w(TAG, "Failed to persist pending scrobbles", it) }
    }

    companion object {
        private const val TAG = "PendingScrobbleStore"

        /** Last.fm's own batch cap is 50; keep a generous offline buffer. */
        private const val MAX_PENDING = 500
    }
}
