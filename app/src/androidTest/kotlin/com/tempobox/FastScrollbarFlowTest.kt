package com.tempobox

import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.tempobox.database.TempoBoxDatabase
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Fast scrollbar end to end on a long Tracks list: hidden at rest, the thumb
 * appears on scroll, and dragging it to the bottom of the track jumps the list
 * to the far end.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FastScrollbarFlowTest {

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

    private val trackCount = 60

    @Before
    fun seedLongLibrary() {
        hiltRule.inject()
        runBlocking {
            db.trackDao().deleteAll()
            db.trackDao().upsertKeepingUserData(
                (1..trackCount).map { n ->
                    TestLibrary.track(
                        "/seed/long%03d.mp3".format(n),
                        title = "Track %03d".format(n),
                        artist = "Long Artist",
                        album = "Long Album",
                        trackNumber = n,
                    )
                },
            )
        }
    }

    @After
    fun clearLibrary() {
        runBlocking { db.trackDao().deleteAll() }
    }

    @Test
    fun scrollShowsThumb_dragJumpsToTheFarEndOfTheList() {
        composeRule.onNodeWithText("Tracks").performScrollTo().performClick()
        composeRule.waitForText("Track 001")

        // At rest the scrollbar is not composed, so row gestures stay clear.
        assertEquals(
            0,
            composeRule.onAllNodes(hasTestTag("fastScrollerThumb"))
                .fetchSemanticsNodes().size,
        )

        // Scroll the list: the thumb appears.
        composeRule.onRoot().performTouchInput {
            swipeUp(startY = height * 0.75f, endY = height * 0.35f)
        }
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) {
            composeRule.onAllNodes(hasTestTag("fastScrollerThumb"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Grab the thumb and drag it to the bottom of the track.
        val track = composeRule.onNodeWithTag("fastScroller")
            .fetchSemanticsNode().boundsInRoot
        val thumb = composeRule.onNodeWithTag("fastScrollerThumb")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("thumb should sit inside its track", thumb.top >= track.top - 1f)
        composeRule.onRoot().performTouchInput {
            swipe(
                start = Offset(thumb.center.x, thumb.center.y),
                end = Offset(thumb.center.x, track.bottom - 1f),
                durationMillis = 500,
            )
        }

        // The list jumped: the last seeded track is on screen, the first is
        // long since disposed by the lazy list.
        composeRule.waitForText("Track %03d".format(trackCount))
        composeRule.waitForTextGone("Track 001")
    }
}
