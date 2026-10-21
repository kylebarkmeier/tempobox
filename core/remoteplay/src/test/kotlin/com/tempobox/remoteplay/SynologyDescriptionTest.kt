package com.tempobox.remoteplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Regression: the description a Synology DSM media server actually serves. */
class SynologyDescriptionTest {

    @Test
    fun `synology media server description parses as a server`() {
        val device = DeviceDescription.parse(SYNOLOGY_XML, "http://10.0.0.2:50001/desc/device.xml")
        assertThat(device).isNotNull()
        assertThat(device!!.udn).isEqualTo("uuid:0011327b-91dd-0011-dd91-dd917b321100")
        assertThat(device.friendlyName).isEqualTo("Infosphere")
        assertThat(device.isServer).isTrue()
        assertThat(device.contentDirectoryControlUrl)
            .isEqualTo("http://10.0.0.2:50001/ContentDirectory/control")
        assertThat(device.isRenderer).isFalse()
    }

    companion object {
        private val SYNOLOGY_XML = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
            <specVersion>
            	<major>1</major>
            	<minor>0</minor>
            </specVersion>
            <device>
            	<deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
            	<friendlyName>Infosphere</friendlyName>
            	<manufacturer>Synology Inc.</manufacturer>
            	<manufacturerURL>http://www.synology.com/</manufacturerURL>
            	<modelName>RS815+</modelName>
            	<modelDescription>Synology DLNA/UPnP Media Server</modelDescription>
            	<modelURL>http://www.synology.com</modelURL>
            	<serialNumber>1781MRN321300</serialNumber>
            	<UDN>uuid:0011327b-91dd-0011-dd91-dd917b321100</UDN>
            	<presentationURL>http://10.0.0.2:5000/</presentationURL>
                <iconList>
                    <icon>
                        <mimetype>image/jpeg</mimetype>
                        <width>120</width>
                        <height>120</height>
                        <depth>24</depth>
                        <url>/tmp_icon/dmsicon120.jpg</url>
                    </icon>
                </iconList>
            	<serviceList>
            		<service>
            			<serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
            			<serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
            			<SCPDURL>/connmgrSCPD.xml</SCPDURL>
            			<controlURL>/ConnectionManager/control</controlURL>
            			<eventSubURL>/ConnectionManager/event</eventSubURL>
            		</service>
            		<service>
            			<serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
            			<serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
            			<SCPDURL>/cdsxSCPD.xml</SCPDURL>
            			<controlURL>/ContentDirectory/control</controlURL>
            			<eventSubURL>/ContentDirectory/event</eventSubURL>
            		</service>
            		<service>
            			<serviceType>urn:microsoft.com:service:X_MS_MediaReceiverRegistrar:1</serviceType>
            			<serviceId>urn:microsoft.com:serviceId:X_MS_MediaReceiverRegistrar</serviceId>
            			<SCPDURL>/msrrSCPD.xml</SCPDURL>
            			<eventSubURL>/MediaReceiverRegistrar/event</eventSubURL>
            			<controlURL>/MediaReceiverRegistrar/control</controlURL>
            		</service>
            	</serviceList>
            </device>
            </root>
        """.trimIndent()
    }
}
