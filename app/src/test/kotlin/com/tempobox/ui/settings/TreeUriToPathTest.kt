package com.tempobox.ui.settings

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TreeUriToPathTest {

    private fun treeUri(docId: String): Uri =
        Uri.parse("content://com.android.externalstorage.documents/tree/" + Uri.encode(docId))

    @Test
    fun `primary volume maps to external storage`() {
        assertThat(treeUriToPath(treeUri("primary:Music")))
            .endsWith("/Music")
    }

    @Test
    fun `sd card volumes map to storage mount`() {
        assertThat(treeUriToPath(treeUri("1D04-330A:Music/FLAC")))
            .isEqualTo("/storage/1D04-330A/Music/FLAC")
    }

    @Test
    fun `volume root maps without trailing segment`() {
        assertThat(treeUriToPath(treeUri("1D04-330A:")))
            .isEqualTo("/storage/1D04-330A")
    }

    @Test
    fun `non-tree uris return null`() {
        assertThat(treeUriToPath(Uri.parse("content://media/external/audio/1"))).isNull()
    }
}
