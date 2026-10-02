package com.tempobox

import android.os.Build
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Tag editor dialog end to end: single-track prefill, the bulk editor's
 * "Multiple values" presentation for mixed fields, and the dirty-field
 * contract (saving untouched fields writes nothing).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TagEditorFlowTest {

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
                    // Same album & genre, different titles & years → the bulk
                    // editor must prefill the shared fields and mark the rest mixed.
                    TestLibrary.track(
                        "/seed/tag1.mp3", title = "Tag One", artist = "Tag Artist",
                        album = "Shared Album", genre = "Shoegaze", year = 1999, trackNumber = 1,
                    ),
                    TestLibrary.track(
                        "/seed/tag2.mp3", title = "Tag Two", artist = "Tag Artist",
                        album = "Shared Album", genre = "Shoegaze", year = 2001, trackNumber = 2,
                    ),
                ),
            )
        }
    }

    @After
    fun clearLibrary() {
        runBlocking { db.trackDao().deleteAll() }
    }

    /** A node carrying [text] inside the currently open dialog. */
    private fun inDialog(text: String): SemanticsNodeInteraction =
        composeRule.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun openSingleTrackEditor(title: String) {
        composeRule.onNodeWithText("Tracks").performScrollTo().performClick()
        composeRule.waitForText(title)
        composeRule.onNodeWithContentDescription("More options for $title").performClick()
        composeRule.waitForText("Edit ID3 tags")
        composeRule.onNodeWithText("Edit ID3 tags").performClick()
    }

    @Test
    fun singleTrackEditor_prefillsEveryFieldWithTheCurrentTags() {
        openSingleTrackEditor("Tag One")

        composeRule.waitForText("Edit tags — Tag One")
        inDialog("Tag One").assertExists() // Title field value
        inDialog("Shared Album").assertExists()
        inDialog("Shoegaze").assertExists()
        inDialog("1999").assertExists() // Year
        // Single-track mode offers the per-track numeric fields too.
        inDialog("Track #").assertIsDisplayed()
        inDialog("Disc #").assertIsDisplayed()

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForTextGone("Edit tags — Tag One")
    }

    @Test
    fun savingWithoutChanges_writesNothingAndCloses() {
        openSingleTrackEditor("Tag One")
        composeRule.waitForText("Edit tags — Tag One")

        composeRule.onNodeWithText("Save").performClick()

        // Dirty-field tracking: untouched fields derive an empty write set, so
        // the dialog closes without touching any file or row.
        composeRule.waitForTextGone("Edit tags — Tag One")
        val row = runBlocking { db.trackDao().getByPath("/seed/tag1.mp3")!! }
        assertEquals("Tag One", row.title)
        assertEquals("Shoegaze", row.genre)
        assertEquals(1999, row.year)
    }

    @Test
    fun bulkEditor_prefillsSharedFields_andMarksMixedOnes() {
        composeRule.onNodeWithText("Albums").performScrollTo().performClick()
        composeRule.waitForText("Shared Album")
        composeRule.onNodeWithContentDescription("More options for Shared Album").performClick()
        composeRule.waitForText("Edit ID3 tags")
        composeRule.onNodeWithText("Edit ID3 tags").performClick()

        composeRule.waitForText("Edit tags — 2 tracks")
        // The bulk contract is spelled out to the user…
        composeRule.onNodeWithText(
            "Shared values are pre-filled. Only fields you change are applied to all 2 tracks.",
        ).assertIsDisplayed()
        // …shared fields arrive prefilled…
        inDialog("Shoegaze").assertExists()
        inDialog("Shared Album").assertExists()
        // …the differing year shows the "Multiple values" hint instead…
        composeRule.waitForText("Multiple values", substring = true)
        // …and per-track fields (title, track#, disc#) are not offered in bulk.
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) {
            composeRule.onAllNodes(hasText("Track #")).fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForTextGone("Edit tags — 2 tracks")
    }
}
