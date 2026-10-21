package com.tempobox.remoteplay

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * SSDP discovery of DLNA devices (renderers and media servers) on the WLAN.
 *
 * Runs only while something is looking: the output picker or the network
 * browse screen call [start]/[stop] around their visibility, and the
 * [WifiManager.MulticastLock] (CHANGE_WIFI_MULTICAST_STATE, maintainer
 * signed off) is held exactly that long — multicast filtering is a battery
 * drain, so the lock never outlives its screen.
 *
 * M-SEARCH replies arrive unicast; the lock is still required on devices
 * whose Wi-Fi firmware drops multicast NOTIFY traffic, and for the replies
 * themselves on some chipsets.
 */
@javax.inject.Singleton
class SsdpDiscovery(
    context: Context,
    private val upnp: UpnpControl,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @javax.inject.Inject constructor(
        @ApplicationContext context: Context,
        upnp: UpnpControl,
    ) : this(context, upnp, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val multicastLock: WifiManager.MulticastLock by lazy {
        wifiManager.createMulticastLock("tempobox-ssdp").apply { setReferenceCounted(false) }
    }

    private val _devices = MutableStateFlow<List<UpnpDevice>>(emptyList())

    /** Devices currently answering discovery, deduplicated by UDN. */
    val devices: StateFlow<List<UpnpDevice>> = _devices.asStateFlow()

    private var job: Job? = null
    private var starts = 0

    /** Devices by UDN; kept across start/stop so reopening a picker is instant. */
    private val known = LinkedHashMap<String, UpnpDevice>()
    private val failedLocations = HashSet<String>()

    /** Ref-counted: the picker and the browse screen can overlap. */
    @Synchronized
    fun start() {
        starts++
        if (job?.isActive == true) return
        try {
            multicastLock.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire multicast lock; discovery may miss devices", e)
        }
        job = scope.launch { discoverLoop() }
    }

    @Synchronized
    fun stop() {
        if (starts > 0) starts--
        if (starts > 0) return
        job?.cancel()
        job = null
        releaseLock()
    }

    /** Full teardown (service shutdown): no screens are counted anymore. */
    @Synchronized
    fun shutdown() {
        starts = 0
        job?.cancel()
        job = null
        releaseLock()
    }

    private fun releaseLock() {
        try {
            if (multicastLock.isHeld) multicastLock.release()
        } catch (e: Exception) {
            Log.w(TAG, "Could not release multicast lock", e)
        }
    }

    private suspend fun discoverLoop() {
        while (scope.isActive && job?.isActive == true) {
            searchOnce()
        }
    }

    /** One M-SEARCH round: send for both device types, collect replies. */
    private suspend fun searchOnce() {
        val seenThisRound = HashSet<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = RECEIVE_TIMEOUT_MS
                val group = InetAddress.getByName(Ssdp.MULTICAST_ADDRESS)
                for (st in listOf(Ssdp.ST_MEDIA_RENDERER, Ssdp.ST_MEDIA_SERVER)) {
                    val message = Ssdp.searchMessage(st, mxSeconds = MX_SECONDS).toByteArray()
                    socket.send(DatagramPacket(message, message.size, group, Ssdp.PORT))
                }
                val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
                val deadline = System.currentTimeMillis() + ROUND_MS
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                    val announcement = Ssdp.parseAnnouncement(text) ?: continue
                    seenThisRound += announcement.location
                    onAnnouncement(announcement)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP search round failed (no Wi-Fi?)", e)
            kotlinx.coroutines.delay(ROUND_MS.toLong())
        }
        // Forget devices that stopped answering so the picker doesn't offer
        // powered-off boxes forever (one silent round is enough: each round
        // asks twice and renderers answer every ST they match).
        synchronized(this) {
            val gone = known.values.filter { it.location !in seenThisRound }.map { it.udn }
            if (gone.isNotEmpty()) {
                gone.forEach(known::remove)
                _devices.value = known.values.toList()
            }
        }
    }

    private suspend fun onAnnouncement(announcement: Ssdp.Announcement) {
        val alreadyKnown = synchronized(this) {
            known.values.any { it.location == announcement.location }
        }
        if (alreadyKnown || announcement.location in failedLocations) return
        val device = upnp.fetchDevice(announcement.location)
        if (device == null) {
            // Don't hammer a bad description URL every round.
            failedLocations += announcement.location
            return
        }
        synchronized(this) {
            known[device.udn] = device
            _devices.value = known.values.toList()
        }
    }

    /** The parsed device for a picker/browse id. */
    fun device(udn: String): UpnpDevice? = synchronized(this) { known[udn] }

    companion object {
        private const val TAG = "SsdpDiscovery"
        private const val MX_SECONDS = 2
        private const val RECEIVE_TIMEOUT_MS = 1_000
        private const val RECEIVE_BUFFER_BYTES = 4 * 1024
        /** Length of one search round; devices answer within MX + slack. */
        private const val ROUND_MS = 5_000
    }
}
