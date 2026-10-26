package com.tempobox.playback

import android.util.Log
import com.tempobox.common.ApplicationScope
import com.tempobox.common.resultOf
import com.tempobox.library.AddToPlaylistResult
import com.tempobox.library.LibraryRepository
import com.tempobox.library.PlaylistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs spoken commands and reports what really happened, in the words the
 * toast shows and [VoiceLog] keeps. [VoiceCommand.Immediate] commands go
 * straight to [PlayerConnection.execute]; everything else resolves its
 * names through [VoiceSearch] here, so a miss says "nothing matched" instead
 * of claiming success or quietly playing something else. The assistant and
 * Android Auto paths stay in [PlaybackService], where a miss must still play
 * something because the car cannot show a toast.
 *
 * [handle] owns the coroutine: the in-app dialog finishes before a lookup
 * completes, so the work runs on the application scope and the outcome
 * comes back through a callback.
 */
@Singleton
class VoiceActions @Inject constructor(
    private val player: PlayerConnection,
    private val voiceSearch: VoiceSearch,
    private val playlistRepository: PlaylistRepository,
    private val libraryRepository: LibraryRepository,
    private val voiceLog: VoiceLog,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /**
     * Parses [heard] from [source] (a [VoiceLog] constant). Returns null, after
     * logging, when the words form no command so the caller can show what it
     * heard. Otherwise runs the command in the background, logs the outcome,
     * and hands it to [onOutcome] on an arbitrary thread.
     */
    fun handle(
        heard: String,
        alternatives: List<String>,
        source: String,
        onOutcome: (VoiceCommand, String) -> Unit,
    ): VoiceCommand? {
        val command = VoiceCommand.parse(heard)
        if (command == null) {
            voiceLog.record(
                source, heard, command = null,
                outcome = if (heard.isBlank()) "heard nothing" else "no command",
                alternatives = alternatives,
            )
            return null
        }
        scope.launch {
            val outcome = resultOf { execute(command) }.getOrElse { e ->
                Log.e(TAG, "Voice command $command failed", e)
                "Couldn't do that: ${e.message ?: e.javaClass.simpleName}"
            }
            voiceLog.record(source, heard, command.toString(), outcome, alternatives)
            onOutcome(command, outcome)
        }
        return command
    }

    /** Executes [command] and returns the outcome text. */
    suspend fun execute(command: VoiceCommand): String = when (command) {
        is VoiceCommand.Immediate -> player.execute(command)

        is VoiceCommand.PlaySearch -> resolved(command.query) { r ->
            // Replaces the queue like the library's Play and Shuffle buttons;
            // both suspend until the controller has the new queue.
            if (r.shuffle) player.playShuffled(r.tracks) else player.playTracks(r.tracks)
            ActionMessages.playing(r.tracks.size, r.shuffle, r.description)
        }

        is VoiceCommand.AddToQueue -> resolved(command.query) { r ->
            ActionMessages.addedToQueue(player.addToQueue(r.tracks), r.tracks.size, r.description)
        }

        is VoiceCommand.PlayNext -> resolved(command.query) { r ->
            player.playNext(r.tracks)
            ActionMessages.playingNext(r.tracks.size, r.description)
        }

        is VoiceCommand.AddToPlaylist -> addToPlaylist(command)
    }

    private suspend fun resolved(query: String, block: suspend (VoiceSearch.Resolution) -> String): String {
        val resolution = voiceSearch.resolve(query) ?: return nothingMatched(query)
        return block(resolution)
    }

    private suspend fun addToPlaylist(command: VoiceCommand.AddToPlaylist): String {
        val playlists = playlistRepository.getPlaylists()
        val playlist = voiceSearch.findPlaylist(command.playlist, playlists)
            ?: return "No playlist named \"${command.playlist}\""
        // Refusals come before any lookup, so the reason is the real one.
        if (playlist.isSmart) return ActionMessages.autoPlaylistRefusal(playlist.name)
        if (playlist.ruleBroken) return ActionMessages.brokenPlaylistRefusal(playlist.name)

        val (tracks, what) = if (command.query == null) {
            val current = player.state.value.track
                ?: return "Nothing is playing to add to \"${playlist.name}\""
            if (current.isNetworkStream || current.id <= 0) {
                return "\"${current.title}\" is a network stream, not a library track"
            }
            // The playing item is a snapshot; its row may have gone in a rescan.
            val live = libraryRepository.getTracksByIds(listOf(current.id)).firstOrNull()
                ?: return "\"${current.title}\" is no longer in the library"
            listOf(live) to "track: ${live.title}"
        } else {
            val resolution = voiceSearch.resolve(command.query, playlists)
                ?: return nothingMatched(command.query)
            resolution.tracks to resolution.description
        }
        return when (playlistRepository.addToPlaylist(playlist.id, tracks.map { it.id })) {
            AddToPlaylistResult.ADDED -> ActionMessages.addedToPlaylist(tracks.size, playlist.name, what)
            AddToPlaylistResult.SMART_PLAYLIST -> ActionMessages.autoPlaylistRefusal(playlist.name)
            AddToPlaylistResult.MISSING -> ActionMessages.playlistGone(playlist.name)
        }
    }

    /** The miss names the request as spoken, without the command words ("shuffle"). */
    private fun nothingMatched(query: String) =
        "Nothing in the library matched \"${VoiceSearch.spokenName(query)}\""

    private companion object {
        const val TAG = "VoiceActions"
    }
}
