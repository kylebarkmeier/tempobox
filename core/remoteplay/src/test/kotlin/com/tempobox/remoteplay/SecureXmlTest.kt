package com.tempobox.remoteplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DOCTYPE input must be rejected before parsing: Android's platform parser
 * ignores the disallow-doctype-decl feature and would expand internal DTD
 * entities, so the guard cannot rely on the parser enforcing it.
 */
@RunWith(RobolectricTestRunner::class)
class SecureXmlTest {

    @Test
    fun `a document without a DTD parses`() {
        val doc = SecureXml.parse("""<?xml version="1.0"?><root><name>ok</name></root>""")
        assertThat(doc).isNotNull()
        assertThat(SecureXml.text(doc!!.documentElement, "name")).isEqualTo("ok")
    }

    @Test
    fun `a DOCTYPE declaration is rejected outright`() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE root [ <!ENTITY payload "expanded"> ]>
            <root><name>&payload;</name></root>
        """.trimIndent()
        assertThat(SecureXml.parse(xml)).isNull()
    }

    @Test
    fun `a lowercase doctype is rejected too`() {
        assertThat(SecureXml.parse("""<!doctype root><root/>""")).isNull()
    }

    @Test
    fun `a DOCTYPE hidden after a comment is rejected`() {
        val xml = """<?xml version="1.0"?><!-- hi --><!DOCTYPE root SYSTEM "http://evil/x.dtd"><root/>"""
        assertThat(SecureXml.parse(xml)).isNull()
    }
}
