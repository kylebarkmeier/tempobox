package com.tempobox.playlist

import com.google.common.truth.Truth.assertThat
import com.tempobox.model.Track
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class M3uCodecTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val codec = M3uCodec()

    private fun track(path: String, title: String = "T", artist: String = "A", seconds: Long = 61) =
        Track(filePath = path, title = title, artist = artist, durationMs = seconds * 1000)

    /**
     * Host-portable expected path: parse() returns File(...).normalize().absolutePath,
     * which uses the host separator (and drive letter on Windows). Building the
     * expectation through the same API keeps these tests green on any dev OS --
     * on Android/CI (Linux) this is an identity function.
     */
    private fun abs(path: String): String = File(path).normalize().absolutePath

    // ------------------------------------------------------------------ parsing

    @Test
    fun `parse skips directives and blank lines`() {
        val a = abs("/music/a.mp3")
        val b = abs("/music/b.flac")
        val content = """
            #EXTM3U
            #EXTINF:123,Artist - Title

            $a
            #comment
            $b
        """.trimIndent()
        assertThat(codec.parse(content, File("/base")))
            .containsExactly(a, b)
            .inOrder()
    }

    @Test
    fun `parse resolves relative entries against the playlist folder`() {
        val paths = codec.parse("sub/song.mp3", File("/music/playlists"))
        assertThat(paths).containsExactly(abs("/music/playlists/sub/song.mp3"))
    }

    @Test
    fun `parse normalizes windows separators`() {
        val paths = codec.parse("sub\\song.mp3", File("/music"))
        assertThat(paths).containsExactly(abs("/music/sub/song.mp3"))
    }

    @Test
    fun `parse strips a UTF-8 BOM on the first line`() {
        val a = abs("/music/a.mp3")
        val paths = codec.parse("﻿#EXTM3U\n$a", File("/base"))
        assertThat(paths).containsExactly(a)
    }

    // ------------------------------------------------------------------ read

    @Test
    fun `read m3u8 decodes UTF-8`() {
        val file = tmp.newFile("list.m3u8")
        val p = abs("/music/Füße – práci.mp3")
        file.writeText("#EXTM3U\n$p\n", Charsets.UTF_8)
        assertThat(codec.read(file)).containsExactly(p)
    }

    @Test
    fun `read legacy m3u falls back to latin-1 for invalid UTF-8`() {
        val file = tmp.newFile("legacy.m3u")
        val p = abs("/music/café.mp3")
        file.writeBytes(p.toByteArray(Charsets.ISO_8859_1))
        assertThat(codec.read(file)).containsExactly(p)
    }

    // ------------------------------------------------------------------ writing

    @Test
    fun `serialize emits EXTM3U header and EXTINF lines`() {
        val out = codec.serialize(
            listOf(track("/music/a.mp3", title = "Song", artist = "Band", seconds = 200)),
            baseDir = File("/other"),
        )
        assertThat(out).startsWith("#EXTM3U\n")
        assertThat(out).contains("#EXTINF:200,Band - Song")
        assertThat(out).contains("/music/a.mp3")
    }

    @Test
    fun `serialize writes paths relative to the playlist folder when possible`() {
        val musicDir = tmp.newFolder("music")
        val outside = abs("/elsewhere/b.mp3")
        val out = codec.serialize(
            listOf(track("${musicDir.absolutePath}/rock/a.mp3"), track(outside)),
            baseDir = musicDir,
        )
        assertThat(out).contains("\nrock/a.mp3")
        assertThat(out).contains("\n$outside")
    }

    @Test
    fun `write only accepts m3u8 targets`() {
        val bad = File(tmp.root, "list.m3u")
        assertThrows(IllegalArgumentException::class.java) {
            codec.write(bad, listOf(track("/music/a.mp3")))
        }
    }

    @Test
    fun `write then read round-trips`() {
        val musicDir = tmp.newFolder("music")
        val file = File(musicDir, "mix.m3u8")
        val tracks = listOf(
            track("${musicDir.absolutePath}/one.mp3", title = "Öne"),
            track(abs("/absolute/two.flac")),
        )
        codec.write(file, tracks)
        assertThat(codec.read(file))
            .containsExactly(abs("${musicDir.absolutePath}/one.mp3"), abs("/absolute/two.flac"))
            .inOrder()
    }

    @Test
    fun `ensureM3u8Path upgrades legacy extensions only`() {
        assertThat(codec.ensureM3u8Path("/p/list.m3u")).isEqualTo("/p/list.m3u8")
        assertThat(codec.ensureM3u8Path("/p/list.M3U8")).isEqualTo("/p/list.M3U8")
        assertThat(codec.ensureM3u8Path("/p/list.m3u8")).isEqualTo("/p/list.m3u8")
    }
}
