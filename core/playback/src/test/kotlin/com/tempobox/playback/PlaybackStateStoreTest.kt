package com.tempobox.playback

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.tempobox.model.RepeatMode
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackStateStoreTest {

    private val store = PlaybackStateStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `snapshot round-trips through the store`() = runTest {
        val snapshot = PlaybackStateStore.Snapshot(
            trackIds = listOf(5L, 3L, 9L),
            currentIndex = 1,
            positionMs = 42_000,
            repeatMode = RepeatMode.ALL,
        )
        store.save(snapshot)
        assertThat(store.load()).isEqualTo(snapshot)
    }

    @Test
    fun `clear removes the snapshot`() = runTest {
        store.save(PlaybackStateStore.Snapshot(trackIds = listOf(1L)))
        store.clear()
        assertThat(store.load()).isNull()
    }
}
