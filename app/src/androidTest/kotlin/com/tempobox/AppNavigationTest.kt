package com.tempobox

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full-app smoke test: launches the real activity (real DI graph, real DB)
 * and drives the drawer + tab navigation the way a user would.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppNavigationTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    // Pre-grant runtime permissions so no system dialog blocks the UI.
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

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun launches_intoLibraryWithAllTabs() {
        // NOTE: "Library" also exists as a (closed) drawer item, so tab labels
        // are asserted instead of the ambiguous screen title. The tab row is a
        // ScrollableTabRow, so later tabs start off-screen on narrow displays —
        // scroll each into view before asserting visibility.
        composeRule.onNodeWithText("Artists").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Albums").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Genres").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Tracks").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Playlists").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tabs_switchContent() {
        composeRule.onNodeWithText("Playlists").performScrollTo().performClick()
        // Playlist tab exposes its creation buttons.
        composeRule.onNodeWithText("New playlist").assertIsDisplayed()
        composeRule.onNodeWithText("New auto playlist").assertIsDisplayed()
    }

    @Test
    fun drawer_opensAndNavigatesToQueue() {
        composeRule.onNodeWithContentDescription("Open navigation").performClick()
        composeRule.onNodeWithText("Queue").performClick()
        composeRule.onNodeWithText("Queue (0)").assertIsDisplayed()
    }

    @Test
    fun drawer_navigatesToSettingsAndSections() {
        composeRule.onNodeWithContentDescription("Open navigation").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Locations, scanning, reset").assertIsDisplayed()

        composeRule.onNodeWithText("Shuffle").performClick()
        composeRule.onNodeWithText("Anti-repeat shuffle").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()
    }

    @Test
    fun drawer_navigatesToNowPlayingEmptyState() {
        composeRule.onNodeWithContentDescription("Open navigation").performClick()
        composeRule.onNodeWithText("Now Playing").performClick()
        composeRule.onNodeWithText("Nothing playing — pick something from the Library")
            .assertIsDisplayed()
    }
}
