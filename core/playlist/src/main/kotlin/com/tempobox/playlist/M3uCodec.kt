package com.tempobox.playlist

import com.tempobox.model.Track
import java.io.File
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes M3U / M3U8 playlist files.
 *
 * Reading accepts both `.m3u` (best-effort Latin-1/UTF-8) and `.m3u8`
 * (UTF-8). Writing ALWAYS produces `.m3u8` in UTF-8 with `#EXTM3U` /
 * `#EXTINF` metadata — the app never writes legacy M3U (product spec).
 */
@Singleton
class M3uCodec @Inject constructor() {

    /**
     * Parses playlist [content] into absolute file paths.
     *
     * - Blank lines and directives (`#…`) are skipped (EXTINF titles are
     *   display hints only — the library re-reads real tags anyway).
     * - Relative entries resolve against [baseDir] (the playlist's folder).
     * - Windows-style separators are normalized so playlists written by
     *   desktop players still resolve.
     */
    fun parse(content: String, baseDir: File): List<String> =
        content.lineSequence()
            .map { it.trim().removePrefix("﻿") } // strip BOM if present
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { entry ->
                val normalized = entry.replace('\\', '/')
                val file = File(normalized)
                if (file.isAbsolute) file else File(baseDir, normalized)
            }
            .map { it.normalize().absolutePath }
            .toList()

    /**
     * Reads a playlist file. `.m3u8` is decoded as UTF-8; plain `.m3u` tries
     * UTF-8 first and falls back to Latin-1 (the de-facto legacy encoding).
     */
    fun read(file: File): List<String> {
        val bytes = file.readBytes()
        val content = if (file.extension.equals("m3u8", ignoreCase = true)) {
            String(bytes, Charsets.UTF_8)
        } else {
            decodeBestEffort(bytes)
        }
        return parse(content, file.parentFile ?: File("/"))
    }

    /**
     * Serializes [tracks] to M3U8 text. Paths inside [baseDir] are written
     * relative (portable across devices); everything else absolute.
     */
    fun serialize(tracks: List<Track>, baseDir: File): String = buildString {
        appendLine("#EXTM3U")
        for (track in tracks) {
            val seconds = (track.durationMs / 1000).coerceAtLeast(0)
            val artist = track.artist.ifBlank { Track.UNKNOWN_ARTIST }
            appendLine("#EXTINF:$seconds,$artist - ${track.title}")
            appendLine(relativize(track.filePath, baseDir))
        }
    }

    /**
     * Writes [tracks] to [file] as UTF-8 M3U8. The target must have an
     * `.m3u8` extension — callers use [ensureM3u8Path] first.
     */
    fun write(file: File, tracks: List<Track>) {
        require(file.extension.equals("m3u8", ignoreCase = true)) {
            "TempoBox only writes .m3u8 playlists (got ${file.name})"
        }
        file.parentFile?.mkdirs()
        file.writeText(serialize(tracks, file.parentFile ?: File("/")), Charsets.UTF_8)
    }

    /** "Road Trip.m3u" → "Road Trip.m3u8"; already-.m3u8 paths pass through. */
    fun ensureM3u8Path(path: String): String =
        if (path.endsWith(".m3u8", ignoreCase = true)) path
        else path.substringBeforeLast('.', path) + ".m3u8"

    // ------------------------------------------------------------------ helpers

    private fun relativize(absolutePath: String, baseDir: File): String {
        val base = baseDir.normalize().absolutePath.trimEnd('/') + "/"
        return if (absolutePath.startsWith(base)) absolutePath.removePrefix(base) else absolutePath
    }

    private fun decodeBestEffort(bytes: ByteArray): String {
        // Try strict UTF-8; malformed sequences → assume Latin-1.
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrElse { String(bytes, Charset.forName("ISO-8859-1")) }
    }
}
