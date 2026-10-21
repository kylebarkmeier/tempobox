package com.tempobox.remoteplay

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

    fun parse(xml: String): Document? = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            isExpandEntityReferences = false
            // Not every parser (JDK Xerces vs Android's) knows every feature;
            // apply the ones it does rather than failing setup.
            trySetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            trySetFeature("http://xml.org/sax/features/external-general-entities", false)
            trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
            trySetFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    } catch (_: Exception) {
        null
    }

    private fun DocumentBuilderFactory.trySetFeature(feature: String, value: Boolean) {
        try {
            setFeature(feature, value)
        } catch (_: Exception) {
            // Parser doesn't know this feature; the remaining hardening applies.
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
