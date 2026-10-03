package com.tempobox.ui.queue

import com.google.common.truth.Truth.assertThat
import com.tempobox.model.NowPlayingState
import com.tempobox.model.QueueItem
import com.tempobox.model.Track
import com.tempobox.playback.PlayerConnection
import com.tempobox.settings.AppSettings
import com.tempobox.settings.SettingsRepository
import com.tempobox.ui.library.LibraryItem
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueViewModelTest {

    private val queueFlow = MutableStateFlow<List<QueueItem>>(emptyList())
    private val stateFlow = MutableStateFlow(NowPlayingState())
    private val player: PlayerConnection = mockk(relaxed = true) {
        every { queue } returns queueFlow
        every { state } returns stateFlow
    }
    private val settingsRepository: SettingsRepository = mockk {
        every { settings } returns MutableStateFlow(AppSettings())
    }

    private lateinit var viewModel: QueueViewModel

    private fun item(uid: Long, title: String = "T$uid") =
        QueueItem(uid, Track(id = uid, filePath = "/t$uid.mp3", title = title))

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = QueueViewModel(player, settingsRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `multi-select starts empty and toggles off`() {
        assertThat(viewModel.selection.value).isNull()
        viewModel.toggleMultiSelect()
        assertThat(viewModel.selection.value).isEmpty()
        viewModel.toggleMultiSelect()
        assertThat(viewModel.selection.value).isNull()
    }

    @Test
    fun `long-press starts multi-select with that item selected`() {
        viewModel.startMultiSelect(uid = 5)
        assertThat(viewModel.selection.value).containsExactly(5L)
    }

    @Test
    fun `toggleSelected adds and removes`() {
        viewModel.toggleMultiSelect()
        viewModel.toggleSelected(1)
        viewModel.toggleSelected(2)
        viewModel.toggleSelected(1)
        assertThat(viewModel.selection.value).containsExactly(2L)
    }

    @Test
    fun `removeSelected delegates to the player and clears the selection`() {
        viewModel.startMultiSelect(3)
        viewModel.toggleSelected(4)
        viewModel.removeSelected()
        verify { player.removeQueueItems(match { it.toSet() == setOf(3L, 4L) }) }
        assertThat(viewModel.selection.value).isEmpty()
    }

    @Test
    fun `selectedItem wraps selected tracks for the shared action layer`() {
        queueFlow.value = listOf(item(1, "One"), item(2, "Two"), item(3, "Three"))
        viewModel.startMultiSelect(1)
        viewModel.toggleSelected(3)

        val selected = viewModel.selectedItem() as LibraryItem.TracksItem
        assertThat(selected.tracks.map { it.title }).containsExactly("One", "Three")
        assertThat(selected.title).isEqualTo("2 tracks")
    }

    @Test
    fun `single selection uses the track title as label`() {
        queueFlow.value = listOf(item(1, "Solo"))
        viewModel.startMultiSelect(1)
        val selected = viewModel.selectedItem() as LibraryItem.TracksItem
        assertThat(selected.title).isEqualTo("Solo")
    }

    @Test
    fun `clearQueue delegates and exits multi-select`() {
        viewModel.startMultiSelect(1)
        viewModel.clearQueue()
        verify { player.clearQueue() }
        assertThat(viewModel.selection.value).isNull()
    }

    @Test
    fun `swipe-remove delegates a single uid`() {
        viewModel.remove(9)
        verify { player.removeQueueItems(listOf(9L)) }
    }

    @Test
    fun `search narrows the visible queue but never the real queue`() {
        queueFlow.value = listOf(item(1, "Alpha"), item(2, "Beta"), item(3, "Alphabet"))

        viewModel.setSearchQuery("alpha")
        assertThat(viewModel.visibleQueue.value.map { it.track.title })
            .containsExactly("Alpha", "Alphabet").inOrder()
        assertThat(viewModel.queue.value).hasSize(3)
        verify(exactly = 0) { player.removeQueueItems(any()) }

        viewModel.setSearchQuery("")
        assertThat(viewModel.visibleQueue.value).hasSize(3)
    }
}
