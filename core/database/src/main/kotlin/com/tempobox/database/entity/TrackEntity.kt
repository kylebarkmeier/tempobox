package com.tempobox.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tempobox.model.AudioFormat
import com.tempobox.model.Track

/**
 * Room row for a library track. Maps 1:1 to [Track]; keep the two in sync.
 *
 * `filePath` is unique — a rescan upserts by path so ratings/play counts
 * survive metadata refreshes (see [TrackDao.upsertKeepingUserData]).
 */
@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["filePath"], unique = true),
        Index(value = ["albumArtist"]),
        Index(value = ["album"]),
        Index(value = ["genre"]),
        Index(value = ["dateAddedMs"]),
    ],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val genre: String,
    val year: Int?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long,
    /** Stored as the enum name string for forward compatibility. */
    val format: String,
    val bitrateKbps: Int,
    val sampleRateHz: Int,
    val sizeBytes: Long,
    @ColumnInfo(defaultValue = "0") val rating: Int = 0,
    @ColumnInfo(defaultValue = "0") val playCount: Long = 0,
    val dateAddedMs: Long,
    val dateModifiedMs: Long,
    @ColumnInfo(defaultValue = "0") val hasEmbeddedArt: Boolean = false,
)

fun TrackEntity.toModel(): Track = Track(
    id = id,
    filePath = filePath,
    title = title,
    artist = artist,
    albumArtist = albumArtist,
    album = album,
    genre = genre,
    year = year,
    trackNumber = trackNumber,
    discNumber = discNumber,
    durationMs = durationMs,
    format = runCatching { AudioFormat.valueOf(format) }.getOrDefault(AudioFormat.OTHER),
    bitrateKbps = bitrateKbps,
    sampleRateHz = sampleRateHz,
    sizeBytes = sizeBytes,
    rating = rating,
    playCount = playCount,
    dateAddedMs = dateAddedMs,
    dateModifiedMs = dateModifiedMs,
    hasEmbeddedArt = hasEmbeddedArt,
)

fun Track.toEntity(): TrackEntity = TrackEntity(
    id = id,
    filePath = filePath,
    title = title,
    artist = artist,
    albumArtist = albumArtist,
    album = album,
    genre = genre,
    year = year,
    trackNumber = trackNumber,
    discNumber = discNumber,
    durationMs = durationMs,
    format = format.name,
    bitrateKbps = bitrateKbps,
    sampleRateHz = sampleRateHz,
    sizeBytes = sizeBytes,
    rating = rating,
    playCount = playCount,
    dateAddedMs = dateAddedMs,
    dateModifiedMs = dateModifiedMs,
    hasEmbeddedArt = hasEmbeddedArt,
)
