package com.tempobox.ui.library

import com.google.common.truth.Truth.assertThat
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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

    private lateinit var viewModel: LibraryActionsViewModel

    private val track = Track(id = 1, filePath = "/a.mp3", title = "Song", artist = "Artist")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = LibraryActionsViewModel(libraryRepository, playlistRepository, player)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `play on a track overwrites the queue with that track`() {
        viewModel.play(LibraryItem.TrackItem(track))
        verify { player.playTracks(listOf(track), 0) }
    }

    @Test
    fun `play on a genre resolves its tracks first`() {
        val genreTracks = listOf(track, track.copy(id = 2, filePath = "/b.mp3"))
        coEvery { libraryRepository.observeGenreTracks("Rock") } returns flowOf(genreTracks)

        viewModel.play(LibraryItem.GenreItem(Genre("Rock", trackCount = 2, albumCount = 1)))
        verify { player.playTracks(genreTracks, 0) }
    }

    @Test
    fun `shuffle delegates to playShuffled`() {
        viewModel.shuffle(LibraryItem.TrackItem(track))
        verify { player.playShuffled(listOf(track)) }
    }

    @Test
    fun `swipe dispatch covers direct actions and dialog openers`() {
        val item = LibraryItem.TrackItem(track)

        viewModel.performSwipe(SwipeAction.ADD_TO_QUEUE, item)
        verify { player.addToQueue(listOf(track)) }

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
    fun `createAutoPlaylist delegates to the repository`() {
        val rule = SmartRule.Condition(RuleField.RATING, RuleOp.GREATER_THAN, "3")
        coEvery { playlistRepository.createSmartPlaylist(any(), any()) } returns
            Playlist(id = 9, name = "Favs", smartRule = rule)

        viewModel.createAutoPlaylist("Favs", rule)
        coVerify { playlistRepository.createSmartPlaylist("Favs", rule) }
    }
}
