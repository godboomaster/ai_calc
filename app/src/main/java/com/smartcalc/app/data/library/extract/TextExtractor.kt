package com.smartcalc.app.data.library.extract

import com.smartcalc.app.data.library.source.*
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset

object TextExtractor {
    const val MAX_TEXT_BYTES = 2 * 1024 * 1024
    fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            require(out.size().toLong() + n <= limit) { "Текст слишком большой. Выберите меньший фрагмент." }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
    fun extract(source: LibrarySource, input: InputStream): ExtractionResult {
        val bytes = input.use { readLimited(it, MAX_TEXT_BYTES) }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { String(bytes, Charset.forName("windows-1251")) }
        require(!text.contains('\u0000')) { "Файл не является поддерживаемым текстом" }
        val blocks = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(blocks.isNotEmpty()) { "В документе нет текста" }
        val segments = blocks.mapIndexed { index, block ->
            ExtractedSegment(SourceSegmentRef(source.id, "${source.id}:block:${index + 1}", SegmentKind.DOCUMENT_BLOCK, index + 1), block)
        }
        return ExtractionResult(segments, segments.size)
    }
}
