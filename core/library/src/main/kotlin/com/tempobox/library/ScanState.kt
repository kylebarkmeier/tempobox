package com.tempobox.library

/** Progress of a library scan, observed by Settings and the Library views. */
sealed interface ScanState {
    /** No scan running. */
    data object Idle : ScanState

    /** Enumerating files / reading tags. [total] is 0 while still counting. */
    data class Scanning(val scanned: Int, val total: Int) : ScanState

    /** Last scan's outcome (kept until the next scan starts). */
    data class Done(val added: Int, val updated: Int, val removed: Int) : ScanState
}
