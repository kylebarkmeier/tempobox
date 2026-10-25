package com.tempobox.library

import android.util.Log
import com.tempobox.common.IoDispatcher
import com.tempobox.database.dao.TrackDao
import com.tempobox.database.entity.toEntity
import com.tempobox.database.entity.toModel
import com.tempobox.model.Album
import com.tempobox.model.AlbumArtist
import com.tempobox.model.Genre
import com.tempobox.model.SortKey
import com.tempobox.model.SortSpec
import com.tempobox.model.TagData
import com.tempobox.model.Track
import com.tempobox.model.sortNormalized
import com.tempobox.model.sortTracks
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.tempobox.tags.TagWriter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Chunk size for id lists bound into SQL, under SQLite's variable limit. */
internal const val SQL_CHUNK = 500

/**
 * Facade over the track table: all library reads and mutations the UI needs.
 * (Playlists live in [PlaylistRepository]; scanning in [MediaScanner].)
 *
 * Sorting happens in memory with [SortSpec.sortTracks] because the
 * article-aware alphabetical order ("The Beatles" under B) can't be expressed
 * in SQLite cleanly. Library sizes (tens of thousands of tracks) sort in
 * single-digit milliseconds.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val trackDao: TrackDao,
    private val tagWriter: TagWriter,
    private val playlistRepository: PlaylistRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    // ------------------------------------------------------------------ tracks

    fun observeTracks(sort: SortSpec = SortSpec()): Flow<List<Track>> =
        trackDao.observeAll().map { rows ->
            sort.sortTracks(rows.map { it.toModel() })
        }

    /** Default sort mirrors the album's natural order: disc then track number. */
    fun observeAlbumTracks(
        album: String,
        albumArtist: String,
        sort: SortSpec = SortSpec(SortKey.TRACK_NUMBER),
    ): Flow<List<Track>> =
        trackDao.observeAlbumTracks(album, albumArtist).map { rows ->
            sort.sortTracks(rows.map { it.toModel() })
        }

    /** Default sort mirrors the DAO's natural order: album, disc, track. */
    fun observeArtistTracks(
        albumArtist: String,
        sort: SortSpec = SortSpec(SortKey.ALBUM_ORDER),
    ): Flow<List<Track>> =
        trackDao.observeArtistTracks(albumArtist).map { rows ->
            sort.sortTracks(rows.map { it.toModel() })
        }

    /** Tracks whose track artist (not album artist) is [artist]. */
    fun observeTrackArtistTracks(
        artist: String,
        sort: SortSpec = SortSpec(SortKey.ALBUM_ORDER),
    ): Flow<List<Track>> =
        trackDao.observeTrackArtistTracks(artist).map { rows ->
            sort.sortTracks(rows.map { it.toModel() })
        }

    fun observeGenreTracks(
        genre: String,
        sort: SortSpec = SortSpec(SortKey.ALBUM_ORDER),
    ): Flow<List<Track>> =
        trackDao.observeGenreTracks(genre).map { rows ->
            sort.sortTracks(rows.map { it.toModel() })
        }

    fun observeRecentlyAddedTracks(sinceMs: Long, sort: SortSpec = SortSpec(SortKey.RECENTLY_ADDED, ascending = false)): Flow<List<Track>> =
        trackDao.observeRecentlyAdded(sinceMs).map { rows ->
            sort.sortTracks(rows.map { it.toModel() })
        }

    suspend fun getTracksByIds(ids: List<Long>): List<Track> = withContext(ioDispatcher) {
        // Preserve caller order (queue building relies on it); chunk for SQLite.
        val byId = ids.chunked(SQL_CHUNK)
            .flatMap { trackDao.getByIds(it) }
            .associateBy { it.id }
        ids.mapNotNull { byId[it]?.toModel() }
    }

    suspend fun getTrackByPath(path: String): Track? = withContext(ioDispatcher) {
        trackDao.getByPath(path)?.toModel()
    }

    // ------------------------------------------------------------------ aggregates

    fun observeAlbums(
        albumArtist: String? = null,
        artist: String? = null,
        genre: String? = null,
        sinceMs: Long? = null,
        sort: SortSpec = SortSpec(),
    ): Flow<List<Album>> =
        trackDao.observeAlbums(albumArtist, artist, genre, sinceMs).map { rows ->
            rows.map { it.toModel() }.sortedWith(albumComparator(sort))
        }

    fun observeAlbumArtists(
        genre: String? = null,
        sinceMs: Long? = null,
        sort: SortSpec = SortSpec(),
    ): Flow<List<AlbumArtist>> =
        combine(
            trackDao.observeAlbumArtists(genre, sinceMs),
            trackDao.observeAlbumArtistAlbumArt(genre, sinceMs),
        ) { rows, artRows ->
            val artByArtist = AlbumCollage.topArtPathsByKey(artRows)
            rows.map { it.toModel(artworkTrackPaths = artByArtist[it.name].orEmpty()) }
                .sortedWith(artistComparator(sort))
        }

    /** Track-artist aggregation (the Artists tab; album artists are separate). */
    fun observeTrackArtists(
        genre: String? = null,
        sort: SortSpec = SortSpec(),
    ): Flow<List<AlbumArtist>> =
        combine(
            trackDao.observeTrackArtists(genre),
            trackDao.observeTrackArtistAlbumArt(genre),
        ) { rows, artRows ->
            val artByArtist = AlbumCollage.topArtPathsByKey(artRows)
            rows.map { it.toModel(artworkTrackPaths = artByArtist[it.name].orEmpty()) }
                .sortedWith(artistComparator(sort))
        }

    fun observeGenres(sinceMs: Long? = null, sort: SortSpec = SortSpec()): Flow<List<Genre>> =
        combine(trackDao.observeGenres(sinceMs), trackDao.observeGenreAlbumArt()) { rows, artRows ->
            val artByGenre = AlbumCollage.topArtPathsByKey(artRows)
            val base = rows.map { it.toModel(artworkTrackPaths = artByGenre[it.name].orEmpty()) }
            when (sort.key) {
                SortKey.RECENTLY_ADDED, SortKey.LAST_MODIFIED ->
                    base.sortedBy { it.dateAddedMs }.let { if (sort.ascending) it else it.reversed() }
                else ->
                    base.sortedBy { it.name.sortNormalized() }.let { if (sort.ascending) it else it.reversed() }
            }
        }

    // ------------------------------------------------------------------ ratings & play counts

    /** Sets the 0..5 star rating (library-managed; not written to the file tag). */
    suspend fun setRating(trackId: Long, rating: Int) = withContext(ioDispatcher) {
        trackDao.setRating(trackId, rating.coerceIn(0, Track.MAX_RATING))
    }

    /** Called by playback when a track passes the "played" threshold. */
    suspend fun incrementPlayCount(trackId: Long) = withContext(ioDispatcher) {
        trackDao.incrementPlayCount(trackId)
    }

    // ------------------------------------------------------------------ tag editing

    /**
     * Applies [data] to every track in [trackIds]: writes the files via
     * [TagWriter], then re-syncs the database rows from what actually landed
     * on disk. Returns the tracks that failed (unwritable/permission denied).
     */
    suspend fun editTags(trackIds: List<Long>, data: TagData): List<Track> = withContext(ioDispatcher) {
        val tracks = getTracksByIds(trackIds)
        val failed = mutableListOf<Track>()
        val updated = tracks.mapNotNull { track ->
            val result = tagWriter.writeTags(File(track.filePath), data)
            if (result == null) {
                failed += track
                null
            } else {
                // Keep identity + library-managed fields; refresh tag fields.
                result.copy(
                    id = track.id,
                    rating = track.rating,
                    playCount = track.playCount,
                    dateAddedMs = track.dateAddedMs,
                ).toEntity()
            }
        }
        trackDao.upsertKeepingUserData(updated)
        failed
    }

    /**
     * Embeds [imageBytes] as the artwork of every track in [trackIds] via
     * [TagWriter], then updates the rows so the UI refreshes: hasEmbeddedArt
     * plus dateModifiedMs, which the artwork cache keys on (path + mtime).
     * Returns the tracks whose file could not be written.
     */
    suspend fun editArtwork(
        trackIds: List<Long>,
        imageBytes: ByteArray,
        mimeType: String,
    ): List<Track> = withContext(ioDispatcher) {
        val tracks = getTracksByIds(trackIds)
        val (written, failed) = tracks.partition { track ->
            tagWriter.writeArtwork(File(track.filePath), imageBytes, mimeType)
        }
        written.forEach { track ->
            trackDao.getById(track.id)?.let { entity ->
                // The artwork cache keys on path + mtime: the stored mtime must
                // change or stale art keeps rendering. Fall back to now when
                // the filesystem reports nothing useful.
                val mtime = File(track.filePath).lastModified()
                    .takeIf { it > entity.dateModifiedMs } ?: System.currentTimeMillis()
                trackDao.update(entity.copy(hasEmbeddedArt = true, dateModifiedMs = mtime))
            }
        }
        failed
    }

    // ------------------------------------------------------------------ removal

    /**
     * Removes tracks from the library DB only — files stay on disk, playlist
     * files included: the Remove confirmation promises "Files on the device
     * are not touched", and rewriting a playlist file here would even delete
     * an imported legacy `.m3u` through the upgrade path. The DB-side playlist
     * entries still cascade away with the rows.
     */
    suspend fun removeFromLibrary(trackIds: List<Long>) = withContext(ioDispatcher) {
        trackIds.chunked(SQL_CHUNK).forEach { trackDao.deleteByIds(it) }
    }

    /** Outcome of [deleteFromDevice]. */
    data class DeleteResult(
        /** Ids whose file is gone and whose row was removed. */
        val deletedIds: List<Long>,
        /** Tracks whose file could not be deleted (kept in the library). */
        val failed: List<Track>,
    )

    /**
     * Permanently deletes the files from the device, then removes their rows
     * and rewrites the `.m3u8` of every static playlist that referenced them
     * (row deletion cascades the entries; the files must follow the DB).
     * Ids with no library row are neither deleted nor reported, so callers
     * must act on [DeleteResult.deletedIds], never on "requested minus failed".
     */
    suspend fun deleteFromDevice(trackIds: List<Long>): DeleteResult = withContext(ioDispatcher) {
        // Captured before any file is deleted: the file watcher reacts to the
        // deletions below and can remove rows mid-loop, cascading their
        // playlist entries away before a later lookup would see them. A
        // playlist whose tracks all survive is rewritten unchanged, harmless.
        val affectedPlaylists = playlistRepository.playlistIdsContaining(trackIds)
        val tracks = getTracksByIds(trackIds)
        val (deleted, failed) = tracks.partition { track ->
            val file = File(track.filePath)
            !file.exists() || file.delete()
        }
        val deletedIds = deleted.map { it.id }
        deletedIds.chunked(SQL_CHUNK).forEach { trackDao.deleteByIds(it) }
        playlistRepository.exportStaticPlaylists(affectedPlaylists)
        if (failed.isNotEmpty()) {
            Log.w(
                TAG,
                "Could not delete ${failed.size} file(s) (missing write permission?): " +
                    failed.joinToString { it.filePath },
            )
        }
        DeleteResult(deletedIds, failed)
    }

    /** Settings ▸ "Reset library": wipes all tracks (files untouched). */
    suspend fun resetLibrary() = withContext(ioDispatcher) {
        trackDao.deleteAll()
    }

    // ------------------------------------------------------------------ comparators

    private fun albumComparator(sort: SortSpec): Comparator<Album> {
        val base: Comparator<Album> = when (sort.key) {
            SortKey.ALPHABETICAL -> compareBy { it.name.sortNormalized() }
            SortKey.RECENTLY_ADDED -> compareBy { it.dateAddedMs }
            SortKey.LAST_MODIFIED -> compareBy { it.dateModifiedMs }
            SortKey.RATING -> compareBy { it.maxRating }
            SortKey.TAG_DATE -> compareBy { it.year ?: Int.MIN_VALUE }
            // Track-list-only keys; no album view offers them. Fall back to name.
            else -> compareBy { it.name.sortNormalized() }
        }
        return (if (sort.ascending) base else base.reversed())
            .thenBy { it.name.sortNormalized() }
    }

    private fun artistComparator(sort: SortSpec): Comparator<AlbumArtist> {
        val base: Comparator<AlbumArtist> = when (sort.key) {
            SortKey.RECENTLY_ADDED -> compareBy { it.dateAddedMs }
            SortKey.LAST_MODIFIED -> compareBy { it.dateModifiedMs }
            // RATING and TAG_DATE don't apply to artists; fall back to name.
            else -> compareBy { it.name.sortNormalized() }
        }
        return (if (sort.ascending) base else base.reversed())
            .thenBy { it.name.sortNormalized() }
    }

    companion object {
        private const val TAG = "LibraryRepository"
    }
}
