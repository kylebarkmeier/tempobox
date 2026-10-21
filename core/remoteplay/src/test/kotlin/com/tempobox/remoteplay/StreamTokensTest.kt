package com.tempobox.remoteplay

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StreamTokensTest {

    @get:Rule val tmp = TemporaryFolder()

    private val tokens = StreamTokens()

    @Test
    fun `registered file resolves by its token path only`() {
        val file = tmp.newFile("track.mp3").apply { writeText("audio") }
        val token = tokens.register(file)
        assertThat(token).isNotNull()
        assertThat(token).matches("[0-9a-f]{32}")
        assertThat(tokens.resolve("/$token")?.canonicalPath).isEqualTo(file.canonicalPath)
    }

    @Test
    fun `re-registering the same file reuses its token`() {
        val file = tmp.newFile("track.mp3")
        assertThat(tokens.register(file)).isEqualTo(tokens.register(file))
    }

    @Test
    fun `missing files and directories do not register`() {
        assertThat(tokens.register(tmp.root.resolve("nope.mp3"))).isNull()
        assertThat(tokens.register(tmp.root)).isNull()
    }

    @Test
    fun `traversal and listing probes resolve to nothing`() {
        val file = tmp.newFile("track.mp3")
        val token = tokens.register(file)!!
        listOf(
            "/",
            "",
            "/$token/",
            "/$token/../other",
            "/../$token",
            "/..%2f..%2fetc%2fpasswd",
            "/etc/passwd",
            "/${token.uppercase()}",
            "/${token.dropLast(1)}",
            "/$token-extra",
            "/${"0".repeat(32)}",
            file.absolutePath,
        ).forEach { path ->
            assertThat(tokens.resolve(path)).isNull()
        }
    }

    @Test
    fun `clear revokes every token`() {
        val token = tokens.register(tmp.newFile("track.mp3"))!!
        tokens.clear()
        assertThat(tokens.resolve("/$token")).isNull()
    }
}
