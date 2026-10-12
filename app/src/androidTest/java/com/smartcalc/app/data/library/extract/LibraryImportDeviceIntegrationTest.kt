package com.smartcalc.app.data.library.extract

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.smartcalc.app.data.library.source.*
import com.smartcalc.app.data.library.importer.*
import com.smartcalc.app.data.library.LibraryVerification
import com.smartcalc.app.data.template.*
import com.smartcalc.app.data.search.TaskSearch
import com.smartcalc.app.data.api.*
import com.smartcalc.app.data.settings.ApiSettings
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class LibraryImportDeviceIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun input(name: String) = SourceInput(name, null) { instrumentation.context.assets.open("library-import/$name") }
    private fun root() = File(instrumentation.targetContext.cacheDir, "acceptance-${System.nanoTime()}").apply { mkdirs() }

    @Test fun syntheticFormatsKeepOriginalsAndEverySelectedReferenceOpens() = runBlocking {
        val sources = LibrarySourceStore(File(root(), "sources")); val extractor = DocumentExtractor(sources)
        val names = listOf("i01-utf8.txt", "i02-cp1251.txt", "i03-two-pages.pdf", "i04-scan.pdf", "i05-formulas.docx",
            "i06-tilted.jpg", "i07-unclear.png", "i08-six-pages.pdf", "i11-wrong-answer.txt", "i12-instruction.txt", "i14-boundaries.pdf")
        for (name in names) {
            val source = sources.copy(input(name)).source
            val originalBytes = sources.originalFile(source.id).readBytes()
            assertArrayEquals(instrumentation.context.assets.open("library-import/$name").use { it.readBytes() }, originalBytes)
            assertEquals(source.id, sources.copy(input(name)).source.id)
            val count = extractor.inspect(source)
            val extracted = extractor.extract(source, PageSelection((1..count).toList())) { }
            assertEquals(count, extracted.segments.size)
            for (segment in extracted.segments) {
                assertEquals(source.id, segment.ref.sourceId)
                assertEquals(segment.ref, extractor.extract(source, PageSelection(listOf(segment.ref.ordinal))) { }.segments.single().ref)
                for (path in segment.imagePaths) {
                    val bitmap = checkNotNull(BitmapFactory.decodeFile(path))
                    try { assertTrue(bitmap.width.toLong() * bitmap.height <= 4_000_000) } finally { bitmap.recycle() }
                }
            }
            assertArrayEquals(originalBytes, sources.originalFile(source.id).readBytes())
        }
        try { sources.copy(input("i13-legacy.doc")); fail("old DOC must explain conversion") }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("DOCX")) }
    }

    @Test fun localTwoTasksPublishWithAnswersAndSurviveStoreRestartAndSourceDeletion() = runBlocking {
        val root = root(); val sources = LibrarySourceStore(File(root, "sources"))
        val source = sources.copy(input("i01-utf8.txt")).source
        val journal = LibraryImportJournal(File(root, "imports")); val library = TemplateStore(root, TemplateKind.LIBRARY)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var posts = 0
        try {
            val controller = LibraryImportController(sources, journal, DocumentExtractor(sources), DocumentRecognizer(object : RecognitionTransport {
                override suspend fun post(settings: ApiSettings, body: JsonObject): RecognitionHttpResponse { posts++; error("structured fixture must stay local") }
            }), TemplateImportPublisher(library, sources), { ApiSettings() }, scope,
                sectionSnapshot = { id -> library.items.value.firstOrNull { it.id == id } })
            val count = DocumentExtractor(sources).inspect(source)
            val id = controller.create(listOf(source.id), mapOf(source.id to PageSelection((1..count).toList())))
            controller.process(id)
            withTimeout(5000) { journal.jobs.first { it.jobs.single().status == ImportStatus.REVIEW_REQUIRED } }
            val drafts = journal.jobs.value.jobs.single().drafts
            assertEquals(2, drafts.size); assertEquals(0, posts)
            controller.publish(id, drafts.map { it.id }, null, "Device acceptance")
            val restarted = TemplateStore(root, TemplateKind.LIBRARY)
            assertEquals(2, restarted.items.value.single().examples.size)
            assertTrue(restarted.items.value.single().examples.all { it.answer.isNotBlank() && it.library!!.verification == LibraryVerification.UNVERIFIED })
            val receipt = journal.jobs.value.jobs.single().receipt!!
            assertEquals(receipt, restarted.importReceipt(receipt.commitId))
            assertEquals(2, TaskSearch.entries(restarted.items.value, emptyList(), emptyList(), sources.state.value).size)
            sources.setEnabled(source.id, false)
            assertTrue(TaskSearch.entries(restarted.items.value, emptyList(), emptyList(), sources.state.value).isEmpty())
            val original = sources.originalFile(source.id)
            sources.deleteOriginal(source.id)
            assertFalse(original.exists())
            assertEquals(2, restarted.items.value.single().examples.size)
            assertEquals(receipt, TemplateImportPublisher(restarted, sources).publish(journal.jobs.value.jobs.single().publication!!))
        } finally { scope.cancel() }
    }
}
