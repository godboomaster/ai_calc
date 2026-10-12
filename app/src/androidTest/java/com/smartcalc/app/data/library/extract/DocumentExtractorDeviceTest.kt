package com.smartcalc.app.data.library.extract

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.smartcalc.app.data.library.source.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DocumentExtractorDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun extractsSixPdfPagesAndRastersWithoutTruncation() = runBlocking {
        val root = File(instrumentation.targetContext.cacheDir, "pdf-test-${System.nanoTime()}").apply { mkdirs() }
        val store = LibrarySourceStore(root)
        val source = store.copy(SourceInput("six.pdf", "application/pdf") {
            instrumentation.context.assets.open("library-import/i08-six-pages.pdf") }).source
        val extractor = DocumentExtractor(store)
        assertEquals(6, extractor.inspect(source))
        val saved = mutableListOf<ExtractedSegment>()
        val result = extractor.extract(source, PageSelection((1..6).toList())) { saved += it }
        assertEquals((1..6).toList(), result.segments.map { it.ref.ordinal })
        assertEquals(6, saved.size)
        result.segments.forEach { segment ->
            val bitmap = BitmapFactory.decodeFile(segment.imagePaths.single())
            try { assertTrue(bitmap.width.toLong() * bitmap.height <= 4_000_000) } finally { bitmap.recycle() }
        }
        val again = extractor.extract(source, PageSelection(listOf(5, 6))) { }
        assertEquals(result.segments.takeLast(2).map { it.ref.segmentId }, again.segments.map { it.ref.segmentId })
    }
    @Test fun protectedPdfFailsAndOriginalRemainsAvailable() = runBlocking {
        val store = LibrarySourceStore(File(instrumentation.targetContext.cacheDir, "protected-${System.nanoTime()}"))
        val source = store.copy(SourceInput("protected.pdf", "application/pdf") {
            instrumentation.context.assets.open("library-import/i13-protected.pdf") }).source
        try { DocumentExtractor(store).inspect(source); fail("encrypted PDF") } catch (_: Exception) { }
        assertTrue(store.originalFile(source.id).isFile)
    }
}
