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
import com.tempobox.playback.PlaybackStateStore
import com.tempobox.playback.PlayerConnection
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/**
 * Queue journeys end to end against the real playback service: enqueueing from
 * the library menus, Play next ordering, multi-select removal, and the
 * confirmed Clear — asserted through both the UI and [PlayerConnection]
 * (the single public playback API).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class QueueFlowTest {

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

    @Inject
    lateinit var player: PlayerConnection

    @Inject
    lateinit var stateStore: PlaybackStateStore

    private lateinit var musicDir: File

    @Before
    fun seedLibrary() {
        hiltRule.inject()
        musicDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "queue-test",
        )
        val titles = listOf("Alpha Song", "Bravo Song", "Charlie Song")
        runBlocking {
            db.trackDao().deleteAll()
            db.trackDao().upsertKeepingUserData(
                titles.mapIndexed { i, title ->
                    val wav = File(musicDir, "track$i.wav")
                    TestLibrary.writeSilentWav(wav)
                    TestLibrary.track(wav.absolutePath, title = title, trackNumber = i + 1)
                },
            )
        }
        clearQueueAndWait()
    }

    @After
    fun resetPlaybackState() {
        clearQueueAndWait()
        runBlocking {
            stateStore.clear()
            db.trackDao().deleteAll()
        }
        musicDir.deleteRecursively()
    }

    private fun clearQueueAndWait() {
        player.clearQueue()
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) { player.queue.value.isEmpty() }
    }

    private fun waitForQueueSize(size: Int) {
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) { player.queue.value.size == size }
    }

    private fun addToQueueFromMenu(title: String) {
        composeRule.onNodeWithContentDescription("More options for $title").performClick()
        composeRule.waitForText("Add to queue")
        composeRule.onNodeWithText("Add to queue").performClick()
    }

    private fun openTracksTab() {
        composeRule.onNodeWithText("Tracks").performScrollTo().performClick()
        composeRule.waitForText("Alpha Song")
    }

    private fun openQueueScreen() {
        composeRule.onNodeWithContentDescription("Open navigation").performClick()
        composeRule.waitForText("Queue")
        composeRule.onNodeWithText("Queue").performClick()
    }

    @Test
    fun addToQueue_showsTracksOnTheQueueScreen() {
        openTracksTab()
        addToQueueFromMenu("Alpha Song")
        waitForQueueSize(1)
        addToQueueFromMenu("Bravo Song")
        waitForQueueSize(2)

        openQueueScreen()
        composeRule.waitForText("Queue (2)")
        // waitForText (count-based) rather than single-node assertions: the
        // mini player at the bottom may legitimately show the same title.
        composeRule.waitForText("Alpha Song")
        composeRule.waitForText("Bravo Song")
    }

    @Test
    fun playNext_insertsRightAfterTheCurrentTrack() {
        openTracksTab()
        addToQueueFromMenu("Alpha Song")
        waitForQueueSize(1)
        addToQueueFromMenu("Bravo Song")
        waitForQueueSize(2)

        composeRule.onNodeWithContentDescription("More options for Charlie Song").performClick()
        composeRule.waitForText("Play next")
        composeRule.onNodeWithText("Play next").performClick()

        waitForQueueSize(3)
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) {
            player.queue.value.map { it.track.title } ==
                listOf("Alpha Song", "Charlie Song", "Bravo Song")
        }
    }

    @Test
    fun multiSelect_removesTheSelectedTracks() {
        openTracksTab()
        addToQueueFromMenu("Alpha Song")
        waitForQueueSize(1)
        addToQueueFromMenu("Bravo Song")
        waitForQueueSize(2)

        openQueueScreen()
        composeRule.waitForText("Queue (2)")
        composeRule.onNodeWithContentDescription("Multi-select").performClick()
        composeRule.waitForText("0 selected")
        composeRule.onNodeWithText("Bravo Song").performClick()
        composeRule.waitForText("1 selected")
        composeRule.onNodeWithContentDescription("Remove selected from queue").performClick()

        waitForQueueSize(1)
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) {
            player.queue.value.single().track.title == "Alpha Song"
        }
        composeRule.waitForTextGone("Bravo Song")
    }

    @Test
    fun clearQueue_asksForConfirmation_thenEmptiesTheQueue() {
        openTracksTab()
        addToQueueFromMenu("Alpha Song")
        waitForQueueSize(1)

        openQueueScreen()
        composeRule.waitForText("Queue (1)")
        composeRule.onNodeWithContentDescription("Clear queue").performClick()

        // Confirmation is the product default (Settings ▸ Queue).
        composeRule.waitForText("Clear queue?")
        composeRule.onNodeWithText("Clear").performClick()

        waitForQueueSize(0)
        composeRule.waitForText("Queue (0)")
    }

    @Test
    fun cancellingClear_keepsTheQueue() {
        openTracksTab()
        addToQueueFromMenu("Alpha Song")
        waitForQueueSize(1)

        openQueueScreen()
        composeRule.waitForText("Queue (1)")
        composeRule.onNodeWithContentDescription("Clear queue").performClick()
        composeRule.waitForText("Clear queue?")
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.waitForTextGone("Clear queue?")
        composeRule.onNodeWithText("Queue (1)").assertIsDisplayed()
        // Cancel must retain the exact queued track, not merely one item.
        assertEquals(
            listOf("Alpha Song"),
            player.queue.value.map { it.track.title },
        )
    }
}
