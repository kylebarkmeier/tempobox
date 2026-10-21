package com.tempobox.remoteplay

/**
 * Typed AVTransport + RenderingControl calls against one renderer. Thin layer
 * over [UpnpControl]: argument plumbing and response field extraction only,
 * so it tests against a MockWebServer with canned SOAP bodies.
 */
class RendererClient(
    private val upnp: UpnpControl,
    val device: UpnpDevice,
) {
    private val avTransport = requireNotNull(device.avTransportControlUrl) {
        "${device.friendlyName} is not a renderer"
    }

    data class PositionInfo(val positionMs: Long?, val durationMs: Long?)

    /** Loads [url] (with DIDL-Lite [metadata]) as the current transport URI. */
    suspend fun setUri(url: String, metadata: String): Boolean =
        avAction(
            "SetAVTransportURI",
            listOf("CurrentURI" to url, "CurrentURIMetaData" to metadata),
        ) != null

    suspend fun play(): Boolean = avAction("Play", listOf("Speed" to "1")) != null

    suspend fun pause(): Boolean = avAction("Pause") != null

    suspend fun stop(): Boolean = avAction("Stop") != null

    suspend fun seek(positionMs: Long): Boolean =
        avAction(
            "Seek",
            listOf("Unit" to "REL_TIME", "Target" to Soap.formatTime(positionMs)),
        ) != null

    /** AVTransport CurrentTransportState ("PLAYING", "STOPPED"...), null on failure. */
    suspend fun transportState(): String? =
        avAction("GetTransportInfo")?.get("CurrentTransportState")

    suspend fun position(): PositionInfo? {
        val response = avAction("GetPositionInfo") ?: return null
        return PositionInfo(
            positionMs = Soap.parseTime(response["RelTime"]),
            durationMs = Soap.parseTime(response["TrackDuration"]),
        )
    }

    /** Renderer volume 0..100 via RenderingControl; null when unsupported. */
    suspend fun volume(): Int? {
        val url = device.renderingControlUrl ?: return null
        return upnp.action(
            url,
            Soap.SERVICE_RENDERING_CONTROL,
            "GetVolume",
            listOf("InstanceID" to "0", "Channel" to "Master"),
        )?.get("CurrentVolume")?.toIntOrNull()
    }

    suspend fun setVolume(percent: Int): Boolean {
        val url = device.renderingControlUrl ?: return false
        return upnp.action(
            url,
            Soap.SERVICE_RENDERING_CONTROL,
            "SetVolume",
            listOf(
                "InstanceID" to "0",
                "Channel" to "Master",
                "DesiredVolume" to percent.coerceIn(0, 100).toString(),
            ),
        ) != null
    }

    private suspend fun avAction(
        action: String,
        arguments: List<Pair<String, String>> = emptyList(),
    ): Map<String, String>? = upnp.action(
        avTransport,
        Soap.SERVICE_AV_TRANSPORT,
        action,
        listOf("InstanceID" to "0") + arguments,
    )
}
