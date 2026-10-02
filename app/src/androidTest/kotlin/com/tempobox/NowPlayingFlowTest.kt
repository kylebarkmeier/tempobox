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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/**
 * Now Playing end to end with real audio (generated silent WAV): header
 * metadata, configurable track-info lines, transport controls, default corner
 * buttons, and the slide-up queue sheet.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NowPlayingFlowTest {

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
            "nowplaying-test",
        )
        val wav = File(musicDir, "echo.wav")
        TestLibrary.writeSilentWav(wav, seconds = 60)
        runBlocking {
            db.trackDao().deleteAll()
            db.trackDao().upsertKeepingUserData(
                listOf(
                    TestLibrary.track(
                        wav.absolutePath, title = "Echo Song", artist = "Echo Artist",
                        album = "Echo Album", genre = "Ambient", year = 2020,
                        durationMs = 60_000,
                    ),
                ),
            )
        }
        player.clearQueue()
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) { player.queue.value.isEmpty() }
    }

    @After
    fun resetPlaybackState() {
        player.clearQueue()
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) { player.queue.value.isEmpty() }
        runBlocking {
            stateStore.clear()
            db.trackDao().deleteAll()
        }
        musicDir.deleteRecursively()
    }

    /** Taps the track in the library (really starts playback) and opens Now Playing. */
    private fun startPlaybackAndOpenNowPlaying() {
        composeRule.onNodeWithText("Tracks").performScrollTo().performClick()
        composeRule.waitForText("Echo Song")
        composeRule.onNodeWithText("Echo Song").performClick()

        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) { player.state.value.isPlaying }

        composeRule.onNodeWithContentDescription("Open navigation").performClick()
        composeRule.waitForText("Now Playing")
        composeRule.onNodeWithText("Now Playing").performClick()
    }

    @Test
    fun nowPlaying_showsHeaderMetadataAndTrackInfo() {
        startPlaybackAndOpenNowPlaying()

        // "artist – track" header and "album (year)" line (product spec).
        composeRule.waitForText("Echo Artist – Echo Song")
        composeRule.onNodeWithText("Echo Album (2020)").assertIsDisplayed()

        // Default track-info lines: Artist, Album, Year.
        composeRule.onNodeWithText("Artist: Echo Artist").assertIsDisplayed()
        composeRule.onNodeWithText("Album: Echo Album").assertIsDisplayed()
        composeRule.onNodeWithText("Year: 2020").assertIsDisplayed()
    }

    @Test
    fun transportControls_pauseAndResumeRealPlayback() {
        startPlaybackAndOpenNowPlaying()
        composeRule.waitForText("Echo Artist – Echo Song")

        composeRule.onNodeWithContentDescription("Pause").performClick()
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) { !player.state.value.isPlaying }
        composeRule.onNodeWithContentDescription("Play").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Play").performClick()
        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) { player.state.value.isPlaying }
    }

    @Test
    fun defaultCornerButtons_areConfiguredAroundTheArt() {
        startPlaybackAndOpenNowPlaying()
        composeRule.waitForText("Echo Artist – Echo Song")

        // Product defaults: artist / album / rate / add-to-playlist corners.
        composeRule.onNodeWithContentDescription("Go to artist").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Go to album").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Rate track").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Add to playlist").assertIsDisplayed()
    }

    @Test
    fun cornerButton_goToAlbum_opensTheAlbumDetail() {
        startPlaybackAndOpenNowPlaying()
        composeRule.waitForText("Echo Artist – Echo Song")

        composeRule.onNodeWithContentDescription("Go to album").performClick()
        composeRule.waitForText("Echo Album")
        composeRule.waitForText("Echo Song") // the album's track list
    }

    @Test
    fun queueSheet_isTheFullQueueComponent() {
        startPlaybackAndOpenNowPlaying()
        composeRule.waitForText("Echo Artist – Echo Song")

        composeRule.onNodeWithContentDescription("Show queue").performClick()
        composeRule.waitForText("Queue (1)")
        composeRule.onNodeWithContentDescription("Clear queue").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Multi-select").assertIsDisplayed()
    }

    @Test
    fun rateCorner_persistsARating() {
        startPlaybackAndOpenNowPlaying()
        composeRule.waitForText("Echo Artist – Echo Song")

        composeRule.onNodeWithContentDescription("Rate track").performClick()
        composeRule.waitForText("Rate \"Echo Song\"")
        composeRule.onNodeWithContentDescription("5 stars").performClick()

        composeRule.waitUntil(TestLibrary.WAIT_TIMEOUT_MS) {
            runBlocking {
                db.trackDao().getByPath(File(musicDir, "echo.wav").absolutePath)?.rating == 5
            }
        }
    }
}
