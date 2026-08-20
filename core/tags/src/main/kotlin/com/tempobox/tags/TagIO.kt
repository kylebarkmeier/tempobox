package com.tempobox.tags

import com.tempobox.model.TagData
import com.tempobox.model.Track
import java.io.File

/**
 * Reads metadata from audio files. Implemented by [JAudioTaggerReader];
 * interface kept small so `core:library` tests can use an in-memory fake.
 */
interface TagReader {
    /**
     * Reads tags + audio header of [file] into a [Track] (library-managed
     * fields left at defaults). Returns null when the file can't be parsed —
     * callers should skip such files rather than abort a scan.
     */
    fun readTrack(file: File): Track?

    /** Raw bytes of the first embedded artwork, or null when absent. */
    fun readEmbeddedArtwork(file: File): ByteArray?
}

/**
 * Writes metadata to audio files. All tag edits in the app funnel through this
 * single interface (CLAUDE.md rule 6) so DB re-sync happens in exactly one place.
 */
interface TagWriter {
    /**
     * Applies the non-null fields of [data] to [file]'s tag and saves it.
     * Returns the re-read [Track] on success, or null on failure (unwritable
     * file, unsupported field, IO error).
     */
    fun writeTags(file: File, data: TagData): Track?

    /** Replaces (or sets) the embedded artwork. Returns true on success. */
    fun writeArtwork(file: File, imageBytes: ByteArray, mimeType: String): Boolean
}
