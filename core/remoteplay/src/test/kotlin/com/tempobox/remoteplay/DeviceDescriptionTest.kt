package com.tempobox.remoteplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceDescriptionTest {

    private fun rendererXml(
        urlBase: String? = null,
        controlUrl: String = "/AVTransport/control",
    ) = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          ${urlBase?.let { "<URLBase>$it</URLBase>" }.orEmpty()}
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>Living Room Speaker</friendlyName>
            <UDN>uuid:renderer-1</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <controlURL>$controlUrl</controlURL>
                <eventSubURL>/AVTransport/event</eventSubURL>
                <SCPDURL>/AVTransport/scpd.xml</SCPDURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
                <controlURL>/RenderingControl/control</controlURL>
                <eventSubURL>/RenderingControl/event</eventSubURL>
                <SCPDURL>/RenderingControl/scpd.xml</SCPDURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent().trim()

    @Test
    fun `parses a renderer with relative control URLs`() {
        val device = DeviceDescription.parse(
            rendererXml(),
            "http://192.168.1.50:8080/description.xml",
        )
        assertThat(device).isNotNull()
        assertThat(device!!.udn).isEqualTo("uuid:renderer-1")
        assertThat(device.friendlyName).isEqualTo("Living Room Speaker")
        assertThat(device.isRenderer).isTrue()
        assertThat(device.isServer).isFalse()
        assertThat(device.avTransportControlUrl)
            .isEqualTo("http://192.168.1.50:8080/AVTransport/control")
        assertThat(device.renderingControlUrl)
            .isEqualTo("http://192.168.1.50:8080/RenderingControl/control")
    }

    @Test
    fun `URLBase wins over the description URL`() {
        val device = DeviceDescription.parse(
            rendererXml(urlBase = "http://192.168.1.50:9999/"),
            "http://192.168.1.50:8080/description.xml",
        )
        assertThat(device!!.avTransportControlUrl)
            .isEqualTo("http://192.168.1.50:9999/AVTransport/control")
    }

    @Test
    fun `absolute control URLs pass through`() {
        val device = DeviceDescription.parse(
            rendererXml(controlUrl = "http://192.168.1.51:1400/ctl"),
            "http://192.168.1.50:8080/description.xml",
        )
        assertThat(device!!.avTransportControlUrl).isEqualTo("http://192.168.1.51:1400/ctl")
    }

    @Test
    fun `parses a media server, embedded sub-devices included`() {
        val xml = """
            <root>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
                <friendlyName>NAS</friendlyName>
                <UDN>uuid:server-1</UDN>
                <deviceList>
                  <device>
                    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                    <friendlyName>NAS Renderer</friendlyName>
                    <UDN>uuid:server-1-renderer</UDN>
                    <serviceList>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                        <controlURL>/av</controlURL>
                      </service>
                    </serviceList>
                  </device>
                </deviceList>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                    <controlURL>/cd</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()
        val device = DeviceDescription.parse(xml, "http://nas.local:5000/desc.xml")
        assertThat(device).isNotNull()
        // Identity comes from the root device; capabilities from anywhere in the doc.
        assertThat(device!!.udn).isEqualTo("uuid:server-1")
        assertThat(device.isServer).isTrue()
        assertThat(device.contentDirectoryControlUrl).isEqualTo("http://nas.local:5000/cd")
        assertThat(device.isRenderer).isTrue()
    }

    @Test
    fun `devices with neither AVTransport nor ContentDirectory are dropped`() {
        val xml = """
            <root><device>
              <friendlyName>Router</friendlyName>
              <UDN>uuid:igd</UDN>
              <serviceList><service>
                <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>
                <controlURL>/wan</controlURL>
              </service></serviceList>
            </device></root>
        """.trimIndent()
        assertThat(DeviceDescription.parse(xml, "http://192.168.1.1/desc.xml")).isNull()
    }

    @Test
    fun `garbage and doctype-bearing XML parse to null, never throw`() {
        assertThat(DeviceDescription.parse("not xml at all", "http://x/d.xml")).isNull()
        assertThat(DeviceDescription.parse("", "http://x/d.xml")).isNull()
        val withDoctype = "<?xml version=\"1.0\"?><!DOCTYPE root [<!ENTITY e SYSTEM " +
            "\"file:///etc/passwd\">]><root><device><UDN>uuid:x</UDN></device></root>"
        assertThat(DeviceDescription.parse(withDoctype, "http://x/d.xml")).isNull()
    }
}
