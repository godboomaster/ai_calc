package com.smartcalc.app.data.library.importer

import com.smartcalc.app.data.api.*
import com.smartcalc.app.data.library.extract.DocumentExtractor
import com.smartcalc.app.data.library.extract.PageSelection
import com.smartcalc.app.data.library.source.LibrarySourceStore
import com.smartcalc.app.data.library.source.SourceInput
import com.smartcalc.app.data.settings.ApiSettings
import com.smartcalc.app.data.template.TemplateKind
import com.smartcalc.app.data.template.TemplateStore
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryImportComponentTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun concurrentAccessRecoversOnceWithoutRepeatingPublicationOrRecognition() = runBlocking {
        val root = temp.newFolder()
        val sources = LibrarySourceStore(File(root, "sources"))
        val source = sources.copy(SourceInput("raw.txt", "text/plain") { "Raw".byteInputStream() }).source
        val journal = LibraryImportJournal(File(root, "imports"))
        val id = UUID.randomUUID().toString()
        val intent = PublicationIntent(UUID.randomUUID().toString(), id, UUID.randomUUID().toString(), "Section", items = emptyList())
        journal.save(ImportJob(id, listOf(source.id), mapOf(source.id to PageSelection(listOf(1))),
            status = ImportStatus.PUBLISHING, attempt = 3, postsUsed = 2, publication = intent))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookups = AtomicInteger()
        try {
            val component = LibraryImportComponent(sources, journal, DocumentExtractor(sources),
                TemplateStore(File(root, "library"), TemplateKind.LIBRARY),
                settings = { error("Recovery must not read provider settings") }, scope = scope,
                solutionActive = MutableStateFlow(false),
                recognizer = DocumentRecognizer(object : RecognitionTransport {
                    override suspend fun post(settings: ApiSettings, body: JsonObject): RecognitionHttpResponse =
                        error("Recovery must not repeat paid requests")
                }), publisher = object : ImportPublisher {
                    override suspend fun publish(intent: PublicationIntent): ImportReceipt = error("Recovery must not republish")
                    override fun receipt(commitId: String): ImportReceipt? {
                        assertEquals(intent.commitId, commitId)
                        lookups.incrementAndGet()
                        return null
                    }
                })
            assertEquals(0, lookups.get()) // Composition alone must not start recovery.
            val controllers = coroutineScope { (1..20).map { async(Dispatchers.Default) { component.controller } }.awaitAll() }
            assertTrue(controllers.all { it === controllers.first() })
            withTimeout(5_000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
            assertSame(controllers.first(), component.controller)
            withTimeout(5_000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
            assertEquals(1, lookups.get())
            val recovered = journal.jobs.value.jobs.single()
            assertEquals(ImportStatus.REVIEW_REQUIRED, recovered.status)
            assertEquals(3L, recovered.attempt)
            assertEquals(2, recovered.postsUsed)
            assertEquals(intent, recovered.publication)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
