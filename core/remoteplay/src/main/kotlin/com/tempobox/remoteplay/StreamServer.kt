package com.tempobox.remoteplay

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal HTTP/1.1 file server streaming queue tracks to a DLNA renderer.
 *
 * Deliberately not a general web server: it answers GET/HEAD for the token
 * paths registered in [StreamTokens] and nothing else: no listings, no
 * directories, no other methods. Hand-rolled over [ServerSocket] instead of
 * pulling in an embedded-server dependency; the whole protocol surface it
 * needs (request line, Range, 200/206/404/405/416) fits here and is unit
 * tested with plain sockets.
 */
class StreamServer(private val tokens: StreamTokens) {

    @Volatile private var serverSocket: ServerSocket? = null
    private val connectionId = AtomicInteger()

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /** Address the server was started on; null while stopped. */
    @Volatile var boundAddress: InetAddress? = null
        private set

    val port: Int get() = serverSocket?.localPort ?: -1

    /**
     * Starts listening on [address] (the WLAN interface, never all
     * interfaces) on an ephemeral port. Returns the port, or null on failure.
     */
    @Synchronized
    fun start(address: InetAddress): Int? {
        stop()
        return try {
            val socket = ServerSocket(0, BACKLOG, address)
            serverSocket = socket
            boundAddress = address
            Thread({ acceptLoop(socket) }, "tempobox-stream-server").apply {
                isDaemon = true
                start()
            }
            socket.localPort
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start stream server on $address", e)
            serverSocket = null
            boundAddress = null
            null
        }
    }

    @Synchronized
    fun stop() {
        val socket = serverSocket ?: return
        serverSocket = null
        boundAddress = null
        try {
            socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing stream server socket", e)
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val connection = try {
                socket.accept()
            } catch (e: SocketException) {
                if (serverSocket === socket) Log.w(TAG, "Stream server accept failed", e)
                return // closed by stop(), or dead: either way this loop is done
            } catch (e: Exception) {
                Log.w(TAG, "Stream server accept failed", e)
                continue
            }
            Thread(
                { connection.use { handle(it) } },
                "tempobox-stream-${connectionId.incrementAndGet()}",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handle(connection: Socket) {
        try {
            connection.soTimeout = READ_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')

            var rangeHeader: String? = null
            var headerCount = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (++headerCount > MAX_HEADERS) return // oversized request: drop it
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).trim().equals("Range", ignoreCase = true)) {
                    rangeHeader = line.substring(colon + 1).trim()
                }
            }

            val out = connection.getOutputStream()
            if (method != "GET" && method != "HEAD") {
                respondStatus(out, "405 Method Not Allowed", "Allow: GET, HEAD")
                return
            }
            val file = tokens.resolve(path)
            if (file == null) {
                respondStatus(out, "404 Not Found")
                return
            }
            serveFile(out, file, rangeHeader, includeBody = method == "GET")
        } catch (e: Exception) {
            // Renderers drop connections mid-stream routinely (seeks, stop);
            // log and move on, never crash the accept loop.
            Log.w(TAG, "Stream connection failed", e)
        }
    }

    private fun serveFile(out: OutputStream, file: File, rangeHeader: String?, includeBody: Boolean) {
        val length = file.length()
        val mime = AudioMime.forPath(file.path)
        val common = listOf(
            "Accept-Ranges: bytes",
            "Content-Type: $mime",
            // DLNA extras many renderers want before they will stream/seek.
            "transferMode.dlna.org: Streaming",
            "contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0",
            "Connection: close",
        )
        when (val range = HttpRanges.parse(rangeHeader, length)) {
            is HttpRanges.Result.Unsatisfiable -> {
                respondStatus(out, "416 Range Not Satisfiable", "Content-Range: bytes */$length")
            }
            is HttpRanges.Result.Whole -> {
                writeHeaders(out, "200 OK", common + "Content-Length: $length")
                if (includeBody) copyRange(file, out, 0, length - 1)
            }
            is HttpRanges.Result.Partial -> {
                val count = range.lastByte - range.firstByte + 1
                writeHeaders(
                    out,
                    "206 Partial Content",
                    common + listOf(
                        "Content-Length: $count",
                        "Content-Range: bytes ${range.firstByte}-${range.lastByte}/$length",
                    ),
                )
                if (includeBody) copyRange(file, out, range.firstByte, range.lastByte)
            }
        }
        out.flush()
    }

    private fun respondStatus(out: OutputStream, status: String, vararg extraHeaders: String) {
        writeHeaders(out, status, extraHeaders.toList() + listOf("Content-Length: 0", "Connection: close"))
        out.flush()
    }

    private fun writeHeaders(out: OutputStream, status: String, headers: List<String>) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    private fun copyRange(file: File, out: OutputStream, first: Long, last: Long) {
        file.inputStream().use { input ->
            var skipped = 0L
            while (skipped < first) {
                val n = input.skip(first - skipped)
                if (n <= 0) return
                skipped += n
            }
            var remaining = last - first + 1
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) return
                out.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    companion object {
        private const val TAG = "StreamServer"
        private const val BACKLOG = 4
        private const val READ_TIMEOUT_MS = 10_000
        private const val MAX_HEADERS = 64
    }
}
