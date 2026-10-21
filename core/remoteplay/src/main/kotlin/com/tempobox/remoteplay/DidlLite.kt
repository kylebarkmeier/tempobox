package com.tempobox.remoteplay

/**
 * DIDL-Lite in both directions: building the metadata document a renderer
 * gets with SetAVTransportURI, and parsing the documents a server's Browse
 * action returns.
 */
object DidlLite {

    private const val NS = "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
        "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\""

    /**
     * Metadata for one audio track, handed to a renderer alongside its stream
     * URL. DLNA.ORG_OP=01 advertises HTTP Range support (needed for seeking).
     */
    fun trackMetadata(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        url: String,
        mimeType: String,
    ): String = buildString {
        append("<DIDL-Lite $NS>")
        append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
        append("<dc:title>").append(Soap.escape(title)).append("</dc:title>")
        if (artist.isNotBlank()) {
            append("<upnp:artist>").append(Soap.escape(artist)).append("</upnp:artist>")
            append("<dc:creator>").append(Soap.escape(artist)).append("</dc:creator>")
        }
        if (album.isNotBlank()) {
            append("<upnp:album>").append(Soap.escape(album)).append("</upnp:album>")
        }
        append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
        append("<res protocolInfo=\"http-get:*:$mimeType:DLNA.ORG_OP=01\"")
        if (durationMs > 0) append(" duration=\"").append(Soap.formatTime(durationMs)).append("\"")
        append(">").append(Soap.escape(url)).append("</res>")
        append("</item>")
        append("</DIDL-Lite>")
    }

    /** One entry of a server's Browse result. */
    sealed interface Entry {
        /** A browsable container (folder, album, artist node...). */
        data class Folder(
            val id: String,
            val title: String,
            val childCount: Int?,
        ) : Entry

        /** A playable audio item with its HTTP resource. */
        data class Audio(
            val id: String,
            val title: String,
            val artist: String,
            val album: String,
            val durationMs: Long,
            val url: String,
            val mimeType: String?,
        ) : Entry
    }

    /**
     * Parses the DIDL-Lite document inside a Browse response's Result argument.
     * Unusable entries (no id/title, items without an http resource) are
     * dropped, not thrown on: server metadata is untrusted input.
     */
    fun parseEntries(didlXml: String): List<Entry> {
        val doc = SecureXml.parse(didlXml) ?: return emptyList()
        val entries = ArrayList<Entry>()
        for (container in SecureXml.elements(doc, "container")) {
            val id = container.getAttribute("id").takeIf { it.isNotEmpty() } ?: continue
            val title = SecureXml.text(container, "dc:title") ?: continue
            entries += Entry.Folder(
                id = id,
                title = title,
                childCount = container.getAttribute("childCount").toIntOrNull(),
            )
        }
        for (item in SecureXml.elements(doc, "item")) {
            val id = item.getAttribute("id").takeIf { it.isNotEmpty() } ?: continue
            val title = SecureXml.text(item, "dc:title") ?: continue
            val upnpClass = SecureXml.text(item, "upnp:class").orEmpty()
            // Take the first http-get resource; servers may list several
            // (transcodes, thumbnails). Non-audio items are skipped unless the
            // resource itself is an audio MIME (some servers omit upnp:class).
            val res = SecureXml.elements(item, "res").firstOrNull { element ->
                element.getAttribute("protocolInfo").startsWith("http-get", ignoreCase = true)
            } ?: continue
            val protocolInfo = res.getAttribute("protocolInfo")
            val mime = protocolInfo.split(":").getOrNull(2)?.takeIf { it.isNotEmpty() && it != "*" }
            val isAudio = upnpClass.startsWith("object.item.audioItem") ||
                mime?.startsWith("audio/") == true
            if (!isAudio) continue
            val url = res.textContent?.trim().orEmpty()
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) continue
            entries += Entry.Audio(
                id = id,
                title = title,
                artist = SecureXml.text(item, "upnp:artist")
                    ?: SecureXml.text(item, "dc:creator").orEmpty(),
                album = SecureXml.text(item, "upnp:album").orEmpty(),
                durationMs = Soap.parseTime(res.getAttribute("duration")) ?: 0,
                url = url,
                mimeType = mime,
            )
        }
        return entries
    }
}
