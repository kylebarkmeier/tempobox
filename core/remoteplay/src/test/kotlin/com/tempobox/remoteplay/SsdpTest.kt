package com.tempobox.remoteplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SsdpTest {

    @Test
    fun `search message is a well-formed M-SEARCH`() {
        val message = Ssdp.searchMessage(Ssdp.ST_MEDIA_RENDERER, mxSeconds = 2)
        assertThat(message).startsWith("M-SEARCH * HTTP/1.1\r\n")
        assertThat(message).contains("HOST: 239.255.255.250:1900\r\n")
        assertThat(message).contains("MAN: \"ssdp:discover\"\r\n")
        assertThat(message).contains("MX: 2\r\n")
        assertThat(message).contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n")
        assertThat(message).endsWith("\r\n\r\n")
    }

    @Test
    fun `parses a search reply`() {
        val reply = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "LOCATION: http://192.168.1.50:8080/description.xml\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "USN: uuid:abcd-1234::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "\r\n"
        val parsed = Ssdp.parseAnnouncement(reply)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.location).isEqualTo("http://192.168.1.50:8080/description.xml")
        assertThat(parsed.usn).startsWith("uuid:abcd-1234")
        assertThat(parsed.searchTarget).isEqualTo(Ssdp.ST_MEDIA_RENDERER)
    }

    @Test
    fun `header names parse case-insensitively`() {
        val reply = "HTTP/1.1 200 OK\r\nLocation: http://10.0.0.2/d.xml\r\nUsn: uuid:x\r\n\r\n"
        assertThat(Ssdp.parseAnnouncement(reply)?.location).isEqualTo("http://10.0.0.2/d.xml")
    }

    @Test
    fun `parses a NOTIFY alive and uses NT as the search target`() {
        val notify = "NOTIFY * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "NT: urn:schemas-upnp-org:device:MediaServer:1\r\n" +
            "NTS: ssdp:alive\r\n" +
            "LOCATION: http://192.168.1.9/desc.xml\r\n" +
            "USN: uuid:server-1\r\n\r\n"
        val parsed = Ssdp.parseAnnouncement(notify)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.searchTarget).isEqualTo(Ssdp.ST_MEDIA_SERVER)
    }

    @Test
    fun `NOTIFY byebye is dropped`() {
        val notify = "NOTIFY * HTTP/1.1\r\n" +
            "NT: urn:schemas-upnp-org:device:MediaServer:1\r\n" +
            "NTS: ssdp:byebye\r\n" +
            "LOCATION: http://192.168.1.9/desc.xml\r\n" +
            "USN: uuid:server-1\r\n\r\n"
        assertThat(Ssdp.parseAnnouncement(notify)).isNull()
    }

    @Test
    fun `non-http locations and garbage are dropped`() {
        assertThat(Ssdp.parseAnnouncement("HTTP/1.1 200 OK\r\nLOCATION: file:///etc/passwd\r\n\r\n"))
            .isNull()
        assertThat(Ssdp.parseAnnouncement("M-SEARCH * HTTP/1.1\r\nST: ssdp:all\r\n\r\n")).isNull()
        assertThat(Ssdp.parseAnnouncement("HTTP/1.1 200 OK\r\n\r\n")).isNull()
        assertThat(Ssdp.parseAnnouncement("")).isNull()
        assertThat(Ssdp.parseAnnouncement("\u0000\u0001binary")).isNull()
    }
}
