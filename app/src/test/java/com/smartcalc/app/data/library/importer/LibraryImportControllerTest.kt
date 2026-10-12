package com.smartcalc.app.data.library.importer

import com.smartcalc.app.data.library.source.*
import com.smartcalc.app.data.library.extract.*
import com.smartcalc.app.data.api.*
import com.smartcalc.app.data.settings.ApiSettings
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryImportControllerTest {
    @get:Rule val temp = TemporaryFolder()
    private val fakePublisher = object : ImportPublisher {
        override suspend fun publish(intent: PublicationIntent) = ImportReceipt(intent.commitId, "hash", intent.sectionId, intent.items.map { it.exampleId })
        override fun receipt(commitId: String): ImportReceipt? = null
    }
    private fun response(id: String) = RecognitionHttpResponse(200, buildJsonObject {
        putJsonArray("choices") { add(buildJsonObject { putJsonObject("message") {
            put("content", """{"drafts":[{"condition":"Condition $id","solution":"Author solution","answer":"2","segment_ids":["$id"]}],"carry":null}""")
        } }) }
    }.toString())
    @Test fun explicitRetryCanReplaceFailedOrMalformedCheckpointWithANewPost() = runBlocking {
        for (failed in listOf(RecognitionHttpResponse(429, "rate limit"), RecognitionHttpResponse(200, "malformed"))) {
            val root = temp.newFolder(); val sources = LibrarySourceStore(File(root, "sources"))
            val src = sources.copy(SourceInput("raw.txt", "text/plain") { "Raw".byteInputStream() }).source
            val journal = LibraryImportJournal(File(root, "imports")); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var calls = 0
            try {
                val controller = LibraryImportController(sources, journal, DocumentExtractor(sources), DocumentRecognizer(object : RecognitionTransport {
                    override suspend fun post(settings: ApiSettings, body: JsonObject): RecognitionHttpResponse {
                        calls++; return if (calls == 1) failed else response("${src.id}:block:1")
                    }
                }), fakePublisher, { ApiSettings(apiKey = "synthetic") }, scope)
                val id = controller.create(listOf(src.id), mapOf(src.id to PageSelection(listOf(1))))
                controller.process(id)
                withTimeout(3000) { journal.jobs.first { it.jobs.single().status == ImportStatus.FAILED } }
                controller.process(id)
                withTimeout(3000) { journal.jobs.first { it.jobs.single().status == ImportStatus.REVIEW_REQUIRED } }
                assertEquals(2, calls)
            } finally { scope.cancel() }
        }
    }
    @Test fun explicitPublicationRetryRefreshesSectionSnapshotAfterTheUserFixesIt() = runBlocking {
        val root = temp.newFolder(); val sources = LibrarySourceStore(File(root, "sources"))
        val src = sources.copy(SourceInput("raw.txt", "text/plain") { "Raw".byteInputStream() }).source
        val journal = LibraryImportJournal(File(root, "imports")); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var target = com.smartcalc.app.data.template.TaskTemplate(name = "Target")
        var publishes = 0
        try {
            val publisher = object : ImportPublisher {
                override fun receipt(commitId: String): ImportReceipt? = null
                override suspend fun publish(intent: PublicationIntent): ImportReceipt {
                    publishes++
                    if (publishes == 1) throw IllegalStateException("capacity")
                    check(intent.expectedSection == target) { "stale section" }
                    return fakePublisher.publish(intent)
                }
            }
            val controller = LibraryImportController(sources, journal, DocumentExtractor(sources), DocumentRecognizer(object : RecognitionTransport {
                override suspend fun post(settings: ApiSettings, body: JsonObject) = error("no network")
            }), publisher, { ApiSettings() }, scope, sectionSnapshot = { target })
            val id = UUID.randomUUID().toString()
            val draft = ImportDraft(UUID.randomUUID().toString(), condition = "c", solution = "s",
                refs = listOf(SourceSegmentRef(src.id, "${src.id}:block:1", SegmentKind.DOCUMENT_BLOCK, 1)))
            journal.save(ImportJob(id, listOf(src.id), mapOf(src.id to PageSelection(listOf(1))),
                status = ImportStatus.REVIEW_REQUIRED, drafts = listOf(draft)))
            try { controller.publish(id, listOf(draft.id), target.id, target.name); fail("capacity") } catch (_: IllegalStateException) { }
            target = target.copy(requirements = "User updated target")
            controller.publish(id, listOf(draft.id), target.id, target.name)
            assertEquals(ImportStatus.READY, journal.jobs.value.jobs.single().status)
        } finally { scope.cancel() }
    }
    @Test fun explicitContinueCanRetryRejectedCheckpointLeftAtBatchBudgetBoundary() = runBlocking {
        val root = temp.newFolder(); val sources = LibrarySourceStore(File(root, "sources"))
        val src = sources.copy(SourceInput("raw.txt", "text/plain") { "Raw".byteInputStream() }).source
        val journal = LibraryImportJournal(File(root, "imports")); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val id = UUID.randomUUID().toString()
        val segment = ExtractedSegment(SourceSegmentRef(src.id, "${src.id}:block:1", SegmentKind.DOCUMENT_BLOCK, 1), "Raw")
        journal.save(ImportJob(id, listOf(src.id), mapOf(src.id to PageSelection(listOf(1))), status = ImportStatus.RECOGNIZING,
            postsUsed = 10, segments = listOf(segment), activeBatch = RecognitionBatch(listOf(segment)), inFlightPost = PostAttempt(10, listOf(segment.ref.segmentId))))
        journal.checkpointResponse(id, RecognitionHttpResponse(422, ""))
        journal.update(id) { it.copy(status = ImportStatus.PAUSED) }
        var posts = 0
        try {
            val controller = LibraryImportController(sources, journal, DocumentExtractor(sources), DocumentRecognizer(object : RecognitionTransport {
                override suspend fun post(settings: ApiSettings, body: JsonObject): RecognitionHttpResponse { posts++; return response(segment.ref.segmentId) }
            }), fakePublisher, { ApiSettings(apiKey = "synthetic") }, scope)
            controller.process(id)
            withTimeout(3000) { journal.jobs.first { it.jobs.single().status == ImportStatus.REVIEW_REQUIRED } }
            assertEquals(1, posts)
        } finally { scope.cancel() }
    }
    @Test fun carryThatCannotFitNextBatchBecomesAnEditableDraftWithoutTruncation() = runBlocking {
        val root = temp.newFolder(); val sources = LibrarySourceStore(File(root, "sources"))
        val src = sources.copy(SourceInput("raw.txt", "text/plain") { "Raw".byteInputStream() }).source
        fun segment(n: Int, length: Int) = ExtractedSegment(SourceSegmentRef(src.id, "${src.id}:block:$n", SegmentKind.DOCUMENT_BLOCK, n), "x".repeat(length))
        val segments = listOf(segment(1, 11000), segment(2, 2000)); val journal = LibraryImportJournal(File(root, "imports"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); var posts = 0
        try {
            val extractor = object : DocumentExtractor(sources) {
                override suspend fun inspect(source: LibrarySource) = 2
                override suspend fun extract(source: LibrarySource, selection: PageSelection, onSegment: suspend (ExtractedSegment) -> Unit): ExtractionResult {
                    segments.forEach { onSegment(it) }; return ExtractionResult(segments, 2)
                }
            }
            val controller = LibraryImportController(sources, journal, extractor, DocumentRecognizer(object : RecognitionTransport {
                override suspend fun post(settings: ApiSettings, body: JsonObject): RecognitionHttpResponse {
                    posts++
                    return RecognitionHttpResponse(200, buildJsonObject { putJsonArray("choices") { add(buildJsonObject { putJsonObject("message") {
                        put("content", """{"drafts":[],"carry":{"text":"${"c".repeat(11500)}","segment_ids":["${segments[0].ref.segmentId}"]}}""")
                    } }) } }.toString())
                }
            }), fakePublisher, { ApiSettings(apiKey = "synthetic") }, scope)
            val id = controller.create(listOf(src.id), mapOf(src.id to PageSelection(listOf(1, 2))))
            controller.process(id)
            withTimeout(3000) { journal.jobs.first { it.jobs.single().status in setOf(ImportStatus.REVIEW_REQUIRED, ImportStatus.FAILED) } }
            val job = journal.jobs.value.jobs.single()
            assertEquals(ImportStatus.REVIEW_REQUIRED, job.status)
            assertEquals(1, posts)
            assertTrue(job.drafts.single().condition.contains("c".repeat(11500)))
            assertTrue(job.drafts.single().condition.contains("x".repeat(2000)))
            assertEquals(segments.map { it.ref }.toSet(), job.drafts.single().refs.toSet())
            assertTrue(job.drafts.single().warnings.any { it.blocking })
        } finally { scope.cancel() }
    }

    @Test fun journalWriteFailureIsVisibleAndDoesNotEscapeWorkerScope() = runBlocking {
        val root = temp.newFolder(); val sources = LibrarySourceStore(File(root, "sources"))
        val src = sources.copy(SourceInput("raw.txt", "text/plain") { ByteArrayInputStream("Raw".toByteArray()) }).source
        val journal = LibraryImportJournal(File(root, "imports")); val escaped = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> escaped.incrementAndGet() })
        try {
            val extractor = object : DocumentExtractor(sources) {
                override suspend fun inspect(source: LibrarySource) = 1
                override suspend fun extract(source: LibrarySource, selection: PageSelection, onSegment: suspend (ExtractedSegment) -> Unit): ExtractionResult {
                    val id = journal.jobs.value.jobs.single().id
                    File(root, "imports/$id/job.json").apply { check(delete()); check(mkdir()) }
                    onSegment(ExtractedSegment(SourceSegmentRef(src.id, "${src.id}:block:1", SegmentKind.DOCUMENT_BLOCK, 1), "Raw"))
                    return ExtractionResult(emptyList(), 1)
                }
            }
            val controller = LibraryImportController(sources, journal, extractor, DocumentRecognizer(object : RecognitionTransport {
                override suspend fun post(settings: ApiSettings, body: JsonObject) = error("no network")
            }), fakePublisher, { ApiSettings() }, scope)
            val id = controller.create(listOf(src.id), mapOf(src.id to PageSelection(listOf(1))))
            controller.process(id)
            withTimeout(3000) { journal.jobs.first { it.error != null } }
            delay(100)
            assertEquals(0, escaped.get())
            assertTrue(sources.originalFile(src.id).isFile)
        } finally { scope.cancel() }
    }

    @Test fun tenPostBudgetPausesAndManualContinueProcessesRemainingSegments() = runBlocking {
        val root = temp.newFolder(); val sources = LibrarySourceStore(File(root, "sources"))
        val src = sources.copy(SourceInput("text.txt", "text/plain") { ByteArrayInputStream("Raw".toByteArray()) }).source
        val segments = (1..12).map { n -> ExtractedSegment(SourceSegmentRef(src.id, "${src.id}:block:$n", SegmentKind.DOCUMENT_BLOCK, n), "x".repeat(11000)) }
        val extractor = object : DocumentExtractor(sources) {
            override suspend fun inspect(source: LibrarySource) = 12
            override suspend fun extract(source: LibrarySource, selection: PageSelection, onSegment: suspend (ExtractedSegment) -> Unit): ExtractionResult {
                val selected = segments.filter { it.ref.ordinal in selection.ordinals }; selected.forEach { onSegment(it) }
                return ExtractionResult(selected, 12)
            }
        }
        var calls = 0
        val transport = object : RecognitionTransport {
            override suspend fun post(settings: ApiSettings, body: JsonObject): RecognitionHttpResponse {
                calls++
                val material = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
                val id = Json.parseToJsonElement(material).jsonObject["segments"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
                return response(id)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val journal = LibraryImportJournal(File(root, "imports"))
            val controller = LibraryImportController(sources, journal, extractor, DocumentRecognizer(transport), fakePublisher,
                { ApiSettings(apiKey = "synthetic") }, scope)
            val id = controller.create(listOf(src.id), mapOf(src.id to PageSelection((1..12).toList())))
            controller.process(id)
            withTimeout(30000) { journal.jobs.first { it.jobs.first().status == ImportStatus.PAUSED } }
            assertEquals(10, calls); assertEquals(10, journal.jobs.value.jobs.single().drafts.size)
            controller.process(id)
            withTimeout(30000) { journal.jobs.first { it.jobs.first().status == ImportStatus.REVIEW_REQUIRED } }
            assertEquals(12, calls); assertEquals(12, journal.jobs.value.jobs.single().drafts.size)
        } finally { scope.cancel() }
    }
    @Test fun unknownPostOutcomeDoesNotAutomaticallyResendOnRecovery() = runBlocking {
        val root = temp.newFolder(); val id = UUID.randomUUID().toString(); val sourceId = UUID.randomUUID().toString()
        val journal = LibraryImportJournal(File(root, "imports"))
        journal.save(ImportJob(id, listOf(sourceId), mapOf(sourceId to PageSelection(listOf(1))), status = ImportStatus.RECOGNIZING,
            inFlightPost = PostAttempt(1, listOf("segment"), null)))
        val restarted = LibraryImportJournal(File(root, "imports")); restarted.recover()
        assertEquals(ImportStatus.INTERRUPTED, restarted.jobs.value.jobs.single().status)
        assertEquals(1, restarted.jobs.value.jobs.single().inFlightPost!!.number)
    }
    @Test fun durableResponseCanBeParsedLocallyAfterRestart() = runBlocking {
        val root = temp.newFolder(); val sources = LibrarySourceStore(File(root, "sources"))
        val src = sources.copy(SourceInput("task.txt", "text/plain") { ByteArrayInputStream("Raw".toByteArray()) }).source
        val journal = LibraryImportJournal(File(root, "imports")); val id = UUID.randomUUID().toString()
        val segment = ExtractedSegment(SourceSegmentRef(src.id, "${src.id}:block:1", SegmentKind.DOCUMENT_BLOCK, 1), "Raw")
        journal.save(ImportJob(id, listOf(src.id), mapOf(src.id to PageSelection(listOf(1))), status = ImportStatus.RECOGNIZING,
            segments = listOf(segment), activeBatch = RecognitionBatch(listOf(segment)), inFlightPost = PostAttempt(1, listOf(segment.ref.segmentId), null)))
        journal.checkpointResponse(id, response(segment.ref.segmentId))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = LibraryImportController(sources, journal, DocumentExtractor(sources), DocumentRecognizer(object : RecognitionTransport {
                override suspend fun post(settings: ApiSettings, body: JsonObject) = error("no resend")
            }), fakePublisher, { error("no credentials") }, scope)
            controller.recover()
            assertEquals(ImportStatus.REVIEW_REQUIRED, journal.jobs.value.jobs.single().status)
            assertEquals(1, journal.jobs.value.jobs.single().drafts.size)
        } finally { scope.cancel() }
    }
    @Test fun corruptJournalBlocksWritesAndDoesNotOverwriteUnknownState() = runBlocking {
        val root = temp.newFolder(); val id = UUID.randomUUID().toString()
        File(root, id).mkdirs(); val file = File(root, "$id/job.json"); file.writeText("corrupt")
        val journal = LibraryImportJournal(root)
        assertNotNull(journal.jobs.value.error)
        try { journal.save(ImportJob(UUID.randomUUID().toString(), emptyList(), emptyMap())); fail("read only") } catch (_: IllegalStateException) { }
        assertEquals("corrupt", file.readText())
    }
    @Test fun splitAndMergePreserveAllReferencesAndRequireBoundaryReview() {
        val sourceId = UUID.randomUUID().toString()
        val refs = (1..2).map { SourceSegmentRef(sourceId, "$sourceId:page:$it", SegmentKind.PDF_PAGE, it) }
        val draft = ImportDraft(UUID.randomUUID().toString(), condition = "A and B", solution = "Solution", refs = refs)
        val parts = ImportDraftEditor.split(draft, listOf(draft.copy(id = UUID.randomUUID().toString(), refs = listOf(refs[0])),
            draft.copy(id = UUID.randomUUID().toString(), refs = listOf(refs[1]))))
        assertTrue(parts.all { it.warnings.any { warning -> warning.blocking } })
        assertEquals(refs.toSet(), ImportDraftEditor.merge(parts).refs.toSet())
    }
    @Test fun recoveryDoesNotResumeExplicitlyCancelledSavedResponse() = runBlocking {
        val root = temp.newFolder(); val sources = LibrarySourceStore(File(root, "sources"))
        val src = sources.copy(SourceInput("task.txt", "text/plain") { ByteArrayInputStream("Raw".toByteArray()) }).source
        val journal = LibraryImportJournal(File(root, "imports")); val id = UUID.randomUUID().toString()
        val segment = ExtractedSegment(SourceSegmentRef(src.id, "${src.id}:block:1", SegmentKind.DOCUMENT_BLOCK, 1), "Raw")
        journal.save(ImportJob(id, listOf(src.id), mapOf(src.id to PageSelection(listOf(1))), status = ImportStatus.RECOGNIZING,
            segments = listOf(segment), activeBatch = RecognitionBatch(listOf(segment)), inFlightPost = PostAttempt(1, listOf(segment.ref.segmentId))))
        journal.checkpointResponse(id, response(segment.ref.segmentId))
        journal.update(id) { it.copy(status = ImportStatus.CANCELLED, attempt = it.attempt + 1) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            LibraryImportController(sources, journal, DocumentExtractor(sources), DocumentRecognizer(object : RecognitionTransport {
                override suspend fun post(settings: ApiSettings, body: JsonObject) = error("no network")
            }), fakePublisher, { error("no credentials") }, scope).recover()
            assertEquals(ImportStatus.CANCELLED, journal.jobs.value.jobs.single().status)
            assertTrue(journal.jobs.value.jobs.single().drafts.isEmpty())
        } finally { scope.cancel() }
    }
    @Test fun continueFromTerminalStateWaitsForForegroundCleanup() = runBlocking {
        supervisorScope {
            val root = temp.newFolder(); val sources = LibrarySourceStore(File(root, "sources"))
            val src = sources.copy(SourceInput("task.txt", "text/plain") { ByteArrayInputStream("Задача 1\nУсловие\nc\nРешение\ns\nОтвет\n2".toByteArray()) }).source
            val journal = LibraryImportJournal(File(root, "imports")); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val stops = AtomicInteger()
            try {
                val controller = LibraryImportController(sources, journal, DocumentExtractor(sources), DocumentRecognizer(object : RecognitionTransport {
                    override suspend fun post(settings: ApiSettings, body: JsonObject) = error("local import")
                }), fakePublisher, { ApiSettings() }, scope, stopForeground = {
                    if (stops.incrementAndGet() == 1) { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
                })
                val id = controller.create(listOf(src.id), mapOf(src.id to PageSelection((1..7).toList())))
                controller.process(id)
                withTimeout(3000) { journal.jobs.first { it.jobs.single().status == ImportStatus.REVIEW_REQUIRED } }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                val resume = async { controller.process(id) }
                yield(); release.countDown(); resume.await()
            } finally { release.countDown(); scope.cancel() }
        }
    }
    @Test fun activeTimeBudgetAlsoInterruptsAnUnfinishedPost() = runBlocking {
        val root = temp.newFolder(); val sources = LibrarySourceStore(File(root, "sources"))
        val src = sources.copy(SourceInput("raw.txt", "text/plain") { ByteArrayInputStream("Raw text".toByteArray()) }).source
        val journal = LibraryImportJournal(File(root, "imports")); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var calls = 0
        try {
            val controller = LibraryImportController(sources, journal, DocumentExtractor(sources), DocumentRecognizer(object : RecognitionTransport {
                override suspend fun post(settings: ApiSettings, body: JsonObject): RecognitionHttpResponse { calls++; delay(60000); return response("never") }
            }), fakePublisher, { ApiSettings(apiKey = "synthetic") }, scope, activeTimeoutMillis = 2000)
            val id = controller.create(listOf(src.id), mapOf(src.id to PageSelection(listOf(1))))
            controller.process(id)
            withTimeout(4000) { journal.jobs.first { it.jobs.single().status == ImportStatus.PAUSED } }
            assertEquals(1, calls)
            assertNull(journal.jobs.value.jobs.single().inFlightPost!!.responseRelativePath)
        } finally { scope.cancel() }
    }
    @Test fun waitingForSolverIsNotAResumableTerminalPause() = runBlocking {
        val root = temp.newFolder(); val sources = LibrarySourceStore(File(root, "sources"))
        val src = sources.copy(SourceInput("raw.txt", "text/plain") { ByteArrayInputStream("Raw text".toByteArray()) }).source
        val journal = LibraryImportJournal(File(root, "imports")); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val solving = MutableStateFlow(true)
        try {
            val controller = LibraryImportController(sources, journal, DocumentExtractor(sources), DocumentRecognizer(object : RecognitionTransport {
                override suspend fun post(settings: ApiSettings, body: JsonObject) = error("solver has priority")
            }), fakePublisher, { ApiSettings() }, scope, solutionActive = solving)
            val id = controller.create(listOf(src.id), mapOf(src.id to PageSelection(listOf(1))))
            controller.process(id)
            withTimeout(3000) { journal.jobs.first { it.jobs.single().waitingForSolution } }
            controller.cancel(id)
            assertFalse(journal.jobs.value.jobs.single().waitingForSolution)
            assertEquals(ImportStatus.CANCELLED, journal.jobs.value.jobs.single().status)
        } finally { scope.cancel() }
    }
}
