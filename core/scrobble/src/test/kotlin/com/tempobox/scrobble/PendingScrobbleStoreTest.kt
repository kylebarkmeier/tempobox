package com.tempobox.scrobble

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PendingScrobbleStoreTest {

    private lateinit var store: PendingScrobbleStore

    private fun scrobble(ts: Long) =
        PendingScrobble(artist = "A", title = "T$ts", album = "Al", timestampSec = ts, durationSec = 100)

    @Before
    fun setUp() {
        store = PendingScrobbleStore(ApplicationProvider.getApplicationContext())
        store.clear()
    }

    @Test
    fun `add and read back in order`() {
        store.add(scrobble(1))
        store.add(scrobble(2))
        assertThat(store.all().map { it.timestampSec }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `remove drops only the matching entry`() {
        store.add(scrobble(1))
        store.add(scrobble(2))
        store.remove(scrobble(1))
        assertThat(store.all().map { it.timestampSec }).containsExactly(2L)
    }

    @Test
    fun `queue survives a new store instance (durability)`() {
        store.add(scrobble(7))
        val fresh = PendingScrobbleStore(ApplicationProvider.getApplicationContext())
        assertThat(fresh.all().map { it.timestampSec }).containsExactly(7L)
    }

    @Test
    fun `corrupt file resets to empty instead of crashing`() {
        store.add(scrobble(1))
        val file = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "pending_scrobbles.json",
        )
        file.writeText("{ not valid json")
        assertThat(store.all()).isEmpty()
    }

    @Test
    fun `queue is capped to avoid unbounded growth`() {
        repeat(600) { store.add(scrobble(it.toLong())) }
        assertThat(store.all().size).isAtMost(500)
        // Oldest entries were dropped; the newest are retained.
        assertThat(store.all().last().timestampSec).isEqualTo(599L)
    }
}
