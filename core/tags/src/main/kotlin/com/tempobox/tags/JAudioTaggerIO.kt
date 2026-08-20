package com.tempobox.tags

import android.util.Log
import com.tempobox.model.AudioFormat
import com.tempobox.model.TagData
import com.tempobox.model.Track
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.images.AndroidArtwork
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * jaudiotagger-backed implementation of [TagReader] and [TagWriter].
 *
 * Android notes:
 *  - Only [AndroidArtwork] is used for images — the awt-based artwork classes
 *    would crash on Android (no java.awt).
 *  - jaudiotagger's java.util.logging output is silenced once per process.
 */
@Singleton
class JAudioTaggerIO @Inject constructor() : TagReader, TagWriter {

    init {
        // jaudiotagger logs verbosely via j.u.l on malformed frames; silence it.
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    // ------------------------------------------------------------------ reading

    override fun readTrack(file: File): Track? = runCatching {
        val audio: AudioFile = AudioFileIO.read(file)
        val header = audio.audioHeader
        val tag: Tag? = audio.tag

        val title = tag.text(FieldKey.TITLE).ifBlank { file.nameWithoutExtension }
        val artist = tag.text(FieldKey.ARTIST)
        val albumArtist = tag.text(FieldKey.ALBUM_ARTIST)

        Track(
            filePath = file.absolutePath,
            title = title,
            artist = artist,
            // Scan-time fallback chain keeps the DB's albumArtist non-empty,
            // which the aggregate queries rely on.
            albumArtist = albumArtist.ifBlank { artist.ifBlank { Track.UNKNOWN_ARTIST } },
            album = tag.text(FieldKey.ALBUM),
            genre = tag.text(FieldKey.GENRE),
            year = parseYear(tag.text(FieldKey.YEAR)),
            trackNumber = tag.text(FieldKey.TRACK).substringBefore('/').toIntOrNull(),
            discNumber = tag.text(FieldKey.DISC_NO).substringBefore('/').toIntOrNull(),
            durationMs = header.trackLength * 1000L,
            format = detectFormat(file, header.format ?: "", header.encodingType ?: ""),
            bitrateKbps = header.bitRateAsNumber.toInt(),
            sampleRateHz = header.sampleRateAsNumber,
            sizeBytes = file.length(),
            dateModifiedMs = file.lastModified(),
            hasEmbeddedArt = tag?.firstArtwork != null,
        )
    }.onFailure {
        Log.w(TAG, "Unreadable audio file, skipping: ${file.path} (${it.message})")
    }.getOrNull()

    override fun readEmbeddedArtwork(file: File): ByteArray? = runCatching {
        AudioFileIO.read(file).tag?.firstArtwork?.binaryData
    }.getOrNull()

    // ------------------------------------------------------------------ writing

    override fun writeTags(file: File, data: TagData): Track? = runCatching {
        if (data.isEmpty) return readTrack(file)
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        // Only non-null fields are applied → bulk edits leave other fields intact.
        data.title?.let { tag.setField(FieldKey.TITLE, it) }
        data.artist?.let { tag.setField(FieldKey.ARTIST, it) }
        data.albumArtist?.let { tag.setField(FieldKey.ALBUM_ARTIST, it) }
        data.album?.let { tag.setField(FieldKey.ALBUM, it) }
        data.genre?.let { tag.setField(FieldKey.GENRE, it) }
        data.year?.let { tag.setField(FieldKey.YEAR, it.toString()) }
        data.trackNumber?.let { tag.setField(FieldKey.TRACK, it.toString()) }
        data.discNumber?.let { tag.setField(FieldKey.DISC_NO, it.toString()) }
        data.comment?.let { tag.setField(FieldKey.COMMENT, it) }
        audio.commit()
        // Re-read so callers persist exactly what landed in the file.
        readTrack(file)
    }.onFailure {
        Log.e(TAG, "Failed to write tags to ${file.path}", it)
    }.getOrNull()

    override fun writeArtwork(file: File, imageBytes: ByteArray, mimeType: String): Boolean =
        runCatching {
            val audio = AudioFileIO.read(file)
            val tag = audio.tagOrCreateAndSetDefault
            val artwork = AndroidArtwork().apply {
                binaryData = imageBytes
                this.mimeType = mimeType
                pictureType = FRONT_COVER_PICTURE_TYPE
            }
            tag.deleteArtworkField()
            tag.setField(artwork)
            audio.commit()
            true
        }.onFailure {
            Log.e(TAG, "Failed to write artwork to ${file.path}", it)
        }.getOrDefault(false)

    // ------------------------------------------------------------------ helpers

    /**
     * Container detection with special handling for `.m4a`: jaudiotagger
     * reports the codec in the header format/encoding string, letting us
     * distinguish ALAC (lossless) from plain AAC.
     */
    private fun detectFormat(file: File, headerFormat: String, encodingType: String): AudioFormat {
        val byExtension = AudioFormat.fromExtension(file.extension)
        if (byExtension != AudioFormat.ALAC) return byExtension
        val descriptor = "$headerFormat $encodingType".lowercase()
        return if ("alac" in descriptor || "lossless" in descriptor) AudioFormat.ALAC else AudioFormat.OTHER
    }

    private fun Tag?.text(key: FieldKey): String =
        this?.let { runCatching { it.getFirst(key) }.getOrNull() }?.trim().orEmpty()

    companion object {
        private const val TAG = "JAudioTaggerIO"

        /** ID3v2 APIC picture type 3 = front cover. */
        private const val FRONT_COVER_PICTURE_TYPE = 3

        /**
         * Parses a year from TDRC/TYER/DATE-style values ("1994", "1994-06-21").
         * Exposed for unit tests.
         */
        fun parseYear(raw: String): Int? {
            if (raw.isBlank()) return null
            // Take the first 4-digit run — handles "1994", "1994-06-21", "21/06/1994".
            val match = Regex("(\\d{4})").find(raw) ?: return null
            return match.value.toInt().takeIf { it in 1000..2999 }
        }
    }
}
