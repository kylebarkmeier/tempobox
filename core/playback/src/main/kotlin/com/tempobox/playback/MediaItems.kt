package com.tempobox.playback

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.tempobox.model.AudioFormat
import com.tempobox.model.QueueItem
import com.tempobox.model.ShuffleMode
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
    private const val EXT_SHUFFLE_MODE = "voiceShuffleMode"
    private const val EXT_ORIGINAL_INDEX = "voiceOriginalIndex"

    /**
     * Marks one item of a queue the service shuffled (a voice request from
     * another controller): the flavor used and the track's pre-shuffle
     * position, so [PlayerConnection] can adopt the queue with shuffle on
     * and restore that order on "shuffle off".
     */
    data class Shuffled(val mode: ShuffleMode, val originalIndex: Int)

    fun artworkUri(path: String): Uri = "$ARTWORK_SCHEME:///${Uri.encode(path)}".toUri()

    fun pathFromArtworkUri(uri: Uri): String? =
        // Use encodedPath: uri.path is already decoded, and decoding twice
        // corrupts file names that legitimately contain percent signs.
        if (uri.scheme == ARTWORK_SCHEME) {
            uri.encodedPath?.removePrefix("/")?.let(Uri::decode)
        } else {
            null
        }

    fun toMediaItem(track: Track, uid: Long, shuffled: Shuffled? = null): MediaItem {
        val extras = Bundle().apply {
            shuffled?.let {
                putString(EXT_SHUFFLE_MODE, it.mode.name)
                putInt(EXT_ORIGINAL_INDEX, it.originalIndex)
            }
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
            .setUri(playbackUri(track.filePath))
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * Local library files play from disk; tracks browsed off a network media
     * server carry their HTTP resource URL in [Track.filePath] and stream.
     */
    fun playbackUri(path: String): Uri =
        if (path.startsWith("http://") || path.startsWith("https://")) {
            path.toUri()
        } else {
            Uri.fromFile(File(path))
        }

    /**
     * The items for a voice queue the service built: [order] indexes
     * [tracks] in play order; with a [mode] every item carries its
     * [Shuffled] mark (the track's pre-shuffle position is its index in
     * [tracks]), without one the items are unmarked. [uid] allocates each
     * item's queue uid.
     */
    fun voiceQueue(tracks: List<Track>, order: List<Int>, mode: ShuffleMode?, uid: () -> Long): List<MediaItem> =
        order.map { i -> toMediaItem(tracks[i], uid(), shuffled = mode?.let { Shuffled(it, originalIndex = i) }) }

    /** The [Shuffled] mark on an item, or null for an item no service shuffle produced. */
    fun shuffled(item: MediaItem): Shuffled? {
        val extras = item.mediaMetadata.extras ?: return null
        val name = extras.getString(EXT_SHUFFLE_MODE) ?: return null
        if (!extras.containsKey(EXT_ORIGINAL_INDEX)) return null
        val mode = ShuffleMode.entries.firstOrNull { it.name == name } ?: return null
        return Shuffled(mode, extras.getInt(EXT_ORIGINAL_INDEX))
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
