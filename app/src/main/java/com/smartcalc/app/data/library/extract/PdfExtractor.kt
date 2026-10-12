package com.smartcalc.app.data.library.extract

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import com.smartcalc.app.data.library.source.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object PdfExtractor {
    fun inspect(original: File): Int = try {
        ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { require(it.pageCount > 0) { "В PDF нет страниц" }; it.pageCount }
        }
    } catch (error: Exception) { throw IllegalArgumentException("PDF повреждён или защищён паролем. Сохраните доступную копию.", error) }
    suspend fun extract(source: LibrarySource, original: File, selection: PageSelection,
        onSegment: suspend (ExtractedSegment) -> Unit): ExtractionResult {
        val segments = mutableListOf<ExtractedSegment>()
        val directory = File(original.parentFile, "pages").apply { mkdirs() }
        ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                require(selection.ordinals.all { it <= renderer.pageCount }) { "Выбранная страница отсутствует в PDF" }
                selection.ordinals.forEach { ordinal ->
                    currentCoroutineContext().ensureActive()
                    val segment = renderer.openPage(ordinal - 1).use { page ->
                        val (width, height) = rasterDimensions(page.width, page.height)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val target = File(directory, "page-$ordinal.jpg")
                        val temporary = File(directory, "page-$ordinal.tmp")
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            FileOutputStream(temporary).use { out ->
                                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)); out.fd.sync()
                            }
                            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                            val text = if (Build.VERSION.SDK_INT >= 35) page.textContents.joinToString("\n") { it.text } else ""
                            require(text.toByteArray().size <= TextExtractor.MAX_TEXT_BYTES) { "Текст страницы превышает 2 МиБ" }
                            ExtractedSegment(SourceSegmentRef(source.id, "${source.id}:page:$ordinal", SegmentKind.PDF_PAGE, ordinal), text, listOf(target.absolutePath))
                        } finally { bitmap.recycle(); temporary.delete() }
                    }
                    onSegment(segment)
                    segments += segment
                }
                return ExtractionResult(segments, renderer.pageCount, remainingPages(renderer.pageCount, selection.ordinals))
            }
        }
    }
}
