package com.tempobox.model

/**
 * A playlist. Two kinds:
 *
 * - **Static** ([smartRule] == null): an ordered list of tracks, backed by an
 *   `.m3u8` file on disk. Imported `.m3u` files are converted to `.m3u8` on
 *   first modification (the app only ever *writes* M3U8/UTF-8).
 * - **Smart/auto** ([smartRule] != null): membership is computed from the rule
 *   tree against the library and refreshed automatically when the library
 *   changes. Also exported as `.m3u8` so other players can read it.
 *
 * @property filePath Absolute path of the backing playlist file, or null for a
 *                    smart playlist that has not been exported yet.
 */
data class Playlist(
    val id: Long = 0,
    val name: String,
    val filePath: String? = null,
    val smartRule: SmartRule? = null,
    val trackCount: Int = 0,
    val durationMs: Long = 0,
    val dateAddedMs: Long = 0,
    val dateModifiedMs: Long = 0,
) {
    val isSmart: Boolean get() = smartRule != null
}

/** One entry of a static playlist (ordering is explicit via [position]). */
data class PlaylistEntry(
    val playlistId: Long,
    val trackId: Long,
    val position: Int,
)
