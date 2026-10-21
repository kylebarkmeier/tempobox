package com.tempobox.remoteplay

import com.google.common.truth.Truth.assertThat
import com.tempobox.remoteplay.WlanAddress.Candidate
import org.junit.Test
import java.net.InetAddress

class WlanAddressTest {

    private fun candidate(name: String, ip: String) =
        Candidate(name, InetAddress.getByName(ip))

    @Test
    fun `prefers wlan over ethernet over anything`() {
        val picked = WlanAddress.pick(
            listOf(
                candidate("rmnet0", "10.64.0.3"),
                candidate("eth0", "192.168.1.7"),
                candidate("wlan0", "192.168.1.23"),
            ),
        )
        assertThat(picked?.interfaceName).isEqualTo("wlan0")
    }

    @Test
    fun `falls back to ethernet when wifi is off`() {
        val picked = WlanAddress.pick(
            listOf(
                candidate("rmnet0", "10.64.0.3"),
                candidate("eth0", "192.168.1.7"),
            ),
        )
        assertThat(picked?.interfaceName).isEqualTo("eth0")
    }

    @Test
    fun `skips loopback, IPv6 and public addresses`() {
        val picked = WlanAddress.pick(
            listOf(
                candidate("lo", "127.0.0.1"),
                candidate("wlan0", "fe80::1"),
                candidate("tun0", "8.8.8.8"),
            ),
        )
        assertThat(picked).isNull()
    }

    @Test
    fun `empty input picks nothing`() {
        assertThat(WlanAddress.pick(emptyList())).isNull()
    }
}
