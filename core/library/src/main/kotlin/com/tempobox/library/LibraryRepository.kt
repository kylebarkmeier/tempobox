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
import com.tempobox.model.trackComparator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.tempobox.tags.TagWriter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Facade over the track table: all library reads and mutations the UI needs.
 * (Playlists live in [PlaylistRepository]; scanning in [MediaScanner].)
 *
 * Sorting happens in memory with [SortSpec.trackComparator] because the
 * article-aware alphabetical order ("The Beatles" under B) can't be expressed
 * in SQLite cleanly. Library sizes (tens of thousands of tracks) sort in
 * single-digit milliseconds.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val trackDao: TrackDao,
    private val tagWriter: TagWriter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    // ------------------------------------------------------------------ tracks

    fun observeTracks(sort: SortSpec = SortSpec()): Flow<List<Track>> =
        trackDao.observeAll().map { rows ->
            rows.map { it.toModel() }.sortedWith(sort.trackComparator())
        }

    fun observeAlbumTracks(album: String, albumArtist: String): Flow<List<Track>> =
        trackDao.observeAlbumTracks(album, albumArtist).map { rows -> rows.map { it.toModel() } }

    fun observeArtistTracks(albumArtist: String): Flow<List<Track>> =
        trackDao.observeArtistTracks(albumArtist).map { rows -> rows.map { it.toModel() } }

    fun observeGenreTracks(genre: String): Flow<List<Track>> =
        trackDao.observeGenreTracks(genre).map { rows -> rows.map { it.toModel() } }

    fun observeRecentlyAddedTracks(sinceMs: Long, sort: SortSpec = SortSpec(SortKey.RECENTLY_ADDED, ascending = false)): Flow<List<Track>> =
        trackDao.observeRecentlyAdded(sinceMs).map { rows ->
            rows.map { it.toModel() }.sortedWith(sort.trackComparator())
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
        genre: String? = null,
        sinceMs: Long? = null,
        sort: SortSpec = SortSpec(),
    ): Flow<List<Album>> =
        trackDao.observeAlbums(albumArtist, genre, sinceMs).map { rows ->
            rows.map { it.toModel() }.sortedWith(albumComparator(sort))
        }

    fun observeAlbumArtists(
        genre: String? = null,
        sinceMs: Long? = null,
        sort: SortSpec = SortSpec(),
    ): Flow<List<AlbumArtist>> =
        trackDao.observeAlbumArtists(genre, sinceMs).map { rows ->
            rows.map { it.toModel() }.sortedWith(artistComparator(sort))
        }

    fun observeGenres(sinceMs: Long? = null, sort: SortSpec = SortSpec()): Flow<List<Genre>> =
        trackDao.observeGenres(sinceMs).map { rows ->
            val base = rows.map { it.toModel() }
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

    // ------------------------------------------------------------------ removal

    /** Removes tracks from the library DB only — files stay on disk. */
    suspend fun removeFromLibrary(trackIds: List<Long>) = withContext(ioDispatcher) {
        trackIds.chunked(SQL_CHUNK).forEach { trackDao.deleteByIds(it) }
    }

    /**
     * Permanently deletes the files from the device, then removes their rows.
     * Returns tracks whose file could not be deleted (kept in the library so
     * the UI can surface the failure).
     */
    suspend fun deleteFromDevice(trackIds: List<Long>): List<Track> = withContext(ioDispatcher) {
        val tracks = getTracksByIds(trackIds)
        val (deleted, failed) = tracks.partition { track ->
            val file = File(track.filePath)
            !file.exists() || file.delete()
        }
        deleted.map { it.id }.chunked(SQL_CHUNK).forEach { trackDao.deleteByIds(it) }
        if (failed.isNotEmpty()) {
            Log.w(TAG, "Could not delete ${failed.size} files (missing write permission?)")
        }
        failed
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
        private const val SQL_CHUNK = 500
    }
}
