package com.tempobox

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.tempobox.settings.ScrobbleSettings
import com.tempobox.settings.SettingsRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

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

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Before
    fun inject() {
        hiltRule.inject()
        // Settings persist on-device across tests: start from the product
        // defaults instead of assuming whatever an earlier test left behind.
        runBlocking { settingsRepository.updateScrobble { ScrobbleSettings() } }
    }

    @After
    fun restoreDefaults() {
        runBlocking { settingsRepository.updateScrobble { ScrobbleSettings() } }
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

    @Test
    fun scrobblingSection_isASingleToggle_defaultOn() {
        openSettings()
        composeRule.onNodeWithText("Hand played tracks to your scrobbler app").performClick()

        composeRule.waitForText("Hand scrobbles to a scrobbler app")
        // Spec: broadcast scrobbling is the default — no account, no token fields.
        val enabled = runBlocking { settingsRepository.settings.first().scrobble.broadcastScrobbles }
        assertTrue("Scrobble broadcasting must default to ON", enabled)
    }

    @Test
    fun scrobblingToggle_persistsThroughTheRepository() {
        // Known starting point: broadcasting explicitly ON (test isolation).
        runBlocking {
            settingsRepository.updateScrobble { it.copy(broadcastScrobbles = true) }
        }
        openSettings()
        composeRule.onNodeWithText("Hand played tracks to your scrobbler app").performClick()
        composeRule.waitForText("Hand scrobbles to a scrobbler app")

        composeRule.onNodeWithText("Hand scrobbles to a scrobbler app").performClick()
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) {
            runBlocking { !settingsRepository.settings.first().scrobble.broadcastScrobbles }
        }

        // Toggling back re-enables it (the single-toggle section round-trips).
        composeRule.onNodeWithText("Hand scrobbles to a scrobbler app").performClick()
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) {
            runBlocking { settingsRepository.settings.first().scrobble.broadcastScrobbles }
        }
    }

    @Test
    fun queueSection_exposesPersistenceAndConfirmations() {
        openSettings()
        composeRule.onNodeWithText("Persistence, confirmations").performClick()
        composeRule.onNodeWithText("Restore queue on restart").assertIsDisplayed()
        composeRule.onNodeWithText("Confirm before clearing").assertIsDisplayed()
        composeRule.onNodeWithText("Allow duplicates").assertIsDisplayed()
    }

    @Test
    fun shuffleSection_reflectsTheProductDefaults() {
        openSettings()
        composeRule.onNodeWithText("Shuffle").performClick()
        composeRule.onNodeWithText("Anti-repeat shuffle").assertIsDisplayed()
        composeRule.onNodeWithText("Favor higher-rated tracks").assertIsDisplayed()

        val shuffle = runBlocking { settingsRepository.settings.first().shuffle }
        assertTrue("Anti-repeat must default to ON", shuffle.antiRepeat)
        assertFalse("Rating bias must default to OFF", shuffle.ratingBias)
    }
}
