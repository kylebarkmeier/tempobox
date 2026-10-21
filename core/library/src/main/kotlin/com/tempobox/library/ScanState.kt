package com.tempobox.library

/** Progress of a library scan, observed by Settings and the Library views. */
sealed interface ScanState {
    /** No scan running. */
    data object Idle : ScanState

    /** Enumerating files / reading tags. [total] is 0 while still counting. */
    data class Scanning(val scanned: Int, val total: Int) : ScanState

    /**
     * Last scan's outcome (kept until the next scan starts).
     *
     * @property skippedFiles Files on disk whose tags could not be read; their
     *                        existing rows (if any) are kept untouched.
     * @property unreadableLocations Configured locations that could not be
     *                               read (missing folder, revoked permission,
     *                               unmounted storage). When nonzero, the
     *                               removal phase was skipped so nothing was
     *                               deleted over a read failure.
     */
    data class Done(
        val added: Int,
        val updated: Int,
        val removed: Int,
        val skippedFiles: Int = 0,
        val unreadableLocations: Int = 0,
    ) : ScanState
}
