package com.tempobox.remoteplay

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.Socket

/**
 * Socket-level tests of the stream server: the exact HTTP a renderer sees.
 */
class StreamServerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val tokens = StreamTokens()
    private val server = StreamServer(tokens)
    private lateinit var file: File
    private lateinit var token: String
    private var port = 0

    @Before
    fun setUp() {
        file = tmp.newFile("track.mp3").apply { writeText("0123456789") }
        token = tokens.register(file)!!
        port = server.start(InetAddress.getLoopbackAddress())!!
    }

    @After
    fun tearDown() {
        server.stop()
    }

    /** One request, connection closed after; returns (status line, headers, body). */
    private fun request(raw: String): Triple<String, Map<String, String>, ByteArray> {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(raw.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            val bytes = socket.getInputStream().readBytes()
            val text = String(bytes, Charsets.ISO_8859_1)
            val headerEnd = text.indexOf("\r\n\r\n")
            val headLines = text.substring(0, headerEnd).split("\r\n")
            val headers = headLines.drop(1).associate {
                it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
            }
            return Triple(headLines[0], headers, bytes.copyOfRange(headerEnd + 4, bytes.size))
        }
    }

    private fun get(path: String, vararg headers: String): Triple<String, Map<String, String>, ByteArray> =
        request(
            "GET $path HTTP/1.1\r\nHost: x\r\n" +
                headers.joinToString("") { "$it\r\n" } + "\r\n",
        )

    @Test
    fun `serves a registered token with audio and DLNA headers`() {
        val (status, headers, body) = get("/$token")
        assertThat(status).isEqualTo("HTTP/1.1 200 OK")
        assertThat(headers["content-type"]).isEqualTo("audio/mpeg")
        assertThat(headers["content-length"]).isEqualTo("10")
        assertThat(headers["accept-ranges"]).isEqualTo("bytes")
        assertThat(headers["contentfeatures.dlna.org"]).contains("DLNA.ORG_OP=01")
        assertThat(headers["transfermode.dlna.org"]).isEqualTo("Streaming")
        assertThat(String(body)).isEqualTo("0123456789")
    }

    @Test
    fun `HEAD returns headers without a body`() {
        val (status, headers, body) = request("HEAD /$token HTTP/1.1\r\nHost: x\r\n\r\n")
        assertThat(status).isEqualTo("HTTP/1.1 200 OK")
        assertThat(headers["content-length"]).isEqualTo("10")
        assertThat(body).isEmpty()
    }

    @Test
    fun `range requests answer 206 with the requested bytes`() {
        val (status, headers, body) = get("/$token", "Range: bytes=2-5")
        assertThat(status).isEqualTo("HTTP/1.1 206 Partial Content")
        assertThat(headers["content-range"]).isEqualTo("bytes 2-5/10")
        assertThat(headers["content-length"]).isEqualTo("4")
        assertThat(String(body)).isEqualTo("2345")
    }

    @Test
    fun `open-ended range runs to the end`() {
        val (status, _, body) = get("/$token", "Range: bytes=7-")
        assertThat(status).isEqualTo("HTTP/1.1 206 Partial Content")
        assertThat(String(body)).isEqualTo("789")
    }

    @Test
    fun `unsatisfiable ranges answer 416`() {
        val (status, headers, _) = get("/$token", "Range: bytes=100-")
        assertThat(status).isEqualTo("HTTP/1.1 416 Range Not Satisfiable")
        assertThat(headers["content-range"]).isEqualTo("bytes */10")
    }

    @Test
    fun `unknown tokens, listings and traversal probes answer 404`() {
        listOf(
            "/",
            "/${"0".repeat(32)}",
            "/$token/../${file.name}",
            "/..%2f..%2fetc%2fpasswd",
            "/etc/passwd",
            "/${file.absolutePath}",
        ).forEach { path ->
            val (status, _, _) = get(path)
            assertThat(status).isEqualTo("HTTP/1.1 404 Not Found")
        }
    }

    @Test
    fun `query strings do not reach token matching`() {
        val (status, _, body) = get("/$token?download=1")
        assertThat(status).isEqualTo("HTTP/1.1 200 OK")
        assertThat(String(body)).isEqualTo("0123456789")
    }

    @Test
    fun `non-GET methods answer 405`() {
        val (status, headers, _) = request("POST /$token HTTP/1.1\r\nHost: x\r\n\r\n")
        assertThat(status).isEqualTo("HTTP/1.1 405 Method Not Allowed")
        assertThat(headers["allow"]).isEqualTo("GET, HEAD")
    }

    @Test
    fun `stop closes the listener`() {
        server.stop()
        assertThat(server.isRunning).isFalse()
        val threw = runCatching {
            Socket(InetAddress.getLoopbackAddress(), port).close()
        }.isFailure
        assertThat(threw).isTrue()
    }
}
