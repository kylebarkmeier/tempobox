package com.tempobox

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.tempobox.database.TempoBoxDatabase
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Playlist journeys end to end: creating a static playlist, adding a track
 * from the 3-dot menu, the m3u8-only export invariant, and creating a smart
 * ("auto") playlist whose membership follows the library.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PlaylistFlowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) {
        GrantPermissionRule.grant(
            android.Manifest.permission.READ_MEDIA_AUDIO,
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        GrantPermissionRule.grant(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: TempoBoxDatabase

    @Before
    fun seedLibrary() {
        hiltRule.inject()
        runBlocking {
            clearPlaylists()
            db.trackDao().deleteAll()
            db.trackDao().upsertKeepingUserData(
                listOf(
                    TestLibrary.track("/seed/lima.mp3", title = "Lima Song", genre = "Rock"),
                    TestLibrary.track("/seed/mike.mp3", title = "Mike Song", genre = "Jazz"),
                ),
            )
        }
    }

    @After
    fun clearState() {
        runBlocking {
            clearPlaylists()
            db.trackDao().deleteAll()
        }
    }

    private suspend fun clearPlaylists() {
        db.playlistDao().getAll().forEach { playlist ->
            playlist.filePath?.let { path ->
                java.io.File(path).takeIf { it.exists() }?.delete()
            }
            db.playlistDao().delete(playlist.id)
        }
    }

    private fun openPlaylistsTab() {
        composeRule.onNodeWithText("Playlists").performScrollTo().performClick()
        composeRule.waitForText("New playlist")
    }

    private fun createStaticPlaylist(name: String) {
        openPlaylistsTab()
        composeRule.onNodeWithText("New playlist").performClick()
        composeRule.waitForText("Playlist name") // the input's placeholder
        composeRule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
            .performTextInput(name)
        composeRule.onNodeWithText("Create").performClick()
        composeRule.waitForText(name)
    }

    @Test
    fun createPlaylist_showsAnEmptyPlaylistRow_backedByAnM3u8File() {
        createStaticPlaylist("Road Mix")

        composeRule.onNodeWithText("Road Mix").assertIsDisplayed()
        composeRule.waitForText("0 tracks", substring = true)

        // Spec: everything the app creates exists on disk as UTF-8 M3U8.
        val row = runBlocking { db.playlistDao().getByName("Road Mix")!! }
        assert(row.filePath!!.endsWith(".m3u8")) { "expected .m3u8, got ${row.filePath}" }
        assert(java.io.File(row.filePath!!).exists()) { "playlist file missing on disk" }
    }

    @Test
    fun addTrackToPlaylist_fromTheMenu_updatesCountAndDetail() {
        createStaticPlaylist("Road Mix")

        // Add a track via the standard 3-dot menu.
        composeRule.onNodeWithText("Tracks").performScrollTo().performClick()
        composeRule.waitForText("Lima Song")
        composeRule.onNodeWithContentDescription("More options for Lima Song").performClick()
        composeRule.waitForText("Add to playlist")
        composeRule.onNodeWithText("Add to playlist").performClick()

        composeRule.waitForText("Road Mix") // picker lists the playlist
        composeRule.onNodeWithText("Road Mix").performClick()

        // Membership is persisted…
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) {
            runBlocking {
                val playlist = db.playlistDao().getByName("Road Mix")!!
                db.playlistDao().getPlaylistTracks(playlist.id).size == 1
            }
        }

        // …and visible in the playlist detail.
        openPlaylistsTab()
        composeRule.waitForText("1 tracks", substring = true)
        composeRule.onNodeWithText("Road Mix").performClick()
        composeRule.waitForText("Lima Song")
    }

    @Test
    fun newPlaylistDialog_requiresAName() {
        openPlaylistsTab()
        composeRule.onNodeWithText("New playlist").performClick()
        composeRule.waitForText("Playlist name")

        // Create stays disabled on a blank name — clicking must not create anything.
        composeRule.onNodeWithText("Create").performClick()
        composeRule.onNodeWithText("Playlist name").assertIsDisplayed() // dialog still open
        composeRule.onNodeWithText("Cancel").performClick()
        assert(runBlocking { db.playlistDao().getAll() }.isEmpty())
    }

    @Test
    fun autoPlaylist_isCreatedFromARule_andEvaluatesLive() {
        openPlaylistsTab()
        composeRule.onNodeWithText("New auto playlist").performClick()
        composeRule.waitForText("Auto playlist")

        // Name + one GENRE IS <value> condition (the builder's default field/op).
        composeRule.onNode(hasSetTextAction() and hasText("Name") and hasAnyAncestor(isDialog()))
            .performTextInput("Rock Auto")
        composeRule.onNode(hasSetTextAction() and hasText("value") and hasAnyAncestor(isDialog()))
            .performTextInput("Rock")
        composeRule.onNodeWithText("Create").performClick()

        // The row appears flagged as an auto playlist with live stats.
        composeRule.waitForText("Rock Auto", substring = true)
        composeRule.waitForText("· auto", substring = true)
        composeRule.waitForText("1 tracks", substring = true) // only the Rock track matches

        // Its detail evaluates against the current library.
        composeRule.onNodeWithText("Rock Auto", substring = true).performClick()
        composeRule.waitForText("Lima Song")
        composeRule.waitForText("Auto playlist — updates automatically as your library changes.")
        composeRule.waitForTextGone("Mike Song") // Jazz is filtered out
    }
}
