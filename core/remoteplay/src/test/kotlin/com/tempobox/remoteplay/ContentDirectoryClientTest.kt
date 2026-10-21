package com.tempobox.remoteplay

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class ContentDirectoryClientTest {

    private val server = MockWebServer()
    private lateinit var device: UpnpDevice
    private val client = ContentDirectoryClient(UpnpControl())

    @Before
    fun setUp() {
        server.start()
        device = UpnpDevice(
            udn = "uuid:nas",
            friendlyName = "NAS",
            location = server.url("/desc.xml").toString(),
            contentDirectoryControlUrl = server.url("/cd").toString(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun browseResponse(didl: String, returned: Int, total: Int) = MockResponse().setBody(
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
            "<u:BrowseResponse xmlns:u=\"${Soap.SERVICE_CONTENT_DIRECTORY}\">" +
            "<Result>${Soap.escape(didl)}</Result>" +
            "<NumberReturned>$returned</NumberReturned><TotalMatches>$total</TotalMatches>" +
            "<UpdateID>1</UpdateID></u:BrowseResponse></s:Body></s:Envelope>",
    )

    private fun didlItem(id: String, title: String) =
        "<item id=\"$id\" restricted=\"1\"><dc:title>$title</dc:title>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"http-get:*:audio/mpeg:*\">http://nas/$id.mp3</res></item>"

    @Test
    fun `browses one folder and parses its entries`() = runTest {
        server.enqueue(
            browseResponse(
                "<DIDL-Lite><container id=\"64\" childCount=\"2\"><dc:title>Music</dc:title>" +
                    "</container>${didlItem("1", "Track A")}</DIDL-Lite>",
                returned = 2,
                total = 2,
            ),
        )
        val entries = client.browse(device, "0")
        assertThat(entries).hasSize(2)
        val request = server.takeRequest()
        assertThat(request.getHeader("SOAPACTION"))
            .isEqualTo("\"${Soap.SERVICE_CONTENT_DIRECTORY}#Browse\"")
        val body = request.body.readUtf8()
        assertThat(body).contains("<ObjectID>0</ObjectID>")
        assertThat(body).contains("<BrowseFlag>BrowseDirectChildren</BrowseFlag>")
    }

    @Test
    fun `pages until TotalMatches is reached`() = runTest {
        server.enqueue(browseResponse("<DIDL-Lite>${didlItem("1", "A")}</DIDL-Lite>", 1, 2))
        server.enqueue(browseResponse("<DIDL-Lite>${didlItem("2", "B")}</DIDL-Lite>", 1, 2))
        val entries = client.browse(device, "64")
        assertThat(entries).hasSize(2)
        server.takeRequest()
        assertThat(server.takeRequest().body.readUtf8()).contains("<StartingIndex>1</StartingIndex>")
    }

    @Test
    fun `a failed first page is a failed browse`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThat(client.browse(device, "0")).isNull()
    }

    @Test
    fun `a failed later page returns the partial listing`() = runTest {
        server.enqueue(browseResponse("<DIDL-Lite>${didlItem("1", "A")}</DIDL-Lite>", 1, 5))
        server.enqueue(MockResponse().setResponseCode(500))
        val entries = client.browse(device, "64")
        assertThat(entries).hasSize(1)
    }

    @Test
    fun `a device without ContentDirectory browses to null`() = runTest {
        val renderer = device.copy(contentDirectoryControlUrl = null)
        assertThat(client.browse(renderer, "0")).isNull()
    }
}
