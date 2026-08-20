package com.tempobox.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.tempobox.database.dao.PlaylistDao
import com.tempobox.database.dao.TrackDao
import com.tempobox.database.entity.PlaylistEntity
import com.tempobox.database.entity.PlaylistEntryEntity
import com.tempobox.database.entity.TrackEntity

/**
 * The app database. Version history:
 *  1 — initial schema (tracks, playlists, playlist_entries).
 *
 * Schema export is disabled while the schema is young; enable it (and add
 * migration tests) before the first Play Store release.
 */
@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class TempoBoxDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        const val NAME = "tempobox.db"
    }
}
