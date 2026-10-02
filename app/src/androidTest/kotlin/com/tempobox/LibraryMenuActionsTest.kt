package com.tempobox

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.io.File
import javax.inject.Inject

/**
 * The standard 3-dot menu end to end: entry set (including the Go to
 * artist/album entries), navigation targets, rating, and the confirmed
 * remove / delete flows — all against the real repositories and database.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LibraryMenuActionsTest {

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

    private lateinit var hotelFile: File

    @Before
    fun seedLibrary() {
        hiltRule.inject()
        val filesDir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        hotelFile = File(filesDir, "menu-test/hotel.mp3").apply {
            parentFile?.mkdirs()
            writeText("placeholder audio")
        }
        runBlocking {
            db.trackDao().deleteAll()
            db.trackDao().upsertKeepingUserData(
                listOf(
                    TestLibrary.track(
                        "/seed/golf.mp3", title = "Golf Song", artist = "Golf Artist",
                        album = "Golf Album", genre = "Rock",
                    ),
                    TestLibrary.track(
                        hotelFile.absolutePath, title = "Hotel Song", artist = "Hotel Artist",
                        album = "Hotel Album", genre = "Jazz",
                    ),
                ),
            )
        }
    }

    @After
    fun clearLibrary() {
        runBlocking { db.trackDao().deleteAll() }
        hotelFile.parentFile?.deleteRecursively()
    }

    private fun openTrackMenu(title: String) {
        composeRule.onNodeWithText("Tracks").performScrollTo().performClick()
        composeRule.waitForText(title)
        composeRule.onNodeWithContentDescription("More options for $title").performClick()
    }

    @Test
    fun trackMenu_showsTheStandardEntries() {
        openTrackMenu("Golf Song")
        // assertExists: on short screens the dropdown scrolls, so later entries
        // are in the tree but may start below the fold.
        composeRule.onNodeWithText("Add to queue").assertExists()
        composeRule.onNodeWithText("Play next").assertExists()
        composeRule.onNodeWithText("Go to artist").assertExists()
        composeRule.onNodeWithText("Go to album").assertExists()
        composeRule.onNodeWithText("Add to playlist").assertExists()
        composeRule.onNodeWithText("Create auto playlist").assertExists()
        composeRule.onNodeWithText("Edit ID3 tags").assertExists()
        composeRule.onNodeWithText("Rate").assertExists()
        composeRule.onNodeWithText("Remove from library").assertExists()
        composeRule.onNodeWithText("Delete permanently").assertExists()
        // Shuffle is a collection action — not offered on single tracks.
        composeRule.waitForTextGone("Shuffle")
    }

    @Test
    fun goToAlbum_opensTheTracksAlbumDetail() {
        openTrackMenu("Golf Song")
        composeRule.onNodeWithText("Go to album").performClick()

        composeRule.waitForText("Golf Album")
        composeRule.waitForText("Golf Song")
        // It really is the album detail scaffold, not the library tab.
        composeRule.onNodeWithContentDescription("Play Golf Album").assertIsDisplayed()
    }

    @Test
    fun goToArtist_opensTheTrackArtistDetail() {
        openTrackMenu("Golf Song")
        composeRule.onNodeWithText("Go to artist").performClick()

        composeRule.waitForText("Golf Artist")
        composeRule.waitForText("All tracks") // artist detail's second tab
        composeRule.onNodeWithText("All tracks").performClick()
        composeRule.waitForText("Golf Song")
    }

    @Test
    fun rate_persistsTheRatingToTheLibrary() {
        openTrackMenu("Golf Song")
        composeRule.onNodeWithText("Rate").performClick()

        composeRule.waitForText("Rate \"Golf Song\"")
        composeRule.onNodeWithContentDescription("4 stars").performClick()

        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) {
            runBlocking { db.trackDao().getByPath("/seed/golf.mp3")?.rating == 4 }
        }
    }

    @Test
    fun removeFromLibrary_requiresConfirmation_andDropsOnlyTheRow() {
        openTrackMenu("Hotel Song")
        composeRule.onNodeWithText("Remove from library").performClick()

        composeRule.waitForText("Remove from library?")
        composeRule.onNodeWithText("Remove").performClick()

        composeRule.waitForTextGone("Hotel Song")
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) {
            runBlocking { db.trackDao().getByPath(hotelFile.absolutePath) == null }
        }
        assertTrue("Remove from library must not touch the file", hotelFile.exists())
    }

    @Test
    fun deletePermanently_requiresConfirmation_andDeletesTheFile() {
        openTrackMenu("Hotel Song")
        composeRule.onNodeWithText("Delete permanently").performClick()

        composeRule.waitForText("Delete permanently?")
        composeRule.onNodeWithText("Delete").performClick()

        composeRule.waitForTextGone("Hotel Song")
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) { !hotelFile.exists() }
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) {
            runBlocking { db.trackDao().getByPath(hotelFile.absolutePath) == null }
        }
    }

    @Test
    fun cancellingAConfirmation_changesNothing() {
        openTrackMenu("Hotel Song")
        composeRule.onNodeWithText("Delete permanently").performClick()
        composeRule.waitForText("Delete permanently?")
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.waitForTextGone("Delete permanently?")
        composeRule.onNodeWithText("Hotel Song").assertIsDisplayed()
        assertTrue("Cancelled delete must leave the file alone", hotelFile.exists())
    }
}
