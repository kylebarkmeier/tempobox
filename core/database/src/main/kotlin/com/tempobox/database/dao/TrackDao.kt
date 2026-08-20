package com.tempobox.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.tempobox.database.entity.TrackEntity
import com.tempobox.database.pojo.AlbumArtistRow
import com.tempobox.database.pojo.AlbumRow
import com.tempobox.database.pojo.GenreRow
import kotlinx.coroutines.flow.Flow

/**
 * Track table access. Aggregate views (albums / album artists / genres) are
 * GROUP BY projections over this one table, so they can never drift out of
 * sync with the underlying tracks.
 *
 * NOTE: the `CASE WHEN genre = '' THEN 'Unknown Genre' ...` expressions must
 * stay in sync with [com.tempobox.model.Track.UNKNOWN_GENRE].
 */
@Dao
interface TrackDao {

    // ---------------------------------------------------------------- inserts

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: TrackEntity): Long

    @Update
    suspend fun update(entity: TrackEntity)

    /**
     * Scanner upsert: refreshes file/tag metadata while PRESERVING the
     * library-managed columns (id, rating, playCount, dateAddedMs) of tracks
     * that already exist at the same path.
     */
    @Transaction
    suspend fun upsertKeepingUserData(entities: List<TrackEntity>) {
        for (entity in entities) {
            val existing = getByPath(entity.filePath)
            if (existing == null) {
                insert(entity)
            } else {
                update(
                    entity.copy(
                        id = existing.id,
                        rating = existing.rating,
                        playCount = existing.playCount,
                        dateAddedMs = existing.dateAddedMs,
                    ),
                )
            }
        }
    }

    // ---------------------------------------------------------------- lookups

    @Query("SELECT * FROM tracks WHERE filePath = :path LIMIT 1")
    suspend fun getByPath(path: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<TrackEntity>

    @Query("SELECT filePath FROM tracks")
    suspend fun getAllPaths(): List<String>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    // ---------------------------------------------------------------- observers

    @Query("SELECT * FROM tracks")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE album = :album AND albumArtist = :albumArtist ORDER BY discNumber, trackNumber, title COLLATE NOCASE")
    fun observeAlbumTracks(album: String, albumArtist: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE albumArtist = :albumArtist ORDER BY album COLLATE NOCASE, discNumber, trackNumber")
    fun observeArtistTracks(albumArtist: String): Flow<List<TrackEntity>>

    @Query(
        "SELECT * FROM tracks WHERE (CASE WHEN genre = '' THEN 'Unknown Genre' ELSE genre END) = :genre " +
            "ORDER BY albumArtist COLLATE NOCASE, album COLLATE NOCASE, discNumber, trackNumber",
    )
    fun observeGenreTracks(genre: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE dateAddedMs >= :sinceMs")
    fun observeRecentlyAdded(sinceMs: Long): Flow<List<TrackEntity>>

    // ---------------------------------------------------------------- aggregates

    @Query(
        """
        SELECT album AS name,
               albumArtist AS albumArtist,
               MAX(year) AS year,
               COUNT(*) AS trackCount,
               SUM(durationMs) AS durationMs,
               MIN(CASE WHEN hasEmbeddedArt THEN filePath ELSE NULL END) AS artworkTrackPath,
               MAX(dateAddedMs) AS dateAddedMs,
               MAX(dateModifiedMs) AS dateModifiedMs,
               MAX(rating) AS maxRating
        FROM tracks
        WHERE (:albumArtist IS NULL OR albumArtist = :albumArtist)
          AND (:genre IS NULL OR (CASE WHEN genre = '' THEN 'Unknown Genre' ELSE genre END) = :genre)
          AND (:sinceMs IS NULL OR dateAddedMs >= :sinceMs)
        GROUP BY album, albumArtist
        """,
    )
    fun observeAlbums(
        albumArtist: String? = null,
        genre: String? = null,
        sinceMs: Long? = null,
    ): Flow<List<AlbumRow>>

    @Query(
        """
        SELECT albumArtist AS name,
               COUNT(DISTINCT album) AS albumCount,
               COUNT(*) AS trackCount,
               GROUP_CONCAT(CASE WHEN genre = '' THEN 'Unknown Genre' ELSE genre END, '') AS genresConcat,
               GROUP_CONCAT(CASE WHEN hasEmbeddedArt THEN filePath ELSE NULL END, '') AS artPathsConcat,
               MAX(dateAddedMs) AS dateAddedMs,
               MAX(dateModifiedMs) AS dateModifiedMs
        FROM tracks
        WHERE (:genre IS NULL OR (CASE WHEN genre = '' THEN 'Unknown Genre' ELSE genre END) = :genre)
          AND (:sinceMs IS NULL OR dateAddedMs >= :sinceMs)
        GROUP BY albumArtist
        """,
    )
    fun observeAlbumArtists(
        genre: String? = null,
        sinceMs: Long? = null,
    ): Flow<List<AlbumArtistRow>>

    @Query(
        """
        SELECT (CASE WHEN genre = '' THEN 'Unknown Genre' ELSE genre END) AS name,
               COUNT(*) AS trackCount,
               COUNT(DISTINCT album) AS albumCount,
               MAX(dateAddedMs) AS dateAddedMs
        FROM tracks
        WHERE (:sinceMs IS NULL OR dateAddedMs >= :sinceMs)
        GROUP BY name
        """,
    )
    fun observeGenres(sinceMs: Long? = null): Flow<List<GenreRow>>

    // ---------------------------------------------------------------- library-managed columns

    @Query("UPDATE tracks SET rating = :rating WHERE id = :id")
    suspend fun setRating(id: Long, rating: Int)

    @Query("UPDATE tracks SET playCount = playCount + 1 WHERE id = :id")
    suspend fun incrementPlayCount(id: Long)

    // ---------------------------------------------------------------- removal

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM tracks WHERE filePath IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("DELETE FROM tracks")
    suspend fun deleteAll()
}
