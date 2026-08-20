package com.tempobox.artwork

import android.content.Context
import android.util.Log
import com.tempobox.common.IoDispatcher
import com.tempobox.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Artist images for the Album Artist card view.
 *
 * Source: the Discogs search API, authenticated with the user's personal
 * token (Settings ▸ UI ▸ Artist images). Results — including misses — are
 * cached on disk so each artist is queried at most once per [CACHE_TTL_MS].
 * Without a token (or on a miss) the UI falls back to an album-art collage.
 */
@Singleton
class ArtistImageRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsRepository: SettingsRepository,
    private val client: OkHttpClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    @Serializable
    private data class CacheEntry(val url: String?, val fetchedAtMs: Long)

    private val cacheFile = File(context.filesDir, "artist_images.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheSerializer = MapSerializer(String.serializer(), CacheEntry.serializer())
    private val mutex = Mutex()
    private var cache: MutableMap<String, CacheEntry>? = null

    /**
     * Image URL for [artistName], or null when unavailable (no token, network
     * error, or Discogs has no image). Never throws.
     */
    suspend fun imageUrl(artistName: String): String? = withContext(ioDispatcher) {
        if (artistName.isBlank()) return@withContext null
        val key = artistName.trim().lowercase()

        mutex.withLock {
            val map = loadCache()
            val hit = map[key]
            val now = System.currentTimeMillis()
            if (hit != null && now - hit.fetchedAtMs < CACHE_TTL_MS) return@withContext hit.url

            val token = settingsRepository.settings.first().artwork.discogsToken
            if (token.isBlank()) return@withContext null

            val url = runCatching { queryDiscogs(artistName, token) }
                .onFailure { Log.w(TAG, "Discogs lookup failed for $artistName: ${it.message}") }
                .getOrNull()
            map[key] = CacheEntry(url, now)
            persistCache(map)
            url
        }
    }

    /** Settings ▸ "Clear artist image cache". */
    suspend fun clearCache() = withContext(ioDispatcher) {
        mutex.withLock {
            cache = mutableMapOf()
            cacheFile.delete()
        }
    }

    // ------------------------------------------------------------------ internals

    private fun queryDiscogs(artistName: String, token: String): String? {
        val q = URLEncoder.encode(artistName, "UTF-8")
        val request = Request.Builder()
            .url("https://api.discogs.com/database/search?type=artist&q=$q&per_page=1&token=$token")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            // Minimal parse: first result's cover_image (skip Discogs' grey
            // "spacer.gif" placeholder).
            val cover = Regex("\"cover_image\"\\s*:\\s*\"([^\"]+)\"").find(body)
                ?.groupValues?.get(1)
                ?.replace("\\/", "/")
            return cover?.takeIf { "spacer.gif" !in it }
        }
    }

    private fun loadCache(): MutableMap<String, CacheEntry> {
        cache?.let { return it }
        val loaded = if (cacheFile.exists()) {
            runCatching { json.decodeFromString(cacheSerializer, cacheFile.readText()) }
                .getOrElse { emptyMap() }
        } else {
            emptyMap()
        }
        return loaded.toMutableMap().also { cache = it }
    }

    private fun persistCache(map: Map<String, CacheEntry>) {
        runCatching { cacheFile.writeText(json.encodeToString(cacheSerializer, map)) }
            .onFailure { Log.w(TAG, "Failed to persist artist image cache", it) }
    }

    companion object {
        private const val TAG = "ArtistImageRepository"
        private const val USER_AGENT = "TempoBox/1.0 +https://github.com/kbarkmeier/tempobox"
        private const val CACHE_TTL_MS = 1000L * 60 * 60 * 24 * 30 // 30 days
    }
}
