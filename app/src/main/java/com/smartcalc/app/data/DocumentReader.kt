package com.smartcalc.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

class DocumentException(message: String) : Exception(message)

/** Что из документа уходит модели: текст (DOCX, TXT) или картинки страниц (PDF, изображение). */
class DocumentContent(val name: String, val text: String?, val images: List<ByteArray>)

/**
 * Документ с задачей приводится к тому, что понимает любая OpenAI-совместимая модель с картинками:
 * PDF — к изображениям страниц, DOCX и TXT — к тексту. Файлы целиком серверы принимают не все.
 */
object DocumentReader {

    /** Модели хватает первых страниц; дальше запрос только тяжелеет. */
    private const val MAX_PDF_PAGES = 4
    private const val PDF_PAGE_WIDTH = 1400
    private const val MAX_TEXT_LENGTH = 20_000

    private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    /** Типы для системного выбора файла. */
    val mimeTypes = arrayOf("application/pdf", DOCX_MIME, "text/*", "image/*")

    suspend fun read(context: Context, uri: Uri): DocumentContent = withContext(Dispatchers.IO) {
        val name = displayName(context, uri) ?: "документ"
        val mime = context.contentResolver.getType(uri).orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase()
        when {
            mime == "application/pdf" || extension == "pdf" -> DocumentContent(name, null, pdfPages(context, uri))
            mime == DOCX_MIME || extension == "docx" -> DocumentContent(name, limit(docxText(readZipEntry(context, uri))), emptyList())
            mime.startsWith("image/") -> DocumentContent(name, null, listOf(ImageUtils.compressUri(context, uri)))
            mime.startsWith("text/") || extension in setOf("txt", "md", "csv", "tex") ->
                DocumentContent(name, limit(decode(readBytes(context, uri))), emptyList())

            else -> throw DocumentException("Этот формат не поддерживается: подойдут PDF, DOCX, TXT и изображения")
        }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun readBytes(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw DocumentException("Не удалось открыть файл")

    private fun pdfPages(context: Context, uri: Uri): List<ByteArray> {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw DocumentException("Не удалось открыть PDF")
        return descriptor.use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0) throw DocumentException("В PDF нет страниц")
                (0 until minOf(renderer.pageCount, MAX_PDF_PAGES)).map { index ->
                    renderer.openPage(index).use { page ->
                        val scale = PDF_PAGE_WIDTH.toFloat() / page.width
                        val bitmap = Bitmap.createBitmap(
                            PDF_PAGE_WIDTH,
                            (page.height * scale).roundToInt(),
                            Bitmap.Config.ARGB_8888,
                        )
                        // Страницы PDF прозрачные: без белого фона JPEG получится чёрным.
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        ImageUtils.compressBitmap(bitmap)
                    }
                }
            }
        }
    }

    private fun readZipEntry(context: Context, uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    if (entry.name == "word/document.xml") return zip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        throw DocumentException("Не удалось прочитать DOCX")
    }

    private val PARAGRAPH = Regex("""<w:p[ >].*?</w:p>|<w:p/>""", RegexOption.DOT_MATCHES_ALL)
    private val RUN_TEXT = Regex("""<w:t(?:\s[^>]*)?>(.*?)</w:t>|<w:tab/>|<w:br/>""", RegexOption.DOT_MATCHES_ALL)

    /** Текст DOCX по абзацам. Формулы Word (OMML) сюда не попадают — их текст хранится иначе. */
    internal fun docxText(xml: String): String = PARAGRAPH.findAll(xml).joinToString("\n") { p ->
        RUN_TEXT.findAll(p.value).joinToString("") { m ->
            when {
                m.value == "<w:tab/>" -> "\t"
                m.value == "<w:br/>" -> "\n"
                else -> unescape(m.groupValues[1])
            }
        }
    }.trim().ifEmpty { throw DocumentException("В документе нет текста") }

    private fun unescape(s: String) = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&amp;", "&")

    /** Русские TXT бывают и в UTF-8, и в Windows-1251. */
    internal fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1251"))
    }.removePrefix("\uFEFF").trim().ifEmpty { throw DocumentException("Файл пустой") }

    private fun limit(text: String) =
        if (text.length > MAX_TEXT_LENGTH) text.take(MAX_TEXT_LENGTH) + "\n…" else text
}
