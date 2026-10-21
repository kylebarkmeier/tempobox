package com.tempobox.remoteplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DidlLiteTest {

    @Test
    fun `track metadata carries title artist album duration and escaped url`() {
        val didl = DidlLite.trackMetadata(
            title = "Song <1>",
            artist = "Artist & Co",
            album = "Album",
            durationMs = 205_000,
            url = "http://192.168.1.2:8000/abcd?x=1&y=2",
            mimeType = "audio/flac",
        )
        assertThat(didl).contains("<dc:title>Song &lt;1&gt;</dc:title>")
        assertThat(didl).contains("<upnp:artist>Artist &amp; Co</upnp:artist>")
        assertThat(didl).contains("<upnp:album>Album</upnp:album>")
        assertThat(didl).contains("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
        assertThat(didl).contains("protocolInfo=\"http-get:*:audio/flac:DLNA.ORG_OP=01\"")
        assertThat(didl).contains("duration=\"0:03:25\"")
        assertThat(didl).contains(">http://192.168.1.2:8000/abcd?x=1&amp;y=2</res>")
        // What we build must parse back as one audio entry (same grammar both ways).
        val parsed = DidlLite.parseEntries(didl)
        assertThat(parsed).hasSize(1)
        assertThat((parsed[0] as DidlLite.Entry.Audio).title).isEqualTo("Song <1>")
    }

    @Test
    fun `parses containers and audio items from a browse result`() {
        val didl = """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                xmlns:dc="http://purl.org/dc/elements/1.1/"
                xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
              <container id="64" parentID="0" childCount="12" restricted="1">
                <dc:title>Albums</dc:title>
                <upnp:class>object.container</upnp:class>
              </container>
              <item id="64${'$'}0" parentID="64" restricted="1">
                <dc:title>First Track</dc:title>
                <upnp:artist>Someone</upnp:artist>
                <upnp:album>Greatest</upnp:album>
                <upnp:class>object.item.audioItem.musicTrack</upnp:class>
                <res protocolInfo="http-get:*:audio/mpeg:DLNA.ORG_OP=01" duration="0:04:00">
                  http://nas.local:8200/MediaItems/22.mp3
                </res>
              </item>
            </DIDL-Lite>
        """.trimIndent()
        val entries = DidlLite.parseEntries(didl)
        assertThat(entries).hasSize(2)
        val folder = entries[0] as DidlLite.Entry.Folder
        assertThat(folder.id).isEqualTo("64")
        assertThat(folder.title).isEqualTo("Albums")
        assertThat(folder.childCount).isEqualTo(12)
        val audio = entries[1] as DidlLite.Entry.Audio
        assertThat(audio.title).isEqualTo("First Track")
        assertThat(audio.artist).isEqualTo("Someone")
        assertThat(audio.album).isEqualTo("Greatest")
        assertThat(audio.durationMs).isEqualTo(240_000)
        assertThat(audio.url).isEqualTo("http://nas.local:8200/MediaItems/22.mp3")
        assertThat(audio.mimeType).isEqualTo("audio/mpeg")
    }

    @Test
    fun `dc creator stands in for a missing upnp artist`() {
        val didl = """
            <DIDL-Lite><item id="1" restricted="1">
              <dc:title>T</dc:title><dc:creator>Composer</dc:creator>
              <upnp:class>object.item.audioItem</upnp:class>
              <res protocolInfo="http-get:*:audio/mpeg:*">http://s/1.mp3</res>
            </item></DIDL-Lite>
        """.trimIndent()
        val audio = DidlLite.parseEntries(didl).single() as DidlLite.Entry.Audio
        assertThat(audio.artist).isEqualTo("Composer")
        assertThat(audio.mimeType).isEqualTo("audio/mpeg")
    }

    @Test
    fun `non-audio items and non-http resources are dropped`() {
        val didl = """
            <DIDL-Lite>
              <item id="v1" restricted="1">
                <dc:title>A video</dc:title>
                <upnp:class>object.item.videoItem</upnp:class>
                <res protocolInfo="http-get:*:video/mp4:*">http://s/v.mp4</res>
              </item>
              <item id="a1" restricted="1">
                <dc:title>Weird proto</dc:title>
                <upnp:class>object.item.audioItem.musicTrack</upnp:class>
                <res protocolInfo="internal:*:audio/mpeg:*">file:///music/x.mp3</res>
              </item>
              <item id="a2" restricted="1">
                <dc:title>No resource</dc:title>
                <upnp:class>object.item.audioItem.musicTrack</upnp:class>
              </item>
            </DIDL-Lite>
        """.trimIndent()
        assertThat(DidlLite.parseEntries(didl)).isEmpty()
    }

    @Test
    fun `garbage parses to an empty list`() {
        assertThat(DidlLite.parseEntries("")).isEmpty()
        assertThat(DidlLite.parseEntries("not xml")).isEmpty()
    }
}
