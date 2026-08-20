package com.tempobox.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tempobox.model.Playlist
import com.tempobox.model.SmartRule

/**
 * Room row for a playlist (static or smart).
 *
 * Static playlists mirror an `.m3u8` file at [filePath]; smart playlists store
 * their rule tree as JSON in [smartRuleJson] and are materialized on read.
 */
@Entity(
    tableName = "playlists",
    indices = [Index(value = ["name"], unique = true)],
)
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val filePath: String?,
    val smartRuleJson: String?,
    val dateAddedMs: Long,
    val dateModifiedMs: Long,
)

/**
 * Ordered membership of a static playlist. Cascade delete keeps entries in
 * sync when a playlist or track is removed from the library.
 */
@Entity(
    tableName = "playlist_entries",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId"), Index("trackId")],
)
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val trackId: Long,
    val position: Int,
)

fun PlaylistEntity.toModel(trackCount: Int = 0, durationMs: Long = 0): Playlist = Playlist(
    id = id,
    name = name,
    filePath = filePath,
    smartRule = smartRuleJson?.let { SmartRule.fromJson(it) },
    trackCount = trackCount,
    durationMs = durationMs,
    dateAddedMs = dateAddedMs,
    dateModifiedMs = dateModifiedMs,
)
