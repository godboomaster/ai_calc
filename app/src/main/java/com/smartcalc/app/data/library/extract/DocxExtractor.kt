package com.smartcalc.app.data.library.extract

import com.smartcalc.app.data.library.source.*
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import org.w3c.dom.Element

object DocxExtractor {
    private const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val MATH = "http://schemas.openxmlformats.org/officeDocument/2006/math"
    fun extract(source: LibrarySource, original: File, mediaRoot: File): ExtractionResult {
        val parts = mutableMapOf<String, String>(); val media = mutableMapOf<String, String>()
        var total = 0L
        ZipInputStream(original.inputStream()).use { zip ->
            val seen = mutableSetOf<String>(); val buffer = ByteArray(8192)
            while (true) {
                val entry = zip.nextEntry ?: break
                require(seen.add(entry.name) && !entry.name.startsWith('/') && !entry.name.contains('\\') && entry.name.split('/').none { it == ".." }) { "Недопустимый путь или повтор части DOCX" }
                val xml = entry.name.endsWith(".xml") || entry.name.endsWith(".rels")
                val image = entry.name.startsWith("word/media/") && !entry.isDirectory
                val bytes = ByteArrayOutputStream(); var size = 0L
                while (true) {
                    val n = zip.read(buffer); if (n < 0) break
                    size += n; total += n
                    require(total <= 64L * 1024 * 1024 && (!xml || size <= 8L * 1024 * 1024)) { "DOCX превышает ограничения распаковки" }
                    if (xml || image) bytes.write(buffer, 0, n)
                }
                if (xml) parts[entry.name] = bytes.toString("UTF-8")
                if (image) {
                    mediaRoot.mkdirs()
                    val target = File(mediaRoot, "${UUID.nameUUIDFromBytes(entry.name.toByteArray())}.media")
                    require(target.canonicalFile.toPath().startsWith(mediaRoot.canonicalFile.toPath()))
                    val temporary = File(mediaRoot, ".${target.name}.${UUID.randomUUID()}.tmp")
                    try {
                        java.io.FileOutputStream(temporary).use { it.write(bytes.toByteArray()); it.fd.sync() }
                        java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    } finally { temporary.delete() }
                    media[entry.name] = target.absolutePath
                }
            }
        }
        val root = SafeXml.parse(parts["word/document.xml"] ?: throw IllegalArgumentException("В DOCX отсутствует document.xml"))
        val relationships = parts["word/_rels/document.xml.rels"]?.let { xml ->
            SafeXml.parse(xml).children().associate { el ->
                val target = el.getAttribute("Target")
                val value = if (el.getAttribute("TargetMode") == "External" || target.startsWith('/') || target.split('/').any { it == ".." }) null
                    else media["word/$target"]
                el.getAttribute("Id") to value
            }
        }.orEmpty()
        val body = root.children().firstOrNull { it.localName == "body" && it.namespaceURI == WORD }
            ?: throw IllegalArgumentException("В DOCX нет основного текста")
        val result = mutableListOf<ExtractedSegment>()
        body.children().filterNot { it.localName == "sectPr" }.forEach { block ->
            val warnings = mutableListOf<ExtractionWarning>(); val images = mutableListOf<String>()
            fun text(el: Element): String = when {
                el.namespaceURI == MATH && el.localName in setOf("oMath", "oMathPara") -> OmmlText.convert(el).let { warnings += it.warnings; "\$${it.text}\$" }
                el.namespaceURI == WORD && el.localName == "t" -> el.textContent
                el.namespaceURI == WORD && el.localName == "tab" -> "\t"
                el.namespaceURI == WORD && el.localName in setOf("br", "cr") -> "\n"
                el.localName == "blip" -> {
                    val id = el.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed")
                    val path = relationships[id]
                    if (path != null) images += path else warnings += ExtractionWarning("image_unsupported", "Рисунок DOCX не извлечён. Откройте оригинал.")
                    ""
                }
                el.namespaceURI == WORD && el.localName in setOf("object", "pict", "gridSpan", "vMerge") -> {
                    warnings += ExtractionWarning("object_unsupported", "Объект или объединённая ячейка требует проверки по оригиналу.")
                    el.children().joinToString("") { text(it) }
                }
                el.namespaceURI == WORD && el.localName == "tbl" -> el.children().filter { it.localName == "tr" }.joinToString("\n") { row ->
                    row.children().filter { it.localName == "tc" }.joinToString(" | ") { text(it) }
                }
                el.namespaceURI == WORD && el.localName == "tc" -> el.children().joinToString(" ") { text(it) }.trim()
                else -> el.children().joinToString("") { text(it) }
            }
            val content = text(block).trim()
            if (block.localName !in setOf("p", "tbl")) warnings += ExtractionWarning("block_unsupported", "Нестандартный блок DOCX требует проверки")
            if (content.isNotEmpty() || images.isNotEmpty() || warnings.isNotEmpty()) {
                val ordinal = result.size + 1
                result += ExtractedSegment(SourceSegmentRef(source.id, "${source.id}:block:$ordinal", SegmentKind.DOCUMENT_BLOCK, ordinal), content, images.distinct(), warnings.distinct())
            }
        }
        require(result.isNotEmpty()) { "В DOCX нет доступных блоков" }
        require(result.sumOf { it.text.toByteArray().size.toLong() } <= TextExtractor.MAX_TEXT_BYTES) { "Извлечённый текст больше 2 МиБ. Выберите меньший документ." }
        return ExtractionResult(result, result.size)
    }
}
