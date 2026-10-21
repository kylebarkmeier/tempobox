package com.tempobox.remoteplay

/**
 * Single-range parsing for the stream server's Range support (renderers seek
 * by re-requesting with a byte range; DLNA.ORG_OP=01 promises we honor it).
 */
object HttpRanges {

    sealed interface Result {
        /** No (or syntactically ignorable) Range header: serve the whole file. */
        object Whole : Result

        /** Serve [firstByte]..[lastByte] inclusive with 206. */
        data class Partial(val firstByte: Long, val lastByte: Long) : Result

        /** Range lies outside the file: respond 416. */
        object Unsatisfiable : Result
    }

    /**
     * Parses a Range header value against a file of [length] bytes. Follows
     * RFC 9110 for the forms "bytes=a-b", "bytes=a-" and "bytes=-n"; malformed
     * headers and multi-range requests fall back to serving the whole file.
     */
    fun parse(header: String?, length: Long): Result {
        val value = header?.trim() ?: return Result.Whole
        if (!value.startsWith("bytes=")) return Result.Whole
        val spec = value.removePrefix("bytes=").trim()
        if (spec.contains(',')) return Result.Whole // multi-range: not worth it
        val dash = spec.indexOf('-')
        if (dash < 0) return Result.Whole
        val firstText = spec.substring(0, dash).trim()
        val lastText = spec.substring(dash + 1).trim()
        return when {
            firstText.isEmpty() && lastText.isEmpty() -> Result.Whole
            firstText.isEmpty() -> {
                // Suffix form: the last n bytes.
                val suffix = lastText.toLongOrNull() ?: return Result.Whole
                when {
                    suffix <= 0 -> Result.Unsatisfiable
                    length == 0L -> Result.Unsatisfiable
                    else -> Result.Partial((length - suffix).coerceAtLeast(0), length - 1)
                }
            }
            else -> {
                val first = firstText.toLongOrNull() ?: return Result.Whole
                val last = if (lastText.isEmpty()) {
                    length - 1
                } else {
                    lastText.toLongOrNull() ?: return Result.Whole
                }
                when {
                    first >= length || first < 0 -> Result.Unsatisfiable
                    last < first -> Result.Whole // malformed per RFC: ignore
                    else -> Result.Partial(first, last.coerceAtMost(length - 1))
                }
            }
        }
    }
}
