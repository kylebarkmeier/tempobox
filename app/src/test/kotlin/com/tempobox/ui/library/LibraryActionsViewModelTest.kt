package com.tempobox.ui.library

import com.google.common.truth.Truth.assertThat
import com.tempobox.library.AddToPlaylistResult
import com.tempobox.library.LibraryRepository
import com.tempobox.library.PlaylistRepository
import com.tempobox.model.Genre
import com.tempobox.model.Playlist
import com.tempobox.model.RuleField
import com.tempobox.model.RuleOp
import com.tempobox.model.SmartRule
import com.tempobox.model.SwipeAction
import com.tempobox.model.Track
import com.tempobox.playback.PlayerConnection
import com.tempobox.model.Album
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The shared "standard options" layer used by every screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryActionsViewModelTest {

    private val libraryRepository: LibraryRepository = mockk(relaxed = true)
    private val playlistRepository: PlaylistRepository = mockk(relaxed = true)
    private val player: PlayerConnection = mockk(relaxed = true)
    private val appContext: android.content.Context = mockk(relaxed = true)

    private lateinit var viewModel: LibraryActionsViewModel

    private val track = Track(id = 1, filePath = "/a.mp3", title = "Song", artist = "Artist")

    private val collectors = mutableListOf<Job>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = LibraryActionsViewModel(
            libraryRepository, playlistRepository, player,
            appContext, UnconfinedTestDispatcher(),
        )
    }

    @After
    fun tearDown() {
        collectors.forEach(Job::cancel)
        Dispatchers.resetMain()
    }

    /** Records every navigation request the view model emits from now on. */
    private fun collectNavigations(): List<LibraryActionsViewModel.Navigation> {
        val events = mutableListOf<LibraryActionsViewModel.Navigation>()
        collectors += CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.navigations.collect { events += it }
        }
        return events
    }

    /** Records every snackbar message the view model emits from now on. */
    private fun collectMessages(): List<String> {
        val events = mutableListOf<String>()
        collectors += CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.messages.collect { events += it }
        }
        return events
    }

    @Test
    fun `play on a track overwrites the queue with that track`() {
        viewModel.play(LibraryItem.TrackItem(track))
        coVerify { player.playTracks(listOf(track), 0) }
    }

    @Test
    fun `play on a genre resolves its tracks first`() {
        val genreTracks = listOf(track, track.copy(id = 2, filePath = "/b.mp3"))
        coEvery { libraryRepository.observeGenreTracks("Rock") } returns flowOf(genreTracks)

        viewModel.play(LibraryItem.GenreItem(Genre("Rock", trackCount = 2, albumCount = 1)))
        coVerify { player.playTracks(genreTracks, 0) }
    }

    @Test
    fun `shuffle delegates to playShuffled`() {
        viewModel.shuffle(LibraryItem.TrackItem(track))
        coVerify { player.playShuffled(listOf(track)) }
    }

    @Test
    fun `swipe dispatch covers direct actions and dialog openers`() {
        val item = LibraryItem.TrackItem(track)

        viewModel.performSwipe(SwipeAction.ADD_TO_QUEUE, item)
        coVerify { player.addToQueue(listOf(track)) }

        viewModel.performSwipe(SwipeAction.EDIT_TAGS, item)
        assertThat(viewModel.dialog.value)
            .isEqualTo(LibraryActionsViewModel.Dialog.EditTags(item))

        viewModel.performSwipe(SwipeAction.DELETE_PERMANENTLY, item)
        assertThat(viewModel.dialog.value)
            .isEqualTo(LibraryActionsViewModel.Dialog.ConfirmDelete(item))
    }

    @Test
    fun `remove-from-library needs confirmation then executes`() {
        val item = LibraryItem.TrackItem(track)
        viewModel.requestRemoveFromLibrary(item)
        assertThat(viewModel.dialog.value)
            .isEqualTo(LibraryActionsViewModel.Dialog.ConfirmRemove(item))

        viewModel.confirmRemoveFromLibrary(item)
        coVerify { libraryRepository.removeFromLibrary(listOf(1L)) }
        assertThat(viewModel.dialog.value).isNull()
    }

    @Test
    fun `rate persists and closes the dialog`() {
        viewModel.requestRate(track)
        viewModel.rate(track.id, 5)
        coVerify { libraryRepository.setRating(1L, 5) }
        assertThat(viewModel.dialog.value).isNull()
    }

    @Test
    fun `auto playlist seed uses the item's view paradigm`() {
        viewModel.requestCreateAutoPlaylist(
            LibraryItem.GenreItem(Genre("Rock", trackCount = 1, albumCount = 1)),
        )
        val dialog = viewModel.dialog.value as LibraryActionsViewModel.Dialog.CreateAutoPlaylist
        assertThat(dialog.initialRule)
            .isEqualTo(SmartRule.Condition(RuleField.GENRE, RuleOp.IS, "Rock"))
    }

    @Test
    fun `auto playlist is not offered for static playlists`() {
        viewModel.requestCreateAutoPlaylist(
            LibraryItem.PlaylistItem(Playlist(id = 1, name = "Mix")),
        )
        assertThat(viewModel.dialog.value).isNull()
    }

    @Test
    fun `go to artist on a track opens the track-artist view`() {
        val events = collectNavigations()
        viewModel.goToArtist(LibraryItem.TrackItem(track))
        assertThat(events).containsExactly(
            LibraryActionsViewModel.Navigation.ToArtist("Artist", byAlbumArtist = false),
        )
    }

    @Test
    fun `go to artist falls back to the album artist when the artist tag is blank`() {
        val events = collectNavigations()
        viewModel.goToArtist(LibraryItem.TrackItem(track.copy(artist = "", albumArtist = "AA")))
        assertThat(events).containsExactly(
            LibraryActionsViewModel.Navigation.ToArtist("AA", byAlbumArtist = true),
        )
    }

    @Test
    fun `go to album on a track uses the album-artist grouping`() {
        val events = collectNavigations()
        viewModel.goToAlbum(LibraryItem.TrackItem(track.copy(albumArtist = "AA", album = "LP")))
        assertThat(events).containsExactly(
            LibraryActionsViewModel.Navigation.ToAlbum(albumArtist = "AA", album = "LP"),
        )
    }

    @Test
    fun `go to artist on an album opens its album artist`() {
        val events = collectNavigations()
        viewModel.goToArtist(LibraryItem.AlbumItem(album("LP", "AA")))
        assertThat(events).containsExactly(
            LibraryActionsViewModel.Navigation.ToArtist("AA", byAlbumArtist = true),
        )
    }

    @Test
    fun `go to targets a single queue selection like a plain track`() {
        val events = collectNavigations()
        val selection = LibraryItem.TracksItem("Song", listOf(track.copy(album = "LP")))
        viewModel.goToArtist(selection)
        viewModel.goToAlbum(selection)
        assertThat(events).containsExactly(
            LibraryActionsViewModel.Navigation.ToArtist("Artist", byAlbumArtist = false),
            LibraryActionsViewModel.Navigation.ToAlbum(albumArtist = "Artist", album = "LP"),
        ).inOrder()
    }

    @Test
    fun `go to emits nothing for items without a single destination`() {
        val events = collectNavigations()
        viewModel.goToArtist(LibraryItem.GenreItem(Genre("Rock", trackCount = 1, albumCount = 1)))
        viewModel.goToAlbum(LibraryItem.AlbumItem(album("LP", "AA")))
        viewModel.goToArtist(
            LibraryItem.TracksItem("2 tracks", listOf(track, track.copy(id = 2))),
        )
        assertThat(events).isEmpty()
    }

    @Test
    fun `destination helpers drive menu visibility per item type`() {
        assertThat(LibraryActionsViewModel.artistDestination(LibraryItem.TrackItem(track))).isNotNull()
        assertThat(LibraryActionsViewModel.albumDestination(LibraryItem.TrackItem(track))).isNotNull()
        assertThat(LibraryActionsViewModel.artistDestination(LibraryItem.AlbumItem(album("LP", "AA")))).isNotNull()
        // Blank album artist ⇒ nowhere to go.
        assertThat(LibraryActionsViewModel.artistDestination(LibraryItem.AlbumItem(album("LP", "")))).isNull()
        assertThat(LibraryActionsViewModel.albumDestination(LibraryItem.AlbumItem(album("LP", "AA")))).isNull()
        assertThat(
            LibraryActionsViewModel.artistDestination(LibraryItem.PlaylistItem(Playlist(id = 1, name = "Mix"))),
        ).isNull()
    }

    private fun album(name: String, albumArtist: String) = Album(
        name = name,
        albumArtist = albumArtist,
        year = null,
        trackCount = 1,
        durationMs = 0,
        artworkTrackPath = null,
        dateAddedMs = 0,
        dateModifiedMs = 0,
    )

    @Test
    fun `addToQueue says Already in queue when dedupe filtered everything`() {
        coEvery { player.addToQueue(listOf(track)) } returns 0
        val messages = collectMessages()

        viewModel.addToQueue(LibraryItem.TrackItem(track))

        assertThat(messages).containsExactly("Already in queue")
    }

    @Test
    fun `addToQueue reports the count that was really added`() {
        coEvery { player.addToQueue(listOf(track)) } returns 1
        val messages = collectMessages()

        viewModel.addToQueue(LibraryItem.TrackItem(track))

        assertThat(messages).containsExactly("Added 1 track to queue")
    }

    @Test
    fun `a refused playlist add is reported, not claimed as success`() {
        coEvery { playlistRepository.addToPlaylist(5L, listOf(1L)) } returns AddToPlaylistResult.SMART_PLAYLIST
        val messages = collectMessages()

        viewModel.addToExistingPlaylist(LibraryItem.TrackItem(track), 5L, "Favs")

        assertThat(messages.single()).contains("auto rule")
    }

    @Test
    fun `a playlist that vanished before the add is reported as gone, not as an auto rule`() {
        coEvery { playlistRepository.addToPlaylist(5L, listOf(1L)) } returns AddToPlaylistResult.MISSING
        val messages = collectMessages()

        viewModel.addToExistingPlaylist(LibraryItem.TrackItem(track), 5L, "Favs")

        assertThat(messages.single()).isEqualTo("Can't add tracks to \"Favs\": it no longer exists")
    }

    @Test
    fun `confirmDelete prunes only the actually deleted tracks from the queue`() {
        val locked = track.copy(id = 2, filePath = "/locked.mp3")
        coEvery { libraryRepository.deleteFromDevice(listOf(1L, 2L)) } returns
            LibraryRepository.DeleteResult(deletedIds = listOf(1L), failed = listOf(locked))

        viewModel.confirmDelete(LibraryItem.TracksItem("2 tracks", listOf(track, locked)))

        verify { player.removeTracksFromQueue(listOf(1L)) }
        assertThat(viewModel.dialog.value).isNull()
    }

    @Test
    fun `confirmDelete on a rowless track reports it instead of claiming success`() {
        // A track whose row is already gone (or a network stream): nothing is
        // deleted and nothing failed, and the toast must not say "Deleted".
        coEvery { libraryRepository.deleteFromDevice(listOf(1L)) } returns
            LibraryRepository.DeleteResult(deletedIds = emptyList(), failed = emptyList())
        val messages = collectMessages()

        viewModel.confirmDelete(LibraryItem.TrackItem(track))

        verify { player.removeTracksFromQueue(emptyList()) }
        assertThat(messages.single()).contains("Nothing deleted")
    }

    @Test
    fun `requestRate shows the live rating, not the enqueue-time snapshot`() {
        coEvery { libraryRepository.getTracksByIds(listOf(1L)) } returns
            listOf(track.copy(rating = 5))

        viewModel.requestRate(track.copy(rating = 1))

        val dialog = viewModel.dialog.value as LibraryActionsViewModel.Dialog.Rate
        assertThat(dialog.track.rating).isEqualTo(5)
    }

    @Test
    fun `auto playlist seed from a queue snapshot uses the live artist tag`() {
        // The queue snapshot substitutes the album artist for a blank artist
        // tag; the seed must come from the real row or the rule misses the
        // track it was made from.
        coEvery { libraryRepository.getTracksByIds(listOf(1L)) } returns
            listOf(track.copy(artist = ""))

        viewModel.requestCreateAutoPlaylist(
            LibraryItem.TrackItem(track.copy(artist = "Album Artist Fallback")),
        )

        val dialog = viewModel.dialog.value as LibraryActionsViewModel.Dialog.CreateAutoPlaylist
        assertThat(dialog.initialRule)
            .isEqualTo(SmartRule.Condition(RuleField.ARTIST, RuleOp.IS, ""))
    }

    @Test
    fun `auto playlist seed for an album matches artist AND album`() {
        viewModel.requestCreateAutoPlaylist(LibraryItem.AlbumItem(album("LP", "AA")))
        val dialog = viewModel.dialog.value as LibraryActionsViewModel.Dialog.CreateAutoPlaylist
        assertThat(dialog.initialRule).isEqualTo(
            SmartRule.AllOf(
                listOf(
                    SmartRule.Condition(RuleField.ALBUM_ARTIST, RuleOp.IS, "AA"),
                    SmartRule.Condition(RuleField.ALBUM, RuleOp.IS, "LP"),
                ),
            ),
        )
    }

    @Test
    fun `a playlist file that survives deletion is reported`() {
        coEvery { playlistRepository.deletePlaylist(7L) } returns false
        val messages = collectMessages()

        viewModel.confirmDelete(LibraryItem.PlaylistItem(Playlist(id = 7, name = "Mix")))

        assertThat(messages.single()).contains("could not be deleted")
    }

    @Test
    fun `createAutoPlaylist delegates to the repository`() {
        val rule = SmartRule.Condition(RuleField.RATING, RuleOp.GREATER_THAN, "3")
        coEvery { playlistRepository.createSmartPlaylist(any(), any()) } returns
            Playlist(id = 9, name = "Favs", smartRule = rule)

        viewModel.createAutoPlaylist("Favs", rule)
        coVerify { playlistRepository.createSmartPlaylist("Favs", rule) }
    }
}
