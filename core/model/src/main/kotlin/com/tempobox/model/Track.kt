package com.tempobox.model

/**
 * Audio container/codec of a library file, derived from its extension (and, for
 * `.m4a`, from the codec reported by the tag reader — AAC files are [OTHER]).
 */
enum class AudioFormat(val extensions: Set<String>) {
    MP3(setOf("mp3")),
    FLAC(setOf("flac")),
    OGG(setOf("ogg", "oga", "opus")),
    ALAC(setOf("m4a", "mp4", "alac")),
    OTHER(emptySet());

    companion object {
        /** Best-effort mapping from a file extension (case-insensitive). */
        fun fromExtension(extension: String): AudioFormat {
            val ext = extension.lowercase()
            return entries.firstOrNull { ext in it.extensions } ?: OTHER
        }

        /** Every extension the library scanner should pick up. */
        val SUPPORTED_EXTENSIONS: Set<String> =
            entries.flatMap { it.extensions }.toSet()
    }
}

/**
 * A single audio file in the library.
 *
 * This is the app-facing domain model — the Room entity in `core:database` maps
 * 1:1 to it. Fields mirror the ID3v2/Vorbis tags plus library-managed metadata
 * (rating, play count, timestamps).
 *
 * @property id            Stable library id (Room primary key). 0 when not yet inserted.
 * @property filePath      Absolute path on device storage. Unique per track.
 * @property title         Tag title, falling back to file name at scan time.
 * @property artist        Track artist (TPE1).
 * @property albumArtist   Album artist (TPE2); falls back to [artist] at scan time.
 * @property album         Album title (TALB).
 * @property genre         Genre (TCON); empty when untagged.
 * @property year          Release year parsed from TDRC/TYER/DATE; null when absent.
 * @property trackNumber   Position within the disc; null when untagged.
 * @property discNumber    Disc number; null when untagged.
 * @property durationMs    Decoded duration in milliseconds.
 * @property format        Container/codec, see [AudioFormat].
 * @property bitrateKbps   Average bitrate; 0 when unknown.
 * @property sampleRateHz  Sample rate; 0 when unknown.
 * @property sizeBytes     File size at scan time.
 * @property rating        Library rating 0..5 stars; 0 means unrated.
 * @property playCount     Times played to >=50% or 4 minutes (scrobble rule).
 * @property dateAddedMs   Epoch millis when first scanned into the library.
 * @property dateModifiedMs File mtime at last scan (used for change detection & sorting).
 * @property hasEmbeddedArt Whether the tag contains embedded artwork.
 */
data class Track(
    val id: Long = 0,
    val filePath: String,
    val title: String,
    val artist: String = "",
    val albumArtist: String = "",
    val album: String = "",
    val genre: String = "",
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long = 0,
    val format: AudioFormat = AudioFormat.OTHER,
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 0,
    val sizeBytes: Long = 0,
    val rating: Int = 0,
    val playCount: Long = 0,
    val dateAddedMs: Long = 0,
    val dateModifiedMs: Long = 0,
    val hasEmbeddedArt: Boolean = false,
) {
    /** Artist to group under in Album Artist views (falls back to track artist). */
    val effectiveAlbumArtist: String
        get() = albumArtist.ifBlank { artist.ifBlank { UNKNOWN_ARTIST } }

    /** Album title with a stable placeholder for untagged files. */
    val effectiveAlbum: String
        get() = album.ifBlank { UNKNOWN_ALBUM }

    /** Genre with a stable placeholder for untagged files. */
    val effectiveGenre: String
        get() = genre.ifBlank { UNKNOWN_GENRE }

    companion object {
        const val UNKNOWN_ARTIST = "Unknown Artist"
        const val UNKNOWN_ALBUM = "Unknown Album"
        const val UNKNOWN_GENRE = "Unknown Genre"
        const val MAX_RATING = 5
    }
}
