package com.tempobox.remoteplay

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Picks the phone's LAN address: what goes into the stream URLs a renderer
 * fetches. The choice logic is pure ([pick]) so it unit-tests without real
 * interfaces; [current] does the live enumeration.
 */
object WlanAddress {

    data class Candidate(val interfaceName: String, val address: InetAddress)

    /**
     * Prefers Wi-Fi over everything (renderers live on the WLAN), then
     * ethernet (ADB over LAN, DeX docks), then any remaining site-local IPv4.
     */
    fun pick(candidates: List<Candidate>): Candidate? {
        val usable = candidates.filter {
            it.address is Inet4Address && it.address.isSiteLocalAddress &&
                !it.address.isLoopbackAddress
        }
        return usable.firstOrNull { it.interfaceName.startsWith("wlan") }
            ?: usable.firstOrNull { it.interfaceName.startsWith("eth") }
            ?: usable.firstOrNull()
    }

    /** The live LAN address, or null when the phone has none (no Wi-Fi). */
    fun current(): InetAddress? = try {
        val candidates = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic ->
                nic.inetAddresses.asSequence().map { Candidate(nic.name, it) }
            }
            .toList()
        pick(candidates)?.address
    } catch (_: Exception) {
        null
    }
}
