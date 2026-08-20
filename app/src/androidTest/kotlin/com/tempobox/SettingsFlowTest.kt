package com.tempobox

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings flows that back real product requirements. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SettingsFlowTest {

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

    @Before
    fun inject() {
        hiltRule.inject()
    }

    private fun openSettings() {
        composeRule.onNodeWithContentDescription("Open navigation").performClick()
        composeRule.onNodeWithText("Settings").performClick()
    }

    @Test
    fun librarySection_showsRescanAndReset() {
        openSettings()
        // Click via the unique subtitle ("Library" is ambiguous with the drawer item).
        composeRule.onNodeWithText("Locations, scanning, reset").performClick()
        composeRule.onNodeWithText("Rescan library").assertIsDisplayed()
        composeRule.onNodeWithText("Rescan on start & watch locations").assertIsDisplayed()
        composeRule.onNodeWithText("Reset library").assertIsDisplayed()
    }

    @Test
    fun resetLibrary_requiresTwoConfirmations() {
        openSettings()
        composeRule.onNodeWithText("Locations, scanning, reset").performClick()
        composeRule.onNodeWithText("Reset library").performClick()
        composeRule.onNodeWithText("Reset library?").assertIsDisplayed()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.onNodeWithText("Are you absolutely sure?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
    }

    @Test
    fun bluetoothSection_exposesGesturesAndRemapping() {
        openSettings()
        composeRule.onNodeWithText("Bluetooth & buttons").performClick()
        composeRule.onNodeWithText("Start playback on connect").assertIsDisplayed()
        composeRule.onNodeWithText("Triple-tap volume up").assertIsDisplayed()
        composeRule.onNodeWithText("Play/pause button").assertIsDisplayed()
    }

    @Test
    fun nowPlayingSection_exposesCornerButtons() {
        openSettings()
        composeRule.onNodeWithText("Corner buttons, track info").performClick()
        composeRule.onNodeWithText("Top left").assertIsDisplayed()
        composeRule.onNodeWithText("Bottom right").assertIsDisplayed()
    }
}
