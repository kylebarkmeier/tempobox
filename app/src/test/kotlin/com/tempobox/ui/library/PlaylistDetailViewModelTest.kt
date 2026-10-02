package com.tempobox.ui.library

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.tempobox.library.PlaylistRepository
import com.tempobox.model.Playlist
import com.tempobox.model.SortKey
import com.tempobox.model.SortSpec
import com.tempobox.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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

/**
 * Pins the playlist-detail sorting contract: the chosen sort only changes the
 * displayed list, and the stored playlist order is never written back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModelTest {

    private val playlistRepository: PlaylistRepository = mockk(relaxed = true)
    private val sortState = SubviewSortState()
    private val collectors = mutableListOf<Job>()

    private val playlist = Playlist(id = 1, name = "Mix")
    private val stored = listOf(
        Track(id = 3, filePath = "/c.mp3", title = "Charlie"),
        Track(id = 1, filePath = "/a.mp3", title = "Alpha"),
        Track(id = 2, filePath = "/b.mp3", title = "Bravo"),
    )

    private lateinit var viewModel: PlaylistDetailViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { playlistRepository.getPlaylist(1) } returns playlist
        every { playlistRepository.observePlaylistTracks(playlist) } returns flowOf(stored)
        viewModel = PlaylistDetailViewModel(
            SavedStateHandle(mapOf("id" to 1L)),
            playlistRepository,
            sortState,
        )
        // Keep the WhileSubscribed flows hot, as the screen would.
        collectors += CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.tracks.collect {}
        }
    }

    @After
    fun tearDown() {
        collectors.forEach(Job::cancel)
        Dispatchers.resetMain()
    }

    @Test
    fun `default sort shows the stored playlist order`() {
        assertThat(viewModel.tracks.value.map { it.id }).containsExactly(3L, 1L, 2L).inOrder()
    }

    @Test
    fun `a view sort reorders the display but never writes the playlist`() {
        viewModel.setTracksSort(SortSpec(SortKey.ALPHABETICAL, ascending = true))

        assertThat(viewModel.tracks.value.map { it.title })
            .containsExactly("Alpha", "Bravo", "Charlie").inOrder()
        coVerify(exactly = 0) { playlistRepository.replacePlaylistTracks(any(), any()) }
    }

    @Test
    fun `removeEntry under a view sort removes the right stored entry`() {
        viewModel.setTracksSort(SortSpec(SortKey.ALPHABETICAL, ascending = true))

        // Displayed row 0 is "Alpha" (id 1); stored order must lose id 1 only.
        viewModel.removeEntry(0)

        coVerify { playlistRepository.replacePlaylistTracks(1, listOf(3L, 2L)) }
    }

    @Test
    fun `removeEntry in stored order removes by position`() {
        viewModel.removeEntry(1)
        coVerify { playlistRepository.replacePlaylistTracks(1, listOf(3L, 2L)) }
    }
}
