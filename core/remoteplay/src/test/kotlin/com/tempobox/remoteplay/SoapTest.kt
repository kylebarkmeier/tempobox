package com.tempobox.remoteplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SoapTest {

    @Test
    fun `envelope wraps the action and escapes argument values`() {
        val body = Soap.envelope(
            Soap.SERVICE_AV_TRANSPORT,
            "SetAVTransportURI",
            listOf(
                "InstanceID" to "0",
                "CurrentURI" to "http://192.168.1.2:8000/x?a=1&b=2",
                "CurrentURIMetaData" to "<DIDL-Lite/>",
            ),
        )
        assertThat(body).startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        assertThat(body).contains(
            "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">",
        )
        assertThat(body).contains("<CurrentURI>http://192.168.1.2:8000/x?a=1&amp;b=2</CurrentURI>")
        assertThat(body).contains("<CurrentURIMetaData>&lt;DIDL-Lite/&gt;</CurrentURIMetaData>")
        assertThat(body).endsWith("</s:Body></s:Envelope>")
    }

    @Test
    fun `action header carries service and action quoted`() {
        assertThat(Soap.actionHeader(Soap.SERVICE_AV_TRANSPORT, "Play"))
            .isEqualTo("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"")
    }

    @Test
    fun `escape covers the five XML specials`() {
        assertThat(Soap.escape("""a&b<c>d"e'f""")).isEqualTo("a&amp;b&lt;c&gt;d&quot;e&apos;f")
    }

    @Test
    fun `parses response out-arguments`() {
        val body = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <Track>1</Track>
                  <TrackDuration>0:03:25</TrackDuration>
                  <RelTime>0:01:02</RelTime>
                </u:GetPositionInfoResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val parsed = Soap.parseResponse(body)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!["TrackDuration"]).isEqualTo("0:03:25")
        assertThat(parsed["RelTime"]).isEqualTo("0:01:02")
    }

    @Test
    fun `faults and garbage parse to null`() {
        val fault = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
              <s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring></s:Fault>
            </s:Body></s:Envelope>
        """.trimIndent()
        assertThat(Soap.parseResponse(fault)).isNull()
        assertThat(Soap.parseResponse("junk")).isNull()
        assertThat(Soap.parseResponse("<ok/>")).isNull() // no *Response element
    }

    @Test
    fun `time formatting round-trips`() {
        assertThat(Soap.formatTime(0)).isEqualTo("0:00:00")
        assertThat(Soap.formatTime(62_000)).isEqualTo("0:01:02")
        assertThat(Soap.formatTime(3_600_000 + 25_000)).isEqualTo("1:00:25")
        assertThat(Soap.parseTime("0:03:25")).isEqualTo(205_000)
        assertThat(Soap.parseTime("1:00:25")).isEqualTo(3_625_000)
        assertThat(Soap.parseTime("0:03:25.500")).isEqualTo(205_500)
        assertThat(Soap.parseTime("55")).isEqualTo(55_000) // bare seconds
    }

    @Test
    fun `unusable times parse to null`() {
        assertThat(Soap.parseTime(null)).isNull()
        assertThat(Soap.parseTime("")).isNull()
        assertThat(Soap.parseTime("NOT_IMPLEMENTED")).isNull()
        assertThat(Soap.parseTime("abc")).isNull()
        assertThat(Soap.parseTime("1:2:3:4")).isNull()
        assertThat(Soap.parseTime("-1:00")).isNull()
    }
}
