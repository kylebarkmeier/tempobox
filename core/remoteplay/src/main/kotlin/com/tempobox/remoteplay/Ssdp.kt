package com.tempobox.remoteplay

/**
 * SSDP (Simple Service Discovery Protocol) message building and parsing: the
 * UDP multicast half of UPnP discovery. Pure string work so it unit-tests on
 * the JVM; the sockets live in [SsdpDiscovery].
 */
object Ssdp {

    const val MULTICAST_ADDRESS = "239.255.255.250"
    const val PORT = 1900

    /** Search target for devices we can play TO (DLNA renderers). */
    const val ST_MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    /** Search target for devices we can play FROM (DLNA servers, e.g. a NAS). */
    const val ST_MEDIA_SERVER = "urn:schemas-upnp-org:device:MediaServer:1"

    /** An M-SEARCH request for [searchTarget]; replies arrive unicast. */
    fun searchMessage(searchTarget: String, mxSeconds: Int = 2): String = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $MULTICAST_ADDRESS:$PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: $mxSeconds\r\n")
        append("ST: $searchTarget\r\n")
        append("\r\n")
    }

    /**
     * A device announcement: either an M-SEARCH reply or a NOTIFY ssdp:alive.
     * [location] is the device-description URL, [usn] the unique service name.
     */
    data class Announcement(
        val location: String,
        val usn: String,
        val searchTarget: String,
    )

    /**
     * Parses one received datagram. Returns null for anything that is not a
     * usable announcement (NOTIFY byebye, M-SEARCHes from others, garbage):
     * SSDP traffic is untrusted input, so unparseable messages are dropped,
     * never thrown on.
     */
    fun parseAnnouncement(message: String): Announcement? {
        val lines = message.split("\r\n", "\n")
        val startLine = lines.firstOrNull()?.trim().orEmpty()
        val isSearchReply = startLine.startsWith("HTTP/1.1 200", ignoreCase = true)
        val isNotify = startLine.startsWith("NOTIFY", ignoreCase = true)
        if (!isSearchReply && !isNotify) return null

        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        if (isNotify && !"ssdp:alive".equals(headers["nts"], ignoreCase = true)) return null

        val location = headers["location"] ?: return null
        if (!location.startsWith("http://", ignoreCase = true) &&
            !location.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return Announcement(
            location = location,
            usn = headers["usn"] ?: location,
            searchTarget = headers["st"] ?: headers["nt"].orEmpty(),
        )
    }
}
