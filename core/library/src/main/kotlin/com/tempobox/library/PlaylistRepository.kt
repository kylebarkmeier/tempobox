package com.tempobox.library

import android.content.Context
import android.util.Log
import com.tempobox.common.IoDispatcher
import com.tempobox.common.resultOf
import com.tempobox.database.dao.PlaylistDao
import com.tempobox.database.dao.TrackDao
import com.tempobox.database.entity.PlaylistEntity
import com.tempobox.database.entity.toModel
import com.tempobox.model.Playlist
import com.tempobox.model.SmartRule
import com.tempobox.model.Track
import com.tempobox.playlist.M3uCodec
import com.tempobox.playlist.SmartPlaylistEngine
import com.tempobox.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playlist management.
 *
 * Invariants (product spec):
 *  - Every playlist the app creates or modifies exists on disk as UTF-8 M3U8.
 *  - Legacy `.m3u` files import fine but are upgraded to `.m3u8` on first edit.
 *  - Smart playlists are always evaluated against the *current* library, so
 *    they pick up new matching tracks automatically; their M3U8 export is
 *    refreshed by [LibraryInitializer] whenever the library changes.
 */
@Singleton
class PlaylistRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao,
    private val codec: M3uCodec,
    private val engine: SmartPlaylistEngine,
    private val settingsRepository: SettingsRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Playlist ids whose broken smart rule was already logged: observePlaylists
     * re-evaluates on every library change, and one log line per playlist is
     * signal where one per emission is spam.
     */
    private val loggedBrokenRules = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    // ------------------------------------------------------------------ observers

    /**
     * All playlists with live stats. Smart playlist counts/durations are
     * recomputed whenever the library changes (cheap: single pass per playlist).
     */
    fun observePlaylists(): Flow<List<Playlist>> =
        combine(playlistDao.observeAllWithStats(), trackDao.observeAll()) { rows, trackRows ->
            val library = trackRows.map { it.toModel() }
            rows.map { row ->
                val rule = row.smartRuleJson?.let { SmartRule.fromJson(it) }
                if (rule == null) {
                    val broken = row.smartRuleJson != null
                    if (broken && loggedBrokenRules.add(row.id)) {
                        Log.e(TAG, "Smart rule for playlist \"${row.name}\" failed to decode")
                    }
                    Playlist(
                        id = row.id, name = row.name, filePath = row.filePath,
                        smartRule = null, ruleBroken = broken,
                        trackCount = row.trackCount, durationMs = row.durationMs,
                        dateAddedMs = row.dateAddedMs, dateModifiedMs = row.dateModifiedMs,
                    )
                } else {
                    val tracks = engine.evaluate(rule, library)
                    Playlist(
                        id = row.id, name = row.name, filePath = row.filePath,
                        smartRule = rule, trackCount = tracks.size,
                        durationMs = tracks.sumOf { it.durationMs },
                        dateAddedMs = row.dateAddedMs, dateModifiedMs = row.dateModifiedMs,
                    )
                }
            }
        }

    /** Tracks of [playlist] in play order (smart = live evaluation). */
    fun observePlaylistTracks(playlist: Playlist): Flow<List<Track>> =
        playlist.smartRule?.let { rule ->
            trackDao.observeAll().map { rows -> engine.evaluate(rule, rows.map { it.toModel() }) }
        } ?: playlistDao.observePlaylistTracks(playlist.id).map { rows -> rows.map { it.toModel() } }

    /**
     * Lightweight listing (no live stats, no smart-rule evaluation): names and
     * ids only, for consumers like the media-browse tree that never show counts.
     */
    suspend fun getPlaylists(): List<Playlist> = withContext(ioDispatcher) {
        playlistDao.getAll().map { it.toModel() }
    }

    suspend fun getPlaylist(id: Long): Playlist? = withContext(ioDispatcher) {
        playlistDao.getById(id)?.toModel()
    }

    suspend fun getPlaylistTracks(playlist: Playlist): List<Track> = withContext(ioDispatcher) {
        playlist.smartRule?.let { rule ->
            engine.evaluate(rule, trackDao.observeAll().first().map { it.toModel() })
        } ?: playlistDao.getPlaylistTracks(playlist.id).map { it.toModel() }
    }

    // ------------------------------------------------------------------ creation

    /** Creates a static playlist (deduped name) and writes its `.m3u8` file. */
    suspend fun createPlaylist(name: String, trackIds: List<Long> = emptyList()): Playlist =
        withContext(ioDispatcher) {
            val unique = uniqueName(name)
            val file = File(playlistsDir(), codec.ensureM3u8Path("$unique.m3u8"))
            val now = System.currentTimeMillis()
            val id = playlistDao.insert(
                PlaylistEntity(
                    name = unique, filePath = file.absolutePath,
                    smartRuleJson = null, dateAddedMs = now, dateModifiedMs = now,
                ),
            )
            if (trackIds.isNotEmpty()) playlistDao.replaceEntries(id, trackIds)
            exportStatic(id)
            requireNotNull(playlistDao.getById(id)).toModel(trackIds.size)
        }

    /** Creates a smart ("auto") playlist from [rule] and exports its snapshot. */
    suspend fun createSmartPlaylist(name: String, rule: SmartRule): Playlist =
        withContext(ioDispatcher) {
            val unique = uniqueName(name)
            val file = File(playlistsDir(), codec.ensureM3u8Path("$unique.m3u8"))
            val now = System.currentTimeMillis()
            val id = playlistDao.insert(
                PlaylistEntity(
                    name = unique, filePath = file.absolutePath,
                    smartRuleJson = SmartRule.toJson(rule), dateAddedMs = now, dateModifiedMs = now,
                ),
            )
            exportSmart(requireNotNull(playlistDao.getById(id)))
            requireNotNull(playlistDao.getById(id)).toModel()
        }

    // ------------------------------------------------------------------ mutation

    /**
     * Appends tracks to a static playlist and rewrites its file. Returns false
     * without changing anything for a smart playlist (membership is
     * rule-driven) — reachable from the UI when a broken rule makes a smart
     * playlist look static, so this must refuse, not crash.
     */
    suspend fun addToPlaylist(playlistId: Long, trackIds: List<Long>): Boolean =
        withContext(ioDispatcher) {
            val playlist = playlistDao.getById(playlistId) ?: return@withContext false
            if (playlist.smartRuleJson != null) {
                Log.e(TAG, "Refusing to add tracks to smart playlist \"${playlist.name}\"")
                return@withContext false
            }
            playlistDao.appendEntries(playlistId, trackIds)
            playlistDao.touch(playlistId, System.currentTimeMillis())
            exportStatic(playlistId)
            true
        }

    /** Replaces a static playlist's contents (reorder / remove entries). */
    suspend fun replacePlaylistTracks(playlistId: Long, trackIds: List<Long>) =
        withContext(ioDispatcher) {
            playlistDao.replaceEntries(playlistId, trackIds)
            playlistDao.touch(playlistId, System.currentTimeMillis())
            exportStatic(playlistId)
        }

    /**
     * Deletes the playlist row. "Remove from library" keeps the `.m3u8` file
     * on disk ([deleteFile] = false); "Delete permanently" removes it too.
     *
     * Returns false when the file should have been deleted but was not (the
     * row is still removed — that part succeeded); the UI reports the
     * leftover file instead of claiming a clean delete.
     */
    suspend fun deletePlaylist(playlistId: Long, deleteFile: Boolean = true): Boolean =
        withContext(ioDispatcher) {
            val playlist = playlistDao.getById(playlistId) ?: return@withContext true
            var fileDeleted = true
            if (deleteFile) {
                playlist.filePath?.let { path ->
                    val file = File(path)
                    if (file.exists() && !file.delete()) {
                        fileDeleted = false
                        Log.w(TAG, "Could not delete playlist file $path")
                    }
                }
            }
            playlistDao.delete(playlistId)
            fileDeleted
        }

    // ------------------------------------------------------------------ file sync

    /**
     * Imports `.m3u`/`.m3u8` files found under the library locations that the
     * app doesn't know yet (native playlist support). Entries that don't match
     * a library track are skipped. Called after every full scan.
     */
    suspend fun importPlaylistFiles(locations: List<String>) = withContext(ioDispatcher) {
        val files = locations.map(::File)
            .filter { root ->
                val readable = root.isDirectory && root.canRead()
                if (!readable) {
                    Log.w(TAG, "Library location not readable, skipping playlist import: ${root.absolutePath}")
                }
                readable
            }
            .flatMap { root ->
                root.walkTopDown()
                    .onFail { dir, e -> Log.w(TAG, "Could not list $dir during playlist import", e) }
                    .filter { it.isFile && it.extension.lowercase() in setOf("m3u", "m3u8") }
                    .toList()
            }
        for (file in files) {
            if (playlistDao.getByFilePath(file.absolutePath) != null) continue
            // Also skip the upgraded twin of an imported .m3u we already track.
            if (playlistDao.getByFilePath(codec.ensureM3u8Path(file.absolutePath)) != null) continue
            val paths = runCatching { codec.read(file) }
                .onFailure { Log.w(TAG, "Unreadable playlist ${file.path}", it) }
                .getOrNull() ?: continue
            val trackIds = paths.mapNotNull { trackDao.getByPath(it)?.id }
            val now = System.currentTimeMillis()
            val name = uniqueName(file.nameWithoutExtension)
            val id = playlistDao.insert(
                PlaylistEntity(
                    name = name, filePath = file.absolutePath,
                    smartRuleJson = null, dateAddedMs = now, dateModifiedMs = now,
                ),
            )
            playlistDao.replaceEntries(id, trackIds)
            Log.i(TAG, "Imported playlist ${file.name} (${trackIds.size}/${paths.size} matched)")
        }
    }

    /** Rewrites every smart playlist's `.m3u8` snapshot (library changed). */
    suspend fun refreshSmartExports() = withContext(ioDispatcher) {
        playlistDao.getSmartPlaylists().forEach { exportSmart(it) }
    }

    /** Ids of the static playlists whose entries reference any of [trackIds]. */
    suspend fun playlistIdsContaining(trackIds: List<Long>): List<Long> =
        withContext(ioDispatcher) {
            trackIds.chunked(SQL_CHUNK)
                .flatMap { playlistDao.playlistIdsContaining(it) }
                .distinct()
        }

    /**
     * Rewrites the `.m3u8` of each playlist from its current entries and bumps
     * its modified time. Called after tracks were permanently deleted (their
     * entries cascade away in the DB, so the files must follow).
     */
    suspend fun exportStaticPlaylists(playlistIds: Collection<Long>) = withContext(ioDispatcher) {
        if (playlistIds.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        // One UPDATE for all the mtimes: a touch per playlist is one write
        // transaction each, and every one re-runs the playlists observers.
        playlistIds.chunked(SQL_CHUNK).forEach { playlistDao.touchAll(it, now) }
        playlistIds.forEach { exportStatic(it) }
    }

    // ------------------------------------------------------------------ helpers

    /** Writes a static playlist's current entries to its `.m3u8` file. */
    private suspend fun exportStatic(playlistId: Long) {
        val playlist = playlistDao.getById(playlistId) ?: return
        val tracks = playlistDao.getPlaylistTracks(playlistId).map { it.toModel() }
        writeUpgraded(playlist, tracks)
    }

    private suspend fun exportSmart(playlist: PlaylistEntity) {
        val rule = playlist.smartRuleJson?.let { SmartRule.fromJson(it) }
        if (rule == null) {
            if (playlist.smartRuleJson != null && loggedBrokenRules.add(playlist.id)) {
                Log.e(TAG, "Smart rule for playlist \"${playlist.name}\" failed to decode; not exporting")
            }
            return
        }
        val library = trackDao.observeAll().first().map { it.toModel() }
        writeUpgraded(playlist, engine.evaluate(rule, library))
    }

    /**
     * Writes the playlist file, upgrading a legacy `.m3u` path to `.m3u8`
     * (removing the old file and updating the DB row) per the spec: everything
     * the app modifies becomes M3U8.
     */
    private suspend fun writeUpgraded(playlist: PlaylistEntity, tracks: List<Track>) {
        val currentPath = playlist.filePath ?: File(playlistsDir(), "${playlist.name}.m3u8").absolutePath
        val m3u8Path = codec.ensureM3u8Path(currentPath)
        // resultOf, not runCatching: the block suspends (dao.update), and a
        // cancelled coroutine must not be logged as a write failure.
        resultOf {
            codec.write(File(m3u8Path), tracks)
            if (m3u8Path != currentPath) {
                val legacy = File(currentPath)
                if (legacy.exists() && !legacy.delete()) {
                    Log.w(TAG, "Could not delete legacy playlist file $currentPath after upgrade")
                }
                playlistDao.update(playlist.copy(filePath = m3u8Path))
            }
        }.onFailure { Log.w(TAG, "Failed to write playlist file $m3u8Path", it) }
    }

    /**
     * Folder for app-created playlist files: `<first library location>/Playlists`,
     * falling back to app-private storage when no location is configured.
     */
    private suspend fun playlistsDir(): File {
        val locations = settingsRepository.settings.first().library.locations
        val base = locations.firstOrNull()?.let(::File)
            ?: context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "Playlists").apply {
            if (!mkdirs() && !isDirectory) {
                Log.w(TAG, "Could not create playlists folder $absolutePath")
            }
        }
    }

    private suspend fun uniqueName(base: String): String {
        val trimmed = base.trim().ifBlank { "Playlist" }
        if (playlistDao.getByName(trimmed) == null) return trimmed
        var n = 2
        while (playlistDao.getByName("$trimmed ($n)") != null) n++
        return "$trimmed ($n)"
    }

    companion object {
        private const val TAG = "PlaylistRepository"
    }
}
