package com.tempobox.playback

import android.content.Context
import android.util.Log
import com.tempobox.common.ApplicationScope
import com.tempobox.common.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Append-only record of every voice request the app receives, kept in the
 * app's private no-backup files so it outlives logcat's buffer but never
 * leaves the device (transcripts are not for Auto Backup). One line per
 * event: where it came from, what the recognizer heard (plus its
 * alternatives), what it parsed to, and what actually happened, so a "voice
 * did nothing" report can be checked against the facts instead of a guess.
 * Each line is also written to logcat under the `VoiceLog` tag, the one
 * place voice events are logged.
 *
 * Pull from a debug build with
 * `adb exec-out run-as com.tempobox.debug cat no_backup/voice/commands.log`
 * (the previous rotation is `commands.log.1`).
 */
@Singleton
class VoiceLog internal constructor(
    private val file: File,
    private val dispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val maxBytes: Long = MAX_BYTES,
) {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        @IoDispatcher dispatcher: CoroutineDispatcher,
        @ApplicationScope scope: CoroutineScope,
    ) : this(File(context.noBackupFilesDir, "voice/commands.log"), dispatcher, scope)

    /** Serializes rotation and appends: two writers at the size limit must not both rotate. */
    private val lock = Mutex()

    /**
     * Records one event; the line is written in the background. [command]
     * is null when nothing parsed; [alternatives] are the recognizer's other
     * guesses, worth keeping when the first one was wrong. The timestamp is
     * taken now, not at write time.
     */
    fun record(
        source: String,
        heard: String,
        command: String?,
        outcome: String,
        alternatives: List<String> = emptyList(),
    ) {
        val line = buildString {
            append(TIMESTAMP.format(Instant.now().atZone(ZoneId.systemDefault())))
            append(" source=").append(quote(source))
            append(" heard=").append(quote(heard))
            if (alternatives.isNotEmpty()) {
                append(" alternatives=[").append(alternatives.joinToString("; ") { quote(it) }).append(']')
            }
            append(" command=").append(command?.let { quote(it) } ?: "none")
            append(" outcome=").append(quote(outcome))
        }
        Log.i(TAG, line)
        scope.launch { write(line) }
    }

    internal suspend fun write(line: String) = withContext(dispatcher) {
        lock.withLock {
            try {
                rotateIfFull()
                val dir = file.parentFile
                if (dir != null && !dir.isDirectory && !dir.mkdirs()) {
                    Log.w(TAG, "Could not create ${dir.path}; voice log line dropped")
                    return@withLock
                }
                file.appendText(line + "\n")
            } catch (e: IOException) {
                Log.w(TAG, "Could not write the voice log", e)
            }
        }
    }

    /** Keeps one previous generation so a pull right after rotation loses nothing recent. */
    private fun rotateIfFull() {
        if (!file.isFile || file.length() < maxBytes) return
        val previous = File(file.path + ".1")
        if (previous.exists() && !previous.delete()) {
            Log.w(TAG, "Could not delete ${previous.path}; keeping the oversized log")
            return
        }
        if (!file.renameTo(previous)) {
            Log.w(TAG, "Could not rotate ${file.path}; keeping the oversized log")
        }
    }

    private fun quote(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace('\n', ' ')
            .replace('\r', ' ') + "\""

    companion object {
        private const val TAG = "VoiceLog"
        internal const val MAX_BYTES = 256L * 1024
        private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        /** MediaItem request-metadata extra naming where a search query came from. */
        const val EXTRA_SOURCE = "com.tempobox.voice.source"

        /** The in-app listening dialog (VoiceCommandActivity). */
        const val SOURCE_DIALOG = "dialog"

        /** The assistant's MEDIA_PLAY_FROM_SEARCH intent (MainActivity). */
        const val SOURCE_ASSISTANT_INTENT = "assistant-intent"

        /** A search query that reached the media session from another controller. */
        const val SOURCE_SESSION = "session"

        /** Android Auto's search screen (onSearch). */
        const val SOURCE_AUTO_SEARCH = "auto-search"

        /** The only values the session accepts from a controller's extra; anything else is [SOURCE_SESSION]. */
        val KNOWN_SOURCES = setOf(SOURCE_DIALOG, SOURCE_ASSISTANT_INTENT)
    }
}
