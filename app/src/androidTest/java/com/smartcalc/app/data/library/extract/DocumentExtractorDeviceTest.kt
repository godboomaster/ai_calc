package com.smartcalc.app.data.library.extract

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.smartcalc.app.data.library.source.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DocumentExtractorDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun docxEmbeddedImageIsPreparedAsJpegAndUnsupportedImageWarns() = runBlocking {
        val root = File(instrumentation.targetContext.cacheDir, "docx-test-${System.nanoTime()}")
        val store = LibrarySourceStore(root)
        val buffer = java.io.ByteArrayOutputStream()
        val bitmap = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer) } finally { bitmap.recycle() }
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            val parts = mapOf("[Content_Types].xml" to "<Types/>".toByteArray(),
                "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body><w:p><w:r><w:t>Photo</w:t><a:blip r:embed="good"/></w:r></w:p><w:p><w:r><w:t>Unsupported</w:t><a:blip r:embed="bad"/></w:r></w:p></w:body></w:document>""".toByteArray(),
                "word/_rels/document.xml.rels" to """<Relationships><Relationship Id="good" Target="media/image.png"/><Relationship Id="bad" Target="media/bad.emf"/></Relationships>""".toByteArray(),
                "word/media/image.png" to buffer.toByteArray(), "word/media/bad.emf" to "unsupported".toByteArray())
            parts.forEach { (name, data) -> zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(data); zip.closeEntry() }
        }
        val source = store.copy(SourceInput("photos.docx", null) { bytes.toByteArray().inputStream() }).source
        val result = DocumentExtractor(store).extract(source, PageSelection(listOf(1, 2))) { }
        val path = result.segments.first().imagePaths.single()
        assertTrue(path.endsWith(".jpg"))
        assertEquals(0xff, File(path).readBytes()[0].toInt() and 0xff)
        assertTrue(result.segments.last().imagePaths.isEmpty())
        assertTrue(result.segments.last().warnings.any { it.blocking })
    }
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
