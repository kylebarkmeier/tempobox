package com.tempobox.remoteplay

import android.util.Log
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Hardened XML parsing for everything this module receives off the network
 * (device descriptions, SOAP responses, DIDL-Lite). All of it is untrusted
 * input: DTDs and external entities are disabled, and parse failures return
 * null instead of throwing.
 */
internal object SecureXml {

    private const val TAG = "SecureXml"

    fun parse(xml: String): Document? {
        // Each hardening setter is applied individually: Android's platform
        // parser factory throws UnsupportedOperationException for setters the
        // JDK's accepts (setXIncludeAware), and one refused option must not
        // take the whole parser down with it.
        val factory = DocumentBuilderFactory.newInstance()
        trySet { factory.isNamespaceAware = false }
        trySet { factory.isXIncludeAware = false }
        trySet { factory.isExpandEntityReferences = false }
        trySet { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        trySet { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        trySet { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        trySet { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        return try {
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            // Untrusted input, so a failure is expected and non-fatal, but it
            // is the first thing to know when a device "does not appear".
            Log.w(TAG, "XML parse failed: $e")
            null
        }
    }

    private inline fun trySet(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // Parser doesn't support this option; the remaining hardening applies.
        }
    }

    /** All descendant elements with [tag] (document order), as a Kotlin list. */
    fun elements(root: Document, tag: String): List<Element> {
        val nodes = root.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    fun elements(root: Element, tag: String): List<Element> {
        val nodes = root.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    /** Text of the first descendant [tag] under [root], trimmed; null if absent. */
    fun text(root: Element, tag: String): String? =
        elements(root, tag).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}
