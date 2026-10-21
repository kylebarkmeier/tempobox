package com.tempobox.remoteplay

import java.net.URI

/**
 * A UPnP device parsed from its description XML. One physical box can be both
 * a renderer (AVTransport) and a server (ContentDirectory); the two sides are
 * carried as independent control URLs.
 */
data class UpnpDevice(
    /** UDN, e.g. "uuid:...": the stable identity across rediscoveries. */
    val udn: String,
    val friendlyName: String,
    /** URL the description was fetched from (control URLs resolve against it). */
    val location: String,
    val avTransportControlUrl: String? = null,
    val renderingControlUrl: String? = null,
    val contentDirectoryControlUrl: String? = null,
) {
    val isRenderer: Boolean get() = avTransportControlUrl != null
    val isServer: Boolean get() = contentDirectoryControlUrl != null
}

/**
 * Parses a UPnP device-description document (the XML behind an SSDP LOCATION
 * header) into [UpnpDevice]. Scans every `<service>` in the document, embedded
 * sub-devices included, so combined server+renderer boxes expose both sides.
 */
object DeviceDescription {

    private const val SERVICE_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:"
    private const val SERVICE_RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:"
    private const val SERVICE_CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:"

    /** Null when the XML is unparseable or carries no usable device. */
    fun parse(xml: String, locationUrl: String): UpnpDevice? {
        val doc = SecureXml.parse(xml) ?: return null
        val device = SecureXml.elements(doc, "device").firstOrNull() ?: return null
        val udn = SecureXml.text(device, "UDN") ?: return null
        val friendlyName = SecureXml.text(device, "friendlyName") ?: udn

        // Deprecated but still emitted by some devices; wins over the location
        // URL as the base for relative control URLs when present.
        val base = SecureXml.elements(doc, "URLBase").firstOrNull()
            ?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            ?: locationUrl

        var avTransport: String? = null
        var renderingControl: String? = null
        var contentDirectory: String? = null
        for (service in SecureXml.elements(doc, "service")) {
            val type = SecureXml.text(service, "serviceType") ?: continue
            val control = SecureXml.text(service, "controlURL") ?: continue
            val resolved = resolveUrl(base, control) ?: continue
            when {
                type.startsWith(SERVICE_AV_TRANSPORT) && avTransport == null ->
                    avTransport = resolved
                type.startsWith(SERVICE_RENDERING_CONTROL) && renderingControl == null ->
                    renderingControl = resolved
                type.startsWith(SERVICE_CONTENT_DIRECTORY) && contentDirectory == null ->
                    contentDirectory = resolved
            }
        }
        if (avTransport == null && contentDirectory == null) return null
        return UpnpDevice(
            udn = udn,
            friendlyName = friendlyName,
            location = locationUrl,
            avTransportControlUrl = avTransport,
            renderingControlUrl = renderingControl,
            contentDirectoryControlUrl = contentDirectory,
        )
    }

    /** Resolves a (possibly relative) control URL against [base]; http(s) only. */
    fun resolveUrl(base: String, url: String): String? = try {
        val resolved = URI(base).resolve(url).toString()
        resolved.takeIf {
            it.startsWith("http://", ignoreCase = true) ||
                it.startsWith("https://", ignoreCase = true)
        }
    } catch (_: Exception) {
        null
    }
}
