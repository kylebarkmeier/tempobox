package com.tempobox.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.tempobox.database.entity.PlaylistEntity
import com.tempobox.database.entity.PlaylistEntryEntity
import com.tempobox.database.entity.TrackEntity
import com.tempobox.database.pojo.PlaylistWithStats
import kotlinx.coroutines.flow.Flow

/**
 * Playlist tables access. Static playlist ordering lives in `playlist_entries`
 * (position column); smart playlists have no entries — membership is computed
 * from their rule tree by `core:playlist`.
 */
@Dao
interface PlaylistDao {

    // ---------------------------------------------------------------- playlists

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByName(name: String): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE filePath = :path LIMIT 1")
    suspend fun getByFilePath(path: String): PlaylistEntity?

    @Query("SELECT * FROM playlists")
    suspend fun getAll(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE smartRuleJson IS NOT NULL")
    suspend fun getSmartPlaylists(): List<PlaylistEntity>

    /** All playlists with entry count + duration (0 for smart playlists). */
    @Query(
        """
        SELECT p.id AS id,
               p.name AS name,
               p.filePath AS filePath,
               p.smartRuleJson AS smartRuleJson,
               p.dateAddedMs AS dateAddedMs,
               p.dateModifiedMs AS dateModifiedMs,
               COUNT(e.id) AS trackCount,
               IFNULL(SUM(t.durationMs), 0) AS durationMs
        FROM playlists p
        LEFT JOIN playlist_entries e ON e.playlistId = p.id
        LEFT JOIN tracks t ON t.id = e.trackId
        GROUP BY p.id
        ORDER BY p.name COLLATE NOCASE
        """,
    )
    fun observeAllWithStats(): Flow<List<PlaylistWithStats>>

    @Query("UPDATE playlists SET dateModifiedMs = :modifiedMs WHERE id = :id")
    suspend fun touch(id: Long, modifiedMs: Long)

    // ---------------------------------------------------------------- entries

    @Insert
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun deleteEntries(playlistId: Long)

    @Query("SELECT MAX(position) FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int?

    /** Atomically replaces a playlist's entire track list (used by reorder & file import). */
    @Transaction
    suspend fun replaceEntries(playlistId: Long, trackIds: List<Long>) {
        deleteEntries(playlistId)
        insertEntries(
            trackIds.mapIndexed { index, trackId ->
                PlaylistEntryEntity(playlistId = playlistId, trackId = trackId, position = index)
            },
        )
    }

    /** Appends tracks at the end of a playlist. */
    @Transaction
    suspend fun appendEntries(playlistId: Long, trackIds: List<Long>) {
        val start = (maxPosition(playlistId) ?: -1) + 1
        insertEntries(
            trackIds.mapIndexed { index, trackId ->
                PlaylistEntryEntity(playlistId = playlistId, trackId = trackId, position = start + index)
            },
        )
    }

    /** Tracks of a static playlist in play order. */
    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN playlist_entries e ON e.trackId = t.id
        WHERE e.playlistId = :playlistId
        ORDER BY e.position
        """,
    )
    fun observePlaylistTracks(playlistId: Long): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN playlist_entries e ON e.trackId = t.id
        WHERE e.playlistId = :playlistId
        ORDER BY e.position
        """,
    )
    suspend fun getPlaylistTracks(playlistId: Long): List<TrackEntity>
}
