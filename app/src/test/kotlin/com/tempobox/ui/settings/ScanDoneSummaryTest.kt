package com.tempobox.ui.settings

import com.google.common.truth.Truth.assertThat
import com.tempobox.library.ScanState
import org.junit.Test

/** The Settings scan-status line must not hide skips or unreadable folders. */
class ScanDoneSummaryTest {

    @Test
    fun `clean scan reads as before`() {
        assertThat(scanDoneSummary(ScanState.Done(added = 3, updated = 1, removed = 2)))
            .isEqualTo("Last scan: +3 added, 1 updated, −2 removed")
    }

    @Test
    fun `skipped files are named`() {
        assertThat(scanDoneSummary(ScanState.Done(1, 0, 0, skippedFiles = 4)))
            .contains("4 unreadable file(s) skipped")
    }

    @Test
    fun `unreadable locations are named and the no-removal guarantee is stated`() {
        val line = scanDoneSummary(
            ScanState.Done(0, 0, 0, skippedFiles = 0, unreadableLocations = 1),
        )
        assertThat(line).contains("1 location(s) unreadable")
        assertThat(line).contains("nothing was removed")
    }
}
