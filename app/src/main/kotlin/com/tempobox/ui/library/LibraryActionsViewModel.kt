package com.tempobox.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tempobox.library.LibraryRepository
import com.tempobox.library.PlaylistRepository
import com.tempobox.model.RuleField
import com.tempobox.model.RuleOp
import com.tempobox.model.SmartRule
import com.tempobox.model.SwipeAction
import com.tempobox.model.TagData
import com.tempobox.model.Track
import com.tempobox.playback.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The shared "standard options" engine used by every library view, the queue,
 * and Now Playing: play / shuffle / add-to-queue / add-to-playlist /
 * auto-playlist / tag editing / rating / remove / delete — plus the dialog
 * state those actions need (confirmations, editors, pickers).
 *
 * One implementation ⇒ identical behavior everywhere (CLAUDE.md modularity).
 */
@HiltViewModel
class LibraryActionsViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
    private val playerConnection: PlayerConnection,
) : ViewModel() {

    /** Which modal is currently open (rendered by ActionDialogHost). */
    sealed interface Dialog {
        data class ConfirmRemove(val item: LibraryItem) : Dialog
        data class ConfirmDelete(val item: LibraryItem) : Dialog
        data class AddToPlaylist(val item: LibraryItem) : Dialog
        data class EditTags(val item: LibraryItem) : Dialog
        data class Rate(val track: Track) : Dialog
        data class CreateAutoPlaylist(val suggestedName: String, val initialRule: SmartRule) : Dialog
    }

    private val _dialog = MutableStateFlow<Dialog?>(null)
    val dialog: StateFlow<Dialog?> = _dialog.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-shot user feedback, collected into a snackbar by the app root. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun dismissDialog() {
        _dialog.value = null
    }

    // ------------------------------------------------------------------ immediate actions

    /** Play button: overwrite the queue with this item's tracks. */
    fun play(item: LibraryItem) = withTracks(item) { playerConnection.playTracks(it) }

    /** Play a track list starting from a tapped position (album/playlist rows). */
    fun playFrom(tracks: List<Track>, index: Int) =
        playerConnection.playTracks(tracks, index)

    /** Shuffle (menu): overwrite the queue, shuffled per the user's settings. */
    fun shuffle(item: LibraryItem) = withTracks(item) { playerConnection.playShuffled(it) }

    fun addToQueue(item: LibraryItem) = withTracks(item) {
        playerConnection.addToQueue(it)
        toast("Added ${countLabel(it.size)} to queue")
    }

    fun playNext(item: LibraryItem) = withTracks(item) {
        playerConnection.playNext(it)
        toast("Playing ${countLabel(it.size)} next")
    }

    fun rate(trackId: Long, rating: Int) {
        viewModelScope.launch {
            libraryRepository.setRating(trackId, rating)
            _dialog.value = null
        }
    }

    // ------------------------------------------------------------------ dialog openers

    fun requestRemoveFromLibrary(item: LibraryItem) { _dialog.value = Dialog.ConfirmRemove(item) }
    fun requestDelete(item: LibraryItem) { _dialog.value = Dialog.ConfirmDelete(item) }
    fun requestAddToPlaylist(item: LibraryItem) { _dialog.value = Dialog.AddToPlaylist(item) }
    fun requestEditTags(item: LibraryItem) { _dialog.value = Dialog.EditTags(item) }
    fun requestRate(track: Track) { _dialog.value = Dialog.Rate(track) }

    /** Auto playlist from the current view paradigm (e.g. swiping a genre row). */
    fun requestCreateAutoPlaylist(item: LibraryItem) {
        val rule = autoRuleFor(item)
        if (rule == null) {
            toast("Auto playlists need an artist, album, genre, or track")
            return
        }
        _dialog.value = Dialog.CreateAutoPlaylist(suggestedName = item.title, initialRule = rule)
    }

    // ------------------------------------------------------------------ dialog completions

    fun confirmRemoveFromLibrary(item: LibraryItem) {
        // Playlists are removed directly — an EMPTY playlist must still be
        // removable, so this can't go through withTracks.
        if (item is LibraryItem.PlaylistItem) {
            viewModelScope.launch {
                // Remove keeps the .m3u8 on disk; only Delete erases it.
                playlistRepository.deletePlaylist(item.playlist.id, deleteFile = false)
                toast("Removed playlist \"${item.title}\"")
                _dialog.value = null
            }
            return
        }
        withTracks(item) { tracks ->
            libraryRepository.removeFromLibrary(tracks.map { it.id })
            toast("Removed ${countLabel(tracks.size)} from library")
            _dialog.value = null
        }
    }

    fun confirmDelete(item: LibraryItem) {
        if (item is LibraryItem.PlaylistItem) {
            viewModelScope.launch {
                playlistRepository.deletePlaylist(item.playlist.id)
                toast("Deleted playlist \"${item.title}\"")
                _dialog.value = null
            }
            return
        }
        withTracks(item) { tracks ->
            val failed = libraryRepository.deleteFromDevice(tracks.map { it.id })
            toast(
                if (failed.isEmpty()) "Deleted ${countLabel(tracks.size)} from device"
                else "Could not delete ${failed.size} file(s) — grant All files access in Settings ▸ Library",
            )
            _dialog.value = null
        }
    }

    fun addToExistingPlaylist(item: LibraryItem, playlistId: Long, playlistName: String) =
        withTracks(item) { tracks ->
            playlistRepository.addToPlaylist(playlistId, tracks.map { it.id })
            toast("Added ${countLabel(tracks.size)} to \"$playlistName\"")
            _dialog.value = null
        }

    fun addToNewPlaylist(item: LibraryItem, name: String) = withTracks(item) { tracks ->
        val playlist = playlistRepository.createPlaylist(name, tracks.map { it.id })
        toast("Created \"${playlist.name}\" with ${countLabel(tracks.size)}")
        _dialog.value = null
    }

    fun createAutoPlaylist(name: String, rule: SmartRule) {
        viewModelScope.launch {
            val playlist = playlistRepository.createSmartPlaylist(name, rule)
            toast("Created auto playlist \"${playlist.name}\"")
            _dialog.value = null
        }
    }

    fun applyTagEdit(item: LibraryItem, data: TagData) = withTracks(item) { tracks ->
        if (data.isEmpty) {
            _dialog.value = null
            return@withTracks
        }
        val failed = libraryRepository.editTags(tracks.map { it.id }, data)
        toast(
            if (failed.isEmpty()) "Updated tags on ${countLabel(tracks.size)}"
            else "Failed to write ${failed.size} file(s) — grant All files access in Settings ▸ Library",
        )
        _dialog.value = null
    }

    // ------------------------------------------------------------------ swipes

    /** Runs a configured swipe gesture (Settings ▸ UI) against any item. */
    fun performSwipe(action: SwipeAction, item: LibraryItem) {
        when (action) {
            SwipeAction.NONE -> Unit
            SwipeAction.ADD_TO_QUEUE -> addToQueue(item)
            SwipeAction.ADD_TO_PLAYLIST -> requestAddToPlaylist(item)
            SwipeAction.CREATE_AUTO_PLAYLIST -> requestCreateAutoPlaylist(item)
            SwipeAction.SHUFFLE -> shuffle(item)
            SwipeAction.REMOVE_FROM_LIBRARY -> requestRemoveFromLibrary(item)
            SwipeAction.EDIT_TAGS -> requestEditTags(item)
            SwipeAction.DELETE_PERMANENTLY -> requestDelete(item)
        }
    }

    // ------------------------------------------------------------------ resolution

    /** Expands any library item to its tracks, in sensible play order. */
    suspend fun resolveTracks(item: LibraryItem): List<Track> = when (item) {
        is LibraryItem.TrackItem -> listOf(item.track)
        is LibraryItem.AlbumItem ->
            libraryRepository.observeAlbumTracks(item.album.name, item.album.albumArtist).first()
        is LibraryItem.ArtistItem ->
            if (item.byAlbumArtist) {
                libraryRepository.observeArtistTracks(item.artist.name).first()
            } else {
                libraryRepository.observeTrackArtistTracks(item.artist.name).first()
            }
        is LibraryItem.GenreItem ->
            libraryRepository.observeGenreTracks(item.genre.name).first()
        is LibraryItem.PlaylistItem ->
            playlistRepository.getPlaylistTracks(item.playlist)
        is LibraryItem.TracksItem -> item.tracks
    }

    /** Smart-rule seed for "Create auto playlist" from the item's paradigm. */
    private fun autoRuleFor(item: LibraryItem): SmartRule? = when (item) {
        is LibraryItem.ArtistItem ->
            SmartRule.Condition(
                if (item.byAlbumArtist) RuleField.ALBUM_ARTIST else RuleField.ARTIST,
                RuleOp.IS,
                item.artist.name,
            )
        is LibraryItem.GenreItem ->
            SmartRule.Condition(RuleField.GENRE, RuleOp.IS, item.genre.name)
        is LibraryItem.AlbumItem ->
            SmartRule.Condition(RuleField.ALBUM_ARTIST, RuleOp.IS, item.album.albumArtist)
        is LibraryItem.TrackItem ->
            SmartRule.Condition(RuleField.ARTIST, RuleOp.IS, item.track.artist)
        is LibraryItem.PlaylistItem -> null
        is LibraryItem.TracksItem -> null
    }

    private fun withTracks(item: LibraryItem, block: suspend (List<Track>) -> Unit) {
        viewModelScope.launch {
            val tracks = resolveTracks(item)
            if (tracks.isEmpty()) toast("No tracks found") else block(tracks)
        }
    }

    private fun toast(message: String) {
        _messages.tryEmit(message)
    }

    private fun countLabel(count: Int) = if (count == 1) "1 track" else "$count tracks"
}
