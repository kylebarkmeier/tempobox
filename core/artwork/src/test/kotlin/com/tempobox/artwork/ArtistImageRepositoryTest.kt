package com.tempobox.artwork

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.tempobox.settings.AppSettings
import com.tempobox.settings.ArtworkSettings
import com.tempobox.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Error-caching policy: a Discogs FAILURE (bad token, rate limit, server
 * error) must never be cached as a 30-day "artist has no image"; only genuine
 * answers (an image, or an empty result) may be.
 *
 * The OkHttp client is stubbed with an interceptor, so no network and no
 * change to the production URL.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ArtistImageRepositoryTest {

    private var nextCode = 200
    private var nextBody = EMPTY_RESULTS
    private var requestCount = 0

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            requestCount++
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(nextCode)
                .message("stubbed")
                .body(nextBody.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private val settingsRepository = mockk<SettingsRepository> {
        every { settings } returns flowOf(
            AppSettings(artwork = ArtworkSettings(discogsToken = "token")),
        )
    }

    private lateinit var repository: ArtistImageRepository

    @Before
    fun setUp() {
        repository = ArtistImageRepository(
            context = ApplicationProvider.getApplicationContext(),
            settingsRepository = settingsRepository,
            client = client,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @Test
    fun `an auth failure is not cached as a miss`() = runTest {
        repository.clearCache()
        nextCode = 401
        assertThat(repository.imageUrl("Nina Simone")).isNull()

        // Token fixed, Discogs answers: the earlier failure must not shadow it.
        nextCode = 200
        nextBody = FOUND
        assertThat(repository.imageUrl("Nina Simone")).isEqualTo("https://img.example/nina.jpg")
        assertThat(requestCount).isEqualTo(2)
    }

    @Test
    fun `a server error is not cached as a miss`() = runTest {
        repository.clearCache()
        nextCode = 503
        assertThat(repository.imageUrl("Nina Simone")).isNull()

        nextCode = 200
        nextBody = FOUND
        assertThat(repository.imageUrl("Nina Simone")).isNotNull()
    }

    @Test
    fun `a genuine empty result is cached`() = runTest {
        repository.clearCache()
        nextCode = 200
        nextBody = EMPTY_RESULTS
        assertThat(repository.imageUrl("Obscure Act")).isNull()

        // Even if Discogs would now answer, the cached miss is served.
        nextBody = FOUND
        assertThat(repository.imageUrl("Obscure Act")).isNull()
        assertThat(requestCount).isEqualTo(1)
    }

    @Test
    fun `a found image is cached`() = runTest {
        repository.clearCache()
        nextCode = 200
        nextBody = FOUND
        assertThat(repository.imageUrl("Nina Simone")).isEqualTo("https://img.example/nina.jpg")
        assertThat(repository.imageUrl("Nina Simone")).isEqualTo("https://img.example/nina.jpg")
        assertThat(requestCount).isEqualTo(1)
    }

    private companion object {
        const val EMPTY_RESULTS = """{"results":[]}"""
        const val FOUND = """{"results":[{"cover_image":"https:\/\/img.example\/nina.jpg"}]}"""
    }
}
