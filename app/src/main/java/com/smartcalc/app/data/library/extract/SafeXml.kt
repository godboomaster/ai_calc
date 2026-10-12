package com.smartcalc.app.data.library.extract

import java.io.StringReader
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import org.w3c.dom.Element
import org.w3c.dom.Node

internal object SafeXml {
    fun parse(xml: String): Element {
        require(xml.toByteArray().size <= 8 * 1024 * 1024 && !xml.contains('\u0000')) { "XML слишком большой или кодировка не поддерживается" }
        require(!xml.uppercase(Locale.ROOT).contains("<!DOCTYPE") && !xml.uppercase(Locale.ROOT).contains("<!ENTITY")) { "DTD и внешние сущности не поддерживаются" }
        // Check nesting/node count with a streaming parser before allocating a DOM tree.
        val sax = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser()
        var depth = 0; var count = 0
        sax.parse(InputSource(StringReader(xml)), object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: org.xml.sax.Attributes?) {
                require(++depth <= 64 && ++count <= 100_000) { "Слишком сложный XML" }
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) { depth-- }
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))
        })
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        return builder.parse(InputSource(StringReader(xml))).documentElement
    }
}
internal fun Element.children(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
internal fun Element.descendants(): Sequence<Element> = sequence {
    yield(this@descendants)
    children().forEach { yieldAll(it.descendants()) }
}
