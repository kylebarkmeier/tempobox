package com.tempobox

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
 * List search end to end: the toolbar search icon swaps the title for a text
 * field, typing filters the visible list live, clearing restores it, and
 * closing the field brings the normal top bar back. One flow on a main
 * library tab and one on a detail screen; matching itself is pinned by the
 * JVM tests in core:model (SearchingTest).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SearchFlowTest {

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
            db.trackDao().deleteAll()
            db.trackDao().upsertKeepingUserData(
                listOf(
                    TestLibrary.track(
                        "/seed/india1.mp3", title = "India One", artist = "India Artist",
                        album = "India Album", genre = "Rock", trackNumber = 1,
                    ),
                    TestLibrary.track(
                        "/seed/india2.mp3", title = "India Two", artist = "India Artist",
                        album = "India Album", genre = "Rock", trackNumber = 2,
                    ),
                    TestLibrary.track(
                        "/seed/juliet1.mp3", title = "Juliet Song", artist = "Juliet Artist",
                        album = "Juliet Album", genre = "Jazz",
                    ),
                    TestLibrary.track(
                        "/seed/india3.mp3", title = "Aardvark Closer", artist = "India Artist",
                        album = "India Album", genre = "Jazz", trackNumber = 3,
                    ),
                ),
            )
        }
    }

    @After
    fun clearLibrary() {
        runBlocking { db.trackDao().deleteAll() }
    }

    private fun openTab(label: String) {
        composeRule.onNodeWithText(label).performScrollTo().performClick()
    }

    private fun waitForSearchIcon() {
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) {
            composeRule.onAllNodesWithContentDescription("Search")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun tracksTab_searchFiltersLive_clearAndCloseRestore() {
        openTab("Tracks")
        composeRule.waitForText("India One")

        // Enter search and type: only matching rows stay (query matches the
        // Juliet track's title, artist, and album; no India field contains it).
        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("juliet")
        composeRule.waitForTextGone("India One")
        composeRule.onNodeWithText("Juliet Song").assertIsDisplayed()

        // Clearing the text restores the full list, field still open.
        composeRule.onNodeWithContentDescription("Clear search").performClick()
        composeRule.waitForText("India One")
        composeRule.onNodeWithText("Juliet Song").assertIsDisplayed()

        // Closing the empty field restores the normal top bar.
        composeRule.onNodeWithContentDescription("Close search").performClick()
        waitForSearchIcon()
        composeRule.onNodeWithText("India Two").assertIsDisplayed()
    }

    @Test
    fun tracksTab_searchIsCaseAndDiacriticInsensitiveOnDevice() {
        openTab("Tracks")
        composeRule.waitForText("India One")

        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("JÚLIET")
        composeRule.waitForTextGone("India One")
        composeRule.onNodeWithText("Juliet Song").assertIsDisplayed()
    }

    @Test
    fun albumDetail_searchFiltersTracks_andDismissRestores() {
        openTab("Albums")
        composeRule.waitForText("India Album")
        composeRule.onNodeWithText("India Album").performClick()
        composeRule.waitForText("India One")

        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("aardvark")
        composeRule.waitForTextGone("India One")
        composeRule.onNodeWithText("Aardvark Closer").assertIsDisplayed()

        // Clear, then close: the full album track list comes back.
        composeRule.onNodeWithContentDescription("Clear search").performClick()
        composeRule.waitForText("India One")
        composeRule.onNodeWithContentDescription("Close search").performClick()
        waitForSearchIcon()
        composeRule.onNodeWithText("India Two").assertIsDisplayed()
    }

    @Test
    fun genreDetail_searchAppliesToTheActiveSubTab() {
        openTab("Genres")
        composeRule.waitForText("Jazz")
        composeRule.onNodeWithText("Jazz").performClick()

        // Jazz holds two tracks by different artists; search the Tracks tab.
        composeRule.waitForText("Tracks")
        composeRule.onNodeWithText("Tracks").performClick()
        composeRule.waitForText("Juliet Song")

        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("aardvark")
        composeRule.waitForTextGone("Juliet Song")
        composeRule.onNodeWithText("Aardvark Closer").assertIsDisplayed()
    }
}
