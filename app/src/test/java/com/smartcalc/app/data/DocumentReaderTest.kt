package com.smartcalc.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentReaderTest {

    @Test
    fun docxParagraphsAndEntities() {
        val xml = """<w:document><w:body>
            <w:p><w:r><w:t>Задача 1.</w:t></w:r><w:r><w:t xml:space="preserve"> Найти x &amp; y</w:t></w:r></w:p>
            <w:p/>
            <w:p w:rsidR="00A"><w:r><w:t>x &lt; 5</w:t><w:tab/><w:t>y</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>ячейка</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
        </w:body></w:document>"""
        assertEquals("Задача 1. Найти x & y\n\nx < 5\ty\nячейка", DocumentReader.docxText(xml))
    }

    @Test
    fun emptyDocxFails() {
        assertThrows(DocumentException::class.java) { DocumentReader.docxText("<w:document/>") }
    }

    @Test
    fun textInUtf8AndWindows1251() {
        assertEquals("Решите уравнение", DocumentReader.decode("﻿Решите уравнение".toByteArray(Charsets.UTF_8)))
        assertEquals("Решите уравнение", DocumentReader.decode("Решите уравнение".toByteArray(charset("windows-1251"))))
    }

    @Test fun oversizedTextStopsReadingBeforeLoadingTheWholeFile() {
        var consumed = 0
        val input = object : InputStream() {
            override fun read(): Int = if (consumed++ < 4 * 1024 * 1024) 'x'.code else -1
        }
        assertThrows(DocumentException::class.java) { DocumentReader.readTextBytes(input) }
        assertTrue("Must stop after the 2 MiB input budget", consumed <= 2 * 1024 * 1024 + 1)
    }

    @Test fun oversizedDocxXmlIsRejectedAfterDecompression() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            val chunk = ByteArray(8192) { 'x'.code.toByte() }
            repeat(1025) { zip.write(chunk) }
            zip.closeEntry()
        }
        assertThrows(DocumentException::class.java) {
            DocumentReader.readDocxXml(ByteArrayInputStream(bytes.toByteArray()))
        }
    }

    @Test fun smallInputsStillKeepTheirContents() {
        val text = "Задача: найти x"
        assertEquals(text, DocumentReader.decode(DocumentReader.readTextBytes(ByteArrayInputStream(text.toByteArray()))))
        val xml = "<w:document><w:p><w:t>Найти x</w:t></w:p></w:document>"
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(xml.toByteArray())
            zip.closeEntry()
        }
        assertEquals("Найти x", DocumentReader.docxText(DocumentReader.readDocxXml(ByteArrayInputStream(bytes.toByteArray()))))
    }
}
