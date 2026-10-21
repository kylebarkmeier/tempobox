package com.tempobox.remoteplay

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * SOAP-over-HTTP round trips against a canned renderer. Covers the request
 * shape (SOAPACTION header, InstanceID) and response field extraction.
 */
class RendererClientTest {

    private val server = MockWebServer()
    private lateinit var client: RendererClient

    @Before
    fun setUp() {
        server.start()
        val device = UpnpDevice(
            udn = "uuid:test",
            friendlyName = "Test Renderer",
            location = server.url("/desc.xml").toString(),
            avTransportControlUrl = server.url("/av").toString(),
            renderingControlUrl = server.url("/rc").toString(),
        )
        client = RendererClient(UpnpControl(), device)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueResponse(action: String, innerXml: String = "") {
        server.enqueue(
            MockResponse().setBody(
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
                    "<u:${action}Response xmlns:u=\"${Soap.SERVICE_AV_TRANSPORT}\">$innerXml" +
                    "</u:${action}Response></s:Body></s:Envelope>",
            ),
        )
    }

    @Test
    fun `setUri posts SOAPACTION and the escaped envelope`() = runTest {
        enqueueResponse("SetAVTransportURI")
        val ok = client.setUri("http://10.0.0.2:8000/tok", "<DIDL-Lite/>")
        assertThat(ok).isTrue()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/av")
        assertThat(request.getHeader("SOAPACTION"))
            .isEqualTo("\"${Soap.SERVICE_AV_TRANSPORT}#SetAVTransportURI\"")
        val body = request.body.readUtf8()
        assertThat(body).contains("<InstanceID>0</InstanceID>")
        assertThat(body).contains("<CurrentURI>http://10.0.0.2:8000/tok</CurrentURI>")
        assertThat(body).contains("&lt;DIDL-Lite/&gt;")
    }

    @Test
    fun `play pause stop seek succeed on 200`() = runTest {
        enqueueResponse("Play")
        enqueueResponse("Pause")
        enqueueResponse("Stop")
        enqueueResponse("Seek")
        assertThat(client.play()).isTrue()
        assertThat(client.pause()).isTrue()
        assertThat(client.stop()).isTrue()
        assertThat(client.seek(65_000)).isTrue()
        repeat(3) { server.takeRequest() }
        assertThat(server.takeRequest().body.readUtf8()).contains("<Target>0:01:05</Target>")
    }

    @Test
    fun `position and transport state parse out of the SOAP response`() = runTest {
        enqueueResponse("GetPositionInfo", "<RelTime>0:01:02</RelTime><TrackDuration>0:03:25</TrackDuration>")
        enqueueResponse("GetTransportInfo", "<CurrentTransportState>PLAYING</CurrentTransportState>")
        val position = client.position()
        assertThat(position?.positionMs).isEqualTo(62_000)
        assertThat(position?.durationMs).isEqualTo(205_000)
        assertThat(client.transportState()).isEqualTo("PLAYING")
    }

    @Test
    fun `volume goes through RenderingControl`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
                    "<u:GetVolumeResponse xmlns:u=\"${Soap.SERVICE_RENDERING_CONTROL}\">" +
                    "<CurrentVolume>42</CurrentVolume></u:GetVolumeResponse></s:Body></s:Envelope>",
            ),
        )
        assertThat(client.volume()).isEqualTo(42)
        assertThat(server.takeRequest().path).isEqualTo("/rc")
    }

    @Test
    fun `HTTP 500 faults and dead sockets read as failure, not exceptions`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("fault"))
        assertThat(client.play()).isFalse()
        server.shutdown()
        assertThat(client.pause()).isFalse()
    }
}
