package com.tempobox

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.tempobox.database.entity.TrackEntity
import com.tempobox.model.AudioFormat
import com.tempobox.model.Track
import com.tempobox.database.entity.toEntity
import java.io.File
import java.io.RandomAccessFile

/**
 * Shared helpers for the instrumented suite.
 *
 * Tests seed the real (on-disk, Hilt-provided) Room database through the
 * injected DAOs/repositories — the same write path the scanner uses — and the
 * reactive UI picks the rows up. Audio content, when playback actually has to
 * start, is a generated silent WAV (ExoPlayer sniffs content, not extensions).
 */
object TestLibrary {

    const val WAIT_TIMEOUT_MS = 15_000L

    /** Builds a library track row; [path] must be unique per track. */
    fun track(
        path: String,
        title: String,
        artist: String = "Test Artist",
        albumArtist: String = artist,
        album: String = "Test Album",
        genre: String = "Rock",
        year: Int? = 2020,
        trackNumber: Int? = 1,
        rating: Int = 0,
        durationMs: Long = 30_000,
    ): TrackEntity = Track(
        filePath = path,
        title = title,
        artist = artist,
        albumArtist = albumArtist,
        album = album,
        genre = genre,
        year = year,
        trackNumber = trackNumber,
        discNumber = 1,
        durationMs = durationMs,
        format = AudioFormat.MP3,
        bitrateKbps = 320,
        sampleRateHz = 44_100,
        sizeBytes = 1,
        rating = rating,
        dateAddedMs = System.currentTimeMillis(),
        dateModifiedMs = System.currentTimeMillis(),
    ).toEntity()

    /**
     * Writes [seconds] of silent 16-bit mono 8 kHz PCM as a valid WAV file, so
     * ExoPlayer can genuinely play the "track" (format is sniffed from content,
     * never from the file extension).
     */
    fun writeSilentWav(file: File, seconds: Int = 30) {
        val sampleRate = 8_000
        val dataSize = sampleRate * 2 * seconds
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.write(intLe(36 + dataSize))
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            out.write(intLe(16)) // PCM header size
            out.write(shortLe(1)) // PCM
            out.write(shortLe(1)) // mono
            out.write(intLe(sampleRate))
            out.write(intLe(sampleRate * 2)) // byte rate
            out.write(shortLe(2)) // block align
            out.write(shortLe(16)) // bits per sample
            out.write("data".toByteArray(Charsets.US_ASCII))
            out.write(intLe(dataSize))
            out.setLength(44L + dataSize) // zero-filled samples = silence
        }
    }

    private fun intLe(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun shortLe(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    )
}

/** Waits (Compose-synchronized, no sleeps) until [text] exists in the tree. */
fun ComposeTestRule.waitForText(
    text: String,
    substring: Boolean = false,
    timeoutMillis: Long = TestLibrary.WAIT_TIMEOUT_MS,
) {
    waitUntil(timeoutMillis) {
        onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
    }
}

/** Waits until no node with [text] remains (e.g. a removed row or closed dialog). */
fun ComposeTestRule.waitForTextGone(
    text: String,
    substring: Boolean = false,
    timeoutMillis: Long = TestLibrary.WAIT_TIMEOUT_MS,
) {
    waitUntil(timeoutMillis) {
        onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isEmpty()
    }
}
