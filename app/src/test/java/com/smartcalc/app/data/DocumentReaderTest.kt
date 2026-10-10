package com.smartcalc.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

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
}
