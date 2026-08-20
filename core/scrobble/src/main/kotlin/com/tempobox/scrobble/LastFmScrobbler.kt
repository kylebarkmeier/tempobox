package com.tempobox.scrobble

import android.util.Log
import com.tempobox.common.IoDispatcher
import com.tempobox.model.Track
import com.tempobox.settings.LastFmSettings
import com.tempobox.settings.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Last.fm implementation of [Scrobbler].
 *
 * - Disabled/unauthenticated settings → every call is a cheap no-op.
 * - Failed scrobbles are persisted by [PendingScrobbleStore] and retried by
 *   [flushPending] (called on app start and after each successful scrobble).
 * - Authentication (auth.getMobileSession) lives here too, used by Settings.
 */
@Singleton
class LastFmScrobbler @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val pendingStore: PendingScrobbleStore,
    private val client: OkHttpClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : Scrobbler {

    private val flushMutex = Mutex()

    override suspend fun updateNowPlaying(track: Track) {
        val cfg = config() ?: return
        if (!cfg.updateNowPlaying) return
        withContext(ioDispatcher) {
            val params = LastFmApi.nowPlayingParams(
                artist = track.artist.ifBlank { track.effectiveAlbumArtist },
                track = track.title,
                album = track.album,
                durationSec = track.durationMs / 1000,
                apiKey = cfg.apiKey,
                sessionKey = cfg.sessionKey,
            )
            // Now Playing is ephemeral — failures are not queued.
            runCatching { post(params, cfg.apiSecret) }
                .onFailure { Log.w(TAG, "now-playing update failed: ${it.message}") }
        }
    }

    override suspend fun scrobble(track: Track, startedAtEpochSec: Long) {
        val cfg = config() ?: return
        withContext(ioDispatcher) {
            val pending = PendingScrobble(
                artist = track.artist.ifBlank { track.effectiveAlbumArtist },
                title = track.title,
                album = track.album,
                timestampSec = startedAtEpochSec,
                durationSec = track.durationMs / 1000,
            )
            val ok = runCatching { submit(pending, cfg) }.getOrDefault(false)
            if (!ok) {
                pendingStore.add(pending)
                Log.i(TAG, "Scrobble queued offline: ${pending.artist} – ${pending.title}")
            } else {
                flushPending() // success → try to drain any backlog too
            }
        }
    }

    override suspend fun flushPending() {
        val cfg = config() ?: return
        withContext(ioDispatcher) {
            flushMutex.withLock {
                val queue = pendingStore.all()
                for (item in queue) {
                    val ok = runCatching { submit(item, cfg) }.getOrDefault(false)
                    if (!ok) break // still offline — keep the rest queued
                    pendingStore.remove(item)
                }
            }
        }
    }

    /**
     * Settings-screen login: exchanges username/password for a session key
     * (auth.getMobileSession) and stores it. Returns null on success, or a
     * human-readable error.
     */
    suspend fun authenticate(username: String, password: String): String? =
        withContext(ioDispatcher) {
            val settings = settingsRepository.settings.first().lastFm
            if (settings.apiKey.isBlank() || settings.apiSecret.isBlank()) {
                return@withContext "Enter your Last.fm API key and secret first"
            }
            val params = LastFmApi.mobileSessionParams(username, password, settings.apiKey)
            val body = runCatching { post(params, settings.apiSecret) }
                .getOrElse { return@withContext "Network error: ${it.message}" }
            val key = Regex("<key>([^<]+)</key>").find(body)?.groupValues?.get(1)
                ?: return@withContext parseError(body) ?: "Unexpected response from Last.fm"
            settingsRepository.updateLastFm { it.copy(username = username, sessionKey = key) }
            null
        }

    // ------------------------------------------------------------------ internals

    private suspend fun config(): LastFmSettings? {
        val cfg = settingsRepository.settings.first().lastFm
        val ready = cfg.scrobbleEnabled && cfg.apiKey.isNotBlank() &&
            cfg.apiSecret.isNotBlank() && cfg.sessionKey.isNotBlank()
        return if (ready) cfg else null
    }

    private fun submit(item: PendingScrobble, cfg: LastFmSettings): Boolean {
        val params = LastFmApi.scrobbleParams(
            artist = item.artist,
            track = item.title,
            album = item.album,
            timestampSec = item.timestampSec,
            durationSec = item.durationSec,
            apiKey = cfg.apiKey,
            sessionKey = cfg.sessionKey,
        )
        val body = post(params, cfg.apiSecret)
        return "<lfm status=\"ok\"" in body
    }

    /** Signed POST to the Last.fm REST endpoint; returns the XML body. */
    private fun post(params: Map<String, String>, secret: String): String {
        val signed = params + ("api_sig" to LastFmApi.sign(params, secret))
        val form = FormBody.Builder().apply {
            signed.forEach { (k, v) -> add(k, v) }
        }.build()
        val request = Request.Builder().url(LastFmApi.BASE_URL).post(form).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful || text.isNotBlank()) { "HTTP ${response.code}" }
            return text
        }
    }

    private fun parseError(body: String): String? =
        Regex("<error[^>]*>([^<]+)</error>").find(body)?.groupValues?.get(1)?.trim()

    companion object {
        private const val TAG = "LastFmScrobbler"
    }
}
