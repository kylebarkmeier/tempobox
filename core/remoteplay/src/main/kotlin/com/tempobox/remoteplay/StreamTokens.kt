package com.tempobox.remoteplay

import java.io.File
import java.security.SecureRandom

/**
 * Maps unguessable URL tokens to the local files a renderer session may fetch.
 *
 * This is the stream server's entire authorization model: only files
 * explicitly registered from the current queue are reachable, each under a
 * random 128-bit token, so there is nothing to browse and no path from the
 * request to the filesystem. Request paths never touch file paths: a lookup
 * either hits a registered token exactly or serves nothing (audio paths are
 * untrusted input per the repo safety rules).
 */
class StreamTokens(private val random: SecureRandom = SecureRandom()) {

    private val fileByToken = HashMap<String, File>()
    private val tokenByPath = HashMap<String, String>()

    /**
     * Registers [file] and returns its token (stable while registered).
     * Null when the file does not resolve to a readable regular file.
     */
    @Synchronized
    fun register(file: File): String? {
        val canonical = try {
            file.canonicalFile
        } catch (_: Exception) {
            return null
        }
        if (!canonical.isFile || !canonical.canRead()) return null
        tokenByPath[canonical.path]?.let { return it }
        val token = newToken()
        fileByToken[token] = canonical
        tokenByPath[canonical.path] = token
        return token
    }

    /**
     * The file for a raw HTTP request path, or null. Accepts exactly
     * "/{32 hex chars}"; anything else (traversal attempts, queries, listing
     * probes) resolves to nothing.
     */
    @Synchronized
    fun resolve(requestPath: String): File? {
        if (!TOKEN_PATH.matches(requestPath)) return null
        return fileByToken[requestPath.substring(1)]
    }

    @Synchronized
    fun clear() {
        fileByToken.clear()
        tokenByPath.clear()
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val TOKEN_PATH = Regex("/[0-9a-f]{32}")
    }
}
