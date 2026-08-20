package com.tempobox.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.tempobox.model.Corner
import com.tempobox.model.CornerAction
import com.tempobox.model.DrawerItem
import com.tempobox.model.LibraryTab
import com.tempobox.model.SwipeAction
import com.tempobox.model.ThemeConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DataStoreSettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private lateinit var file: File
    private lateinit var repository: DataStoreSettingsRepository

    @Before
    fun setUp() {
        file = File(tmp.root, "settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        repository = DataStoreSettingsRepository(dataStore)
    }

    @Test
    fun `fresh store yields product defaults`() = scope.runTest {
        val settings = repository.settings.first()
        assertThat(settings.library.autoRescanAndWatch).isTrue() // spec: default ON
        assertThat(settings.ui.swipeLeft).isEqualTo(SwipeAction.ADD_TO_QUEUE)
        assertThat(settings.ui.drawerItems).isEqualTo(UiSettings.DEFAULT_DRAWER_ITEMS)
        assertThat(settings.shuffle.antiRepeat).isTrue() // spec: default ON
        assertThat(settings.shuffle.ratingBias).isFalse()
        assertThat(settings.queue.persistQueue).isTrue()
        assertThat(settings.lastFm.scrobbleEnabled).isFalse()
    }

    @Test
    fun `updates persist and other groups stay untouched`() = scope.runTest {
        repository.updateLibrary { it.copy(locations = listOf("/storage/emulated/0/Music")) }
        repository.updateShuffle { it.copy(ratingBias = true) }

        val settings = repository.settings.first()
        assertThat(settings.library.locations).containsExactly("/storage/emulated/0/Music")
        assertThat(settings.shuffle.ratingBias).isTrue()
        assertThat(settings.ui.swipeRight).isEqualTo(SwipeAction.ADD_TO_PLAYLIST) // untouched default
    }

    @Test
    fun `complex values survive the JSON round trip`() = scope.runTest {
        repository.updateUi {
            it.copy(
                drawerItems = it.drawerItems + DrawerItem.LibraryView(LibraryTab.GENRES, "My genres"),
            )
        }
        repository.updateNowPlaying {
            it.copy(cornerActions = it.cornerActions + (Corner.TOP_LEFT to CornerAction.SET_AS_WALLPAPER))
        }
        repository.updateTheme { it.copy(darkMode = ThemeConfig.DarkMode.DARK, primaryArgb = 0xFF123456) }

        val settings = repository.settings.first()
        assertThat(settings.ui.drawerItems.last())
            .isEqualTo(DrawerItem.LibraryView(LibraryTab.GENRES, "My genres"))
        assertThat(settings.nowPlaying.cornerActions[Corner.TOP_LEFT])
            .isEqualTo(CornerAction.SET_AS_WALLPAPER)
        assertThat(settings.theme.primaryArgb).isEqualTo(0xFF123456)
    }

    @Test
    fun `sequential transforms compose`() = scope.runTest {
        repository.updateLibrary { it.copy(locations = it.locations + "/a") }
        repository.updateLibrary { it.copy(locations = it.locations + "/b") }
        assertThat(repository.settings.first().library.locations).containsExactly("/a", "/b").inOrder()
    }
}
