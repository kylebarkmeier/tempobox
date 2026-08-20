package com.tempobox.playback

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.tempobox.model.AudioFormat
import com.tempobox.model.QueueItem
import com.tempobox.model.Track
import java.io.File

/**
 * Track ↔ [MediaItem] mapping.
 *
 * The mediaId is the queue item uid (unique per enqueue, so duplicates of a
 * track are individually addressable). Full track fields ride along in the
 * metadata extras so the queue can be reconstructed from the player timeline
 * alone — e.g. after process death — without a DB round-trip.
 */
object MediaItems {

    /** Scheme understood by [ArtworkBitmapLoader] for embedded tag artwork. */
    const val ARTWORK_SCHEME = "tempobox-art"

    private const val EXT_TRACK_ID = "trackId"
    private const val EXT_PATH = "path"
    private const val EXT_DURATION = "durationMs"
    private const val EXT_RATING = "rating"
    private const val EXT_GENRE = "genre"
    private const val EXT_FORMAT = "format"
    private const val EXT_ALBUM_ARTIST = "albumArtist"
    private const val EXT_YEAR = "year"

    fun artworkUri(path: String): Uri = "$ARTWORK_SCHEME:///${Uri.encode(path)}".toUri()

    fun pathFromArtworkUri(uri: Uri): String? =
        // Use encodedPath: uri.path is already decoded, and decoding twice
        // corrupts file names that legitimately contain percent signs.
        if (uri.scheme == ARTWORK_SCHEME) {
            uri.encodedPath?.removePrefix("/")?.let(Uri::decode)
        } else {
            null
        }

    fun toMediaItem(track: Track, uid: Long): MediaItem {
        val extras = Bundle().apply {
            putLong(EXT_TRACK_ID, track.id)
            putString(EXT_PATH, track.filePath)
            putLong(EXT_DURATION, track.durationMs)
            putInt(EXT_RATING, track.rating)
            putString(EXT_GENRE, track.genre)
            putString(EXT_FORMAT, track.format.name)
            putString(EXT_ALBUM_ARTIST, track.albumArtist)
            track.year?.let { putInt(EXT_YEAR, it) }
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist.ifBlank { track.effectiveAlbumArtist })
            .setAlbumTitle(track.album)
            .setAlbumArtist(track.effectiveAlbumArtist)
            .setArtworkUri(if (track.hasEmbeddedArt) artworkUri(track.filePath) else null)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(uid.toString())
            .setUri(Uri.fromFile(File(track.filePath)))
            .setMediaMetadata(metadata)
            .build()
    }

    /** Rebuilds the queue-facing [Track] from an item's metadata (lossy but complete for UI). */
    fun toTrack(item: MediaItem): Track {
        val md = item.mediaMetadata
        val extras = md.extras ?: Bundle.EMPTY
        return Track(
            id = extras.getLong(EXT_TRACK_ID),
            filePath = extras.getString(EXT_PATH).orEmpty(),
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString().orEmpty(),
            albumArtist = extras.getString(EXT_ALBUM_ARTIST).orEmpty(),
            album = md.albumTitle?.toString().orEmpty(),
            genre = extras.getString(EXT_GENRE).orEmpty(),
            year = if (extras.containsKey(EXT_YEAR)) extras.getInt(EXT_YEAR) else null,
            durationMs = extras.getLong(EXT_DURATION),
            format = runCatching { AudioFormat.valueOf(extras.getString(EXT_FORMAT).orEmpty()) }
                .getOrDefault(AudioFormat.OTHER),
            rating = extras.getInt(EXT_RATING),
            hasEmbeddedArt = md.artworkUri != null,
        )
    }

    fun toQueueItem(item: MediaItem): QueueItem =
        QueueItem(uid = item.mediaId.toLongOrNull() ?: -1L, track = toTrack(item))
}
