package com.smartcalc.app.data.library.extract

import com.smartcalc.app.data.library.source.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

open class DocumentExtractor(private val sources: LibrarySourceStore) {
    open suspend fun inspect(source: LibrarySource): Int = withContext(Dispatchers.IO) {
        val original = sources.originalFile(source.id)
        when (source.format) {
            SourceFormat.PDF -> PdfExtractor.inspect(original)
            SourceFormat.DOCX -> DocxExtractor.extract(source, original, File(original.parentFile, "media")).totalSegments
            SourceFormat.TEXT, SourceFormat.MARKDOWN -> TextExtractor.extract(source, original.inputStream()).totalSegments
            SourceFormat.JPEG, SourceFormat.PNG -> 1
        }.also { sources.setSegmentCount(source.id, it) }
    }
    open suspend fun extract(source: LibrarySource, selection: PageSelection,
        onSegment: suspend (ExtractedSegment) -> Unit): ExtractionResult = withContext(Dispatchers.IO) {
        val original = sources.originalFile(source.id)
        if (source.format == SourceFormat.PDF) return@withContext PdfExtractor.extract(source, original, selection, onSegment)
        val full = when (source.format) {
            SourceFormat.DOCX -> DocxExtractor.extract(source, original, File(original.parentFile, "media"))
            SourceFormat.TEXT, SourceFormat.MARKDOWN -> TextExtractor.extract(source, original.inputStream())
            SourceFormat.JPEG, SourceFormat.PNG -> ExtractionResult(listOf(ImageExtractor.extract(source, original)), 1)
            else -> error("Unsupported format")
        }
        require(selection.ordinals.all { it <= full.totalSegments }) { "Фрагмент документа отсутствует" }
        val selected = full.segments.filter { it.ref.ordinal in selection.ordinals }.map { segment ->
            if (source.format != SourceFormat.DOCX) segment else {
                val warnings = segment.warnings.toMutableList()
                val images = segment.imagePaths.mapNotNull { path ->
                    try {
                        val raw = File(path)
                        ImageExtractor.prepare(raw, File(raw.parentFile, "${raw.nameWithoutExtension}.jpg"))
                    } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                    catch (_: Exception) {
                        warnings += ExtractionWarning("image_unsupported", "Рисунок DOCX не подготовлен. Проверьте его в оригинале."); null
                    }
                }
                segment.copy(imagePaths = images, warnings = warnings.distinct())
            }
        }
        selected.forEach { currentCoroutineContext().ensureActive(); onSegment(it) }
        full.copy(segments = selected, remainingOrdinals = remainingPages(full.totalSegments, selection.ordinals))
    }
}
