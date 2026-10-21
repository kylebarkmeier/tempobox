package com.tempobox.remoteplay

import java.util.Locale

/**
 * SOAP envelope building and response parsing for UPnP control actions
 * (AVTransport, RenderingControl, ContentDirectory). Pure string/XML work;
 * the HTTP POSTs live in [UpnpControl].
 */
object Soap {

    const val SERVICE_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val SERVICE_RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val SERVICE_CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:1"

    /** Value for the SOAPACTION header (quotes included, per UPnP). */
    fun actionHeader(serviceType: String, action: String): String = "\"$serviceType#$action\""

    /** The request body for one control action. Argument values are escaped here. */
    fun envelope(serviceType: String, action: String, arguments: List<Pair<String, String>>): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append(
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                    "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">",
            )
            append("<s:Body>")
            append("<u:$action xmlns:u=\"$serviceType\">")
            for ((name, value) in arguments) {
                append("<$name>").append(escape(value)).append("</$name>")
            }
            append("</u:$action>")
            append("</s:Body>")
            append("</s:Envelope>")
        }

    /** XML-escapes an element value (DIDL metadata, URLs, titles from tags). */
    fun escape(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    /**
     * Parses a SOAP response body into the action's out-arguments, keyed by
     * element name. Null for faults and unparseable bodies (untrusted input;
     * callers treat null as a failed command).
     */
    fun parseResponse(body: String): Map<String, String>? {
        val doc = SecureXml.parse(body) ?: return null
        val all = SecureXml.elements(doc, "*")
        // Faults arrive with HTTP 500, but some devices send 200 + Fault.
        if (all.any { it.tagName == "Fault" || it.tagName.endsWith(":Fault") }) return null
        val response = all.firstOrNull { it.tagName.endsWith("Response") } ?: return null
        val result = LinkedHashMap<String, String>()
        val children = response.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? org.w3c.dom.Element ?: continue
            result[child.tagName] = child.textContent.orEmpty()
        }
        return result
    }

    /** Millis → "H:MM:SS" (AVTransport REL_TIME / seek target format). */
    fun formatTime(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0) / 1000
        return String.format(
            Locale.US,
            "%d:%02d:%02d",
            totalSeconds / 3600,
            (totalSeconds % 3600) / 60,
            totalSeconds % 60,
        )
    }

    /**
     * "H:MM:SS[.fff]" → millis. Null for blanks and the "NOT_IMPLEMENTED" some
     * renderers report instead of a position.
     */
    fun parseTime(value: String?): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty() || text.equals("NOT_IMPLEMENTED", ignoreCase = true)) return null
        val parts = text.substringBefore('.').split(":")
        if (parts.isEmpty() || parts.size > 3) return null
        var seconds = 0L
        for (part in parts) {
            val n = part.toLongOrNull() ?: return null
            if (n < 0) return null
            seconds = seconds * 60 + n
        }
        val fraction = text.substringAfter('.', "").takeIf { it.isNotEmpty() }
            ?.let { ("0.$it").toDoubleOrNull() } ?: 0.0
        return seconds * 1000 + (fraction * 1000).toLong()
    }
}
