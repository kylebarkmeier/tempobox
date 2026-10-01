package com.tempobox.scrobble

import android.content.Context
import android.content.Intent
import com.tempobox.model.Track
import com.tempobox.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [Scrobbler] that hands tracks to a scrobbler app on the device via the SLS
 * broadcast API ([ScrobbleBroadcast]); that app owns credentials, submission
 * and any offline queue. Gated by Settings ▸ Scrobbling.
 */
@Singleton
class BroadcastScrobbler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) : Scrobbler {

    override suspend fun updateNowPlaying(track: Track) =
        broadcast(track, ScrobbleBroadcast.State.START)

    override suspend fun scrobble(track: Track, startedAtEpochSec: Long) =
        broadcast(track, ScrobbleBroadcast.State.COMPLETE)

    private suspend fun broadcast(track: Track, state: ScrobbleBroadcast.State) {
        if (!settingsRepository.settings.first().scrobble.broadcastScrobbles) return
        val intent = Intent(ScrobbleBroadcast.ACTION)
        ScrobbleBroadcast.extras(
            track = track,
            state = state,
            appName = "TempoBox",
            appPackage = context.packageName,
        ).forEach { (key, value) ->
            when (value) {
                is Int -> intent.putExtra(key, value)
                else -> intent.putExtra(key, value.toString())
            }
        }
        context.sendBroadcast(intent)
    }
}
