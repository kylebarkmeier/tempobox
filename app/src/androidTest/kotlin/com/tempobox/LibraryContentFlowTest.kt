package com.tempobox

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * Library browsing end to end against the real Hilt graph: seeded tracks show
 * up in every tab's aggregation, and tapping through opens the matching
 * artist / album / genre detail screens.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LibraryContentFlowTest {

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

    @Test
    fun tracksTab_listsSeededTracks() {
        openTab("Tracks")
        composeRule.waitForText("India One")
        composeRule.onNodeWithText("India Two").assertIsDisplayed()
        composeRule.onNodeWithText("Juliet Song").assertIsDisplayed()
    }

    @Test
    fun albumArtistsTab_opensArtistDetail_withAlbumsAndAllTracks() {
        composeRule.waitForText("India Artist") // default tab is Album Artists
        composeRule.onNodeWithText("India Artist").performClick()

        // Artist detail: Albums tab first…
        composeRule.waitForText("Albums")
        composeRule.onNodeWithText("All tracks").assertIsDisplayed()
        composeRule.waitForText("India Album")

        // …then the full track list.
        composeRule.onNodeWithText("All tracks").performClick()
        composeRule.waitForText("India One")
        composeRule.onNodeWithText("India Two").assertIsDisplayed()
    }

    @Test
    fun albumsTab_opensAlbumDetail_withItsTracksOnly() {
        openTab("Albums")
        composeRule.waitForText("India Album")
        composeRule.onNodeWithText("Juliet Album").assertIsDisplayed()

        composeRule.onNodeWithText("India Album").performClick()
        composeRule.waitForText("India One")
        composeRule.onNodeWithText("India Two").assertIsDisplayed()
        // The other artist's track does not leak into this album.
        composeRule.waitForTextGone("Juliet Song")
    }

    @Test
    fun genresTab_aggregatesAndOpensGenreDetail() {
        openTab("Genres")
        composeRule.waitForText("Rock")
        composeRule.onNodeWithText("1 albums · 2 tracks").assertIsDisplayed()
        composeRule.onNodeWithText("Jazz").assertIsDisplayed()

        composeRule.onNodeWithText("Rock").performClick()
        // Genre detail is sub-browsable by Artists / Albums / Tracks.
        composeRule.waitForText("Artists")
        composeRule.onNodeWithText("India Artist").assertIsDisplayed()
        composeRule.onNodeWithText("Tracks").performClick()
        composeRule.waitForText("India One")
        composeRule.waitForTextGone("Juliet Song") // jazz stays out of Rock
    }

    @Test
    fun genresTab_layoutToggle_showsTilesThatOpenGenreDetail() {
        openTab("Genres")
        composeRule.waitForText("Rock")

        // Genres default to the list; the toolbar toggle switches to tiles.
        composeRule.onNodeWithContentDescription("Switch to cards").performClick()
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) {
            composeRule.onAllNodesWithContentDescription("Switch to list")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Tiles show the same aggregates and open the same detail screen.
        composeRule.waitForText("Rock")
        composeRule.onNodeWithText("1 albums · 2 tracks").assertIsDisplayed()
        composeRule.onNodeWithText("Jazz").assertIsDisplayed()
        composeRule.onNodeWithText("Rock").performClick()
        composeRule.waitForText("Artists")
        composeRule.onNodeWithText("India Artist").assertIsDisplayed()
    }

    @Test
    fun artistsTab_groupsByTrackArtist() {
        openTab("Artists")
        composeRule.waitForText("Juliet Artist")
        composeRule.onNodeWithText("India Artist").assertIsDisplayed()
    }
}
