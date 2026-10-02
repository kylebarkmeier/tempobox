package com.tempobox

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.tempobox.database.TempoBoxDatabase
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
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
        Espresso.closeSoftKeyboard() // keep the IME off the Create button
        composeRule.waitForIdle()
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
        assertTrue("expected .m3u8, got ${row.filePath}", row.filePath!!.endsWith(".m3u8"))
        assertTrue("playlist file missing on disk", java.io.File(row.filePath!!).exists())
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
        assertTrue(
            "No playlist row may be created from a blank name",
            runBlocking { db.playlistDao().getAll() }.isEmpty(),
        )
    }

    @Test
    fun autoPlaylist_isCreatedFromARule_andEvaluatesLive() {
        openPlaylistsTab()
        composeRule.onNodeWithText("New auto playlist").performClick()
        composeRule.waitForText("Auto playlist")

        // Name + one GENRE IS <value> condition (the builder's default field/op).
        // Each input is verified to have landed before moving on: on a slow
        // emulator the second focus/IME handshake can lag behind the tap, and
        // Create is (correctly) disabled while either field is blank.
        val nameField = hasSetTextAction() and hasText("Name") and hasAnyAncestor(isDialog())
        val valueField = hasSetTextAction() and hasText("value") and hasAnyAncestor(isDialog())
        composeRule.onNode(nameField).performTextInput("Rock Auto")
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) {
            composeRule.onAllNodes(hasText("Rock Auto") and hasAnyAncestor(isDialog()))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(valueField).performTextInput("Rock")
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) {
            composeRule.onAllNodes(hasText("Rock") and hasAnyAncestor(isDialog()))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Drop the soft keyboard so the tap on Create cannot land on the IME.
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
        // Regression guard for the stale-enablement bug fixed alongside this
        // test (SmartRuleBuilderDialog): Create must become enabled once both
        // fields hold text. Bounded wait instead of a single-frame assertion —
        // the derived state may trail the text commit by a frame.
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) {
            composeRule.onAllNodes(hasText("Create") and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Create").performClick()

        // Creation is observable in the repository layer first (deterministic,
        // independent of snackbars and list animation)…
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) {
            runBlocking { db.playlistDao().getByName("Rock Auto")?.smartRuleJson != null }
        }
        composeRule.waitForTextGone("Auto playlist") // builder dialog closed

        // …then the row appears flagged as an auto playlist with live stats.
        composeRule.waitForText("Rock Auto", substring = true)
        composeRule.waitForText("· auto", substring = true)
        composeRule.waitForText("1 tracks", substring = true) // only the Rock track matches

        // Open the detail via the row's exact title ("Rock Auto  ✨") — a
        // substring match could also hit the "Created auto playlist" snackbar.
        composeRule.onNodeWithText("Rock Auto  ✨").performClick()
        composeRule.waitForText("Lima Song")
        composeRule.waitForText("Auto playlist — updates automatically as your library changes.")
        composeRule.waitForTextGone("Mike Song") // Jazz is filtered out
    }
}
