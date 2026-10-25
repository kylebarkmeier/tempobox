package com.tempobox.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Restore-index remapping when stored rows vanished before the restore. */
class QueueRestoreTest {

    @Test
    fun `index is unchanged when every stored row survived`() {
        assertThat(QueueRestore.remapIndex(listOf(1L, 2L, 3L), listOf(1L, 2L, 3L), 2)).isEqualTo(2)
        assertThat(QueueRestore.remapIndex(listOf(1L, 2L, 3L), listOf(1L, 2L, 3L), 0)).isEqualTo(0)
    }

    @Test
    fun `index shifts down past vanished earlier rows`() {
        // Stored [A, B, C, D] playing C; B vanished -> rebuilt [A, C, D], C at 1.
        assertThat(QueueRestore.remapIndex(listOf(1L, 2L, 3L, 4L), listOf(1L, 3L, 4L), 2))
            .isEqualTo(1)
    }

    @Test
    fun `a vanished current row lands on the next survivor`() {
        // Stored [A, B, C] playing B; B vanished -> rebuilt [A, C], resume at C.
        assertThat(QueueRestore.remapIndex(listOf(1L, 2L, 3L), listOf(1L, 3L), 1)).isEqualTo(1)
    }

    @Test
    fun `duplicate stored ids stay positional`() {
        // Stored [A, A, B] playing the second A; nothing vanished.
        assertThat(QueueRestore.remapIndex(listOf(1L, 1L, 2L), listOf(1L, 1L, 2L), 1)).isEqualTo(1)
        // Stored [A, B, A] playing the trailing A; B vanished -> [A, A], index 1.
        assertThat(QueueRestore.remapIndex(listOf(1L, 2L, 1L), listOf(1L, 1L), 2)).isEqualTo(1)
    }

    @Test
    fun `negative stored index maps to the start`() {
        assertThat(QueueRestore.remapIndex(listOf(1L, 2L), listOf(1L, 2L), -1)).isEqualTo(0)
    }
}
