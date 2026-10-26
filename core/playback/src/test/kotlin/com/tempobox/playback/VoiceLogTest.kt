package com.tempobox.playback

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private fun log(maxBytes: Long = VoiceLog.MAX_BYTES): Pair<VoiceLog, File> {
        // The parent directory does not exist yet: the log must create it.
        val file = File(folder.root, "voice/commands.log")
        return VoiceLog(file, dispatcher, scope, maxBytes) to file
    }

    private fun VoiceLog.recordNow(
        source: String,
        heard: String,
        command: String?,
        outcome: String,
        alternatives: List<String> = emptyList(),
    ) {
        record(source, heard, command, outcome, alternatives)
        scope.advanceUntilIdle()
    }

    @Test
    fun `record writes one line with every field`() {
        val (log, file) = log()
        log.recordNow(
            source = VoiceLog.SOURCE_DIALOG,
            heard = "add lamb of god to the queue",
            command = "AddToQueue(query=lamb of god)",
            outcome = "Added 12 tracks to queue (artist: Lamb of God)",
            alternatives = listOf("at lamb of god to the queue", "add lamb of god to the Q"),
        )
        val line = file.readText().trimEnd()
        // The timestamp is in the device's zone, so only its shape is fixed.
        assertThat(line).matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*")
        assertThat(line).contains(" source=\"dialog\" ")
        assertThat(line).contains(" heard=\"add lamb of god to the queue\"")
        assertThat(line)
            .contains(" alternatives=[\"at lamb of god to the queue\"; \"add lamb of god to the Q\"]")
        assertThat(line).contains(" command=\"AddToQueue(query=lamb of god)\"")
        assertThat(line).endsWith(" outcome=\"Added 12 tracks to queue (artist: Lamb of God)\"")
        assertThat(file.readText().lines().filter { it.isNotEmpty() }).hasSize(1)
    }

    @Test
    fun `a missing command is written as none and every value is quoted and escaped`() {
        val (log, file) = log()
        log.recordNow("x\nsource=forged", "say \"hi\"\nthere", command = null, outcome = "no command")
        val lines = file.readText().lines().filter { it.isNotEmpty() }
        assertThat(lines).hasSize(1)
        assertThat(lines.single()).contains(" source=\"x source=forged\" ")
        assertThat(lines.single()).contains(" heard=\"say \\\"hi\\\" there\"")
        assertThat(lines.single()).contains(" command=none ")
        assertThat(lines.single()).doesNotContain(" alternatives=")
    }

    @Test
    fun `record returns before the write and lines keep their order`() {
        val (log, file) = log()
        log.record(VoiceLog.SOURCE_SESSION, "first", "PlaySearch", "playing 2 tracks (artist: Allicorn)")
        log.record(VoiceLog.SOURCE_DIALOG, "second", "Pause", "Pausing")
        assertThat(file.exists()).isFalse()
        scope.advanceUntilIdle()
        val lines = file.readText().lines().filter { it.isNotEmpty() }
        assertThat(lines[0]).contains("heard=\"first\"")
        assertThat(lines[1]).contains("heard=\"second\"")
    }

    @Test
    fun `a full log rotates to a single previous generation`() {
        val (log, file) = log(maxBytes = 150)
        repeat(3) { log.recordNow(VoiceLog.SOURCE_DIALOG, "utterance number $it", "Pause", "Pausing") }
        // Each line is 100 to 110 bytes: one line stays under the limit, two
        // go over it, so the third write rotated before appending.
        val previous = File(file.path + ".1")
        assertThat(previous.isFile).isTrue()
        assertThat(previous.readText()).contains("utterance number 0")
        assertThat(previous.readText()).contains("utterance number 1")
        assertThat(file.readText()).doesNotContain("utterance number 1")
        assertThat(file.readText()).contains("utterance number 2")

        repeat(2) { log.recordNow(VoiceLog.SOURCE_DIALOG, "later utterance $it", "Pause", "Pausing") }
        // A second rotation replaces, not accumulates, the previous generation.
        assertThat(previous.readText()).doesNotContain("utterance number 0")
        assertThat(File(file.path + ".2").exists()).isFalse()
    }
}
