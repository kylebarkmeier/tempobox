package com.tempobox.scrobble

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.tempobox.model.Track
import com.tempobox.settings.AppSettings
import com.tempobox.settings.LastFmSettings
import com.tempobox.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LastFmScrobblerTest {

    private lateinit var server: MockWebServer
    private lateinit var store: PendingScrobbleStore
    private lateinit var scrobbler: LastFmScrobbler

    private val settingsFlow = MutableStateFlow(
        AppSettings(
            lastFm = LastFmSettings(
                scrobbleEnabled = true,
                apiKey = "KEY",
                apiSecret = "SECRET",
                username = "kyle",
                sessionKey = "SESSION",
            ),
        ),
    )
    private val settingsRepository: SettingsRepository = mockk(relaxed = true) {
        every { settings } returns settingsFlow
    }

    private val track = Track(
        id = 1, filePath = "/a.flac", title = "Song", artist = "Artist",
        albumArtist = "Artist", album = "Album", durationMs = 200_000,
    )

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        store = PendingScrobbleStore(ApplicationProvider.getApplicationContext())
        store.clear()
        scrobbler = LastFmScrobbler(
            settingsRepository = settingsRepository,
            pendingStore = store,
            client = OkHttpClient(),
            ioDispatcher = UnconfinedTestDispatcher(),
        ).apply { apiBaseUrl = server.url("/2.0/").toString() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueOk() {
        server.enqueue(
            MockResponse().setBody("""<lfm status="ok"><scrobbles accepted="1"/></lfm>"""),
        )
    }

    @Test
    fun `successful scrobble posts a signed batch request`() = runTest {
        enqueueOk()
        scrobbler.scrobble(track, startedAtEpochSec = 1_700_000_000)

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertThat(body).contains("method=track.scrobble")
        assertThat(body).contains("artist%5B0%5D=Artist")
        assertThat(body).contains("timestamp%5B0%5D=1700000000")
        assertThat(body).contains("api_sig=")
        assertThat(store.all()).isEmpty()
    }

    @Test
    fun `failed scrobble is queued for later`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        scrobbler.scrobble(track, startedAtEpochSec = 123)
        assertThat(store.all()).hasSize(1)
        assertThat(store.all().single().title).isEqualTo("Song")
    }

    @Test
    fun `flushPending drains the offline queue on success`() = runTest {
        store.add(PendingScrobble("A", "Old1", "Al", 1, 100))
        store.add(PendingScrobble("A", "Old2", "Al", 2, 100))
        enqueueOk()
        enqueueOk()

        scrobbler.flushPending()
        assertThat(store.all()).isEmpty()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `flushPending stops at the first failure and keeps the rest`() = runTest {
        store.add(PendingScrobble("A", "Old1", "Al", 1, 100))
        store.add(PendingScrobble("A", "Old2", "Al", 2, 100))
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))

        scrobbler.flushPending()
        assertThat(store.all()).hasSize(2)
    }

    @Test
    fun `disabled scrobbling is a no-op`() = runTest {
        settingsFlow.value = AppSettings(lastFm = LastFmSettings(scrobbleEnabled = false))
        scrobbler.scrobble(track, startedAtEpochSec = 1)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(store.all()).isEmpty()
    }
}
