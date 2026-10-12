package com.smartcalc.app.data.library.importer

import com.smartcalc.app.data.library.source.*
import com.smartcalc.app.data.library.LibraryVerification
import com.smartcalc.app.data.template.*
import com.smartcalc.app.data.search.*
import java.io.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportPublicationTest {
    @get:Rule val temp = TemporaryFolder()
    private suspend fun source(sources: LibrarySourceStore): LibrarySource {
        val result = sources.copy(SourceInput("task.txt", "text/plain") { ByteArrayInputStream("Condition".toByteArray()) }).source
        sources.setSegmentCount(result.id, 1); return result
    }
    private fun intent(src: LibrarySource, photo: String? = null): PublicationIntent {
        val job = UUID.randomUUID().toString(); val draft = UUID.randomUUID().toString()
        return PublicationIntent(UUID.randomUUID().toString(), job, UUID.randomUUID().toString(), "Type", items = listOf(
            PublicationItem(UUID.nameUUIDFromBytes("library-import:$job:$draft".toByteArray()).toString(), draft, 1,
                "Condition", "Author solution", "42", photo?.let { listOf(it) }.orEmpty(), emptyList(),
                listOf(SourceSegmentRef(src.id, "${src.id}:block:1", SegmentKind.DOCUMENT_BLOCK, 1)))))
    }
    @Test fun repeatAfterSectionDeletionDoesNotResurrectTasks() = runBlocking {
        val root = temp.newFolder(); val sources = LibrarySourceStore(File(root, "sources")); val src = source(sources)
        val library = TemplateStore(root, TemplateKind.LIBRARY); val publisher = TemplateImportPublisher(library, sources)
        val request = intent(src); val receipt = publisher.publish(request)
        assertEquals(receipt, publisher.publish(request)); assertEquals(1, library.items.value.single().examples.size)
        library.delete(receipt.sectionId, library.items.value.single())
        sources.deleteOriginal(src.id)
        val restored = TemplateStore(root, TemplateKind.LIBRARY)
        assertEquals(receipt, TemplateImportPublisher(restored, sources).publish(request))
        assertTrue(restored.items.value.isEmpty())
    }
    @Test fun publicationCopiesPhotosAndReceiptSurvivesMissingSourceMedia() = runBlocking {
        val root = temp.newFolder(); val sources = LibrarySourceStore(File(root, "sources")); val src = source(sources)
        val photo = File(sources.originalFile(src.id).parentFile, "media.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, 1, 2, 3)) }
        val library = TemplateStore(root, TemplateKind.LIBRARY); val publisher = TemplateImportPublisher(library, sources)
        val request = intent(src, photo.absolutePath); val receipt = publisher.publish(request)
        val example = library.items.value.single().examples.single()
        assertNotEquals(photo.absolutePath, example.conditionImages.single())
        photo.delete(); sources.deleteOriginal(src.id)
        assertArrayEquals(byteArrayOf(-1, -40, -1, 1, 2, 3), File(example.conditionImages.single()).readBytes())
        assertEquals(receipt, publisher.publish(request))
        assertEquals("42", example.answer)
        assertEquals(LibraryVerification.UNVERIFIED, example.library!!.verification)
    }
    @Test fun reusedCommitWithChangedPayloadOrMediaIsRejected() = runBlocking {
        val root = temp.newFolder(); val sources = LibrarySourceStore(File(root, "sources")); val src = source(sources)
        val photo = File(sources.originalFile(src.id).parentFile, "media.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, 1)) }
        val library = TemplateStore(root, TemplateKind.LIBRARY); val publisher = TemplateImportPublisher(library, sources)
        val request = intent(src, photo.absolutePath); publisher.publish(request)
        try { publisher.publish(request.copy(sectionName = "changed")); fail("changed payload") } catch (_: IllegalStateException) { }
        photo.writeBytes(byteArrayOf(-1, -40, -1, 2))
        try { publisher.publish(request); fail("changed media") } catch (_: IllegalStateException) { }
        assertEquals(1, library.items.value.single().examples.size)
    }
    @Test fun snapshotFailureLeavesLibraryAndDraftMediaUnchanged() = runBlocking {
        val root = temp.newFolder(); val sources = LibrarySourceStore(File(root, "sources")); val src = source(sources)
        val library = TemplateStore(root, TemplateKind.LIBRARY); val publisher = TemplateImportPublisher(library, sources)
        File(root, "templates.json.tmp").mkdir()
        val request = intent(src)
        try { publisher.publish(request); fail("disk write") } catch (_: IOException) { }
        assertTrue(library.items.value.isEmpty()); assertNull(library.importReceipt(request.commitId))
        assertTrue(sources.originalFile(src.id).isFile)
    }
    @Test fun standaloneAnswerSurvivesAllExportFormatsAndOpening() = runBlocking {
        val root = temp.newFolder(); val sources = LibrarySourceStore(File(root, "sources")); val src = source(sources)
        val library = TemplateStore(root, TemplateKind.LIBRARY)
        TemplateImportPublisher(library, sources).publish(intent(src))
        val entries = TaskSearch.entries(library.items.value, emptyList(), emptyList(), sources.state.value)
        assertEquals("42", FoundSolution.of(entries.single()).answer)
        for (format in TemplateFormat.entries) {
            val out = ByteArrayOutputStream(); library.export(null, out, format)
            val imported = TemplateStore(temp.newFolder()); imported.import(ByteArrayInputStream(out.toByteArray()))
            assertEquals("42", imported.items.value.single().examples.single().answer)
            assertNull(imported.items.value.single().examples.single().library)
        }
    }
    @Test fun disabledOrDeletedSourceCannotBeRevivedThroughHistorySearch() = runBlocking {
        val root = temp.newFolder(); val sources = LibrarySourceStore(File(root, "sources")); val src = source(sources)
        val library = TemplateStore(root, TemplateKind.LIBRARY)
        TemplateImportPublisher(library, sources).publish(intent(src))
        val opened = FoundSolution.of(TaskSearch.entries(library.items.value, emptyList(), emptyList(), sources.state.value).single())
        sources.setEnabled(src.id, false)
        assertTrue(TaskSearch.entries(library.items.value, emptyList(), listOf(opened), sources.state.value).isEmpty())
        sources.deleteOriginal(src.id)
        assertTrue(TaskSearch.entries(library.items.value, emptyList(), listOf(opened), sources.state.value).isEmpty())
        assertEquals("42", opened.answer)
    }
    @Test fun answerEditMakesVersionAndArchivesItsPreviousValue() = runBlocking {
        val root = temp.newFolder(); val sources = LibrarySourceStore(File(root, "sources")); val src = source(sources)
        val library = TemplateStore(root, TemplateKind.LIBRARY)
        TemplateImportPublisher(library, sources).publish(intent(src))
        val section = library.items.value.single(); val ex = section.examples.single()
        library.save(section.id, section.name, "", listOf(ExampleDraft(ex.condition, emptyList(), ex.solution, emptyList(),
            libraryId = ex.library!!.id, expectedVersion = 1, answer = "43")), expectedSection = section)
        val updated = library.items.value.single().examples.single()
        assertEquals(2, updated.library!!.version); assertEquals("42", updated.library!!.previousVersions.single().answer)
        assertEquals(ex.library!!.sourceRefs, updated.library!!.sourceRefs)
    }
    @Test fun fiveHundredTaskLimitRejectsAdditionalPublicationWithoutChangingData() = runBlocking {
        val root = temp.newFolder(); val sources = LibrarySourceStore(File(root, "sources")); val src = source(sources)
        val library = TemplateStore(root, TemplateKind.LIBRARY); val publisher = TemplateImportPublisher(library, sources)
        val request = intent(src)
        val many = request.copy(items = (1..500).map { n -> request.items.single().copy(
            exampleId = UUID.randomUUID().toString(), draftId = UUID.randomUUID().toString(), condition = "Condition $n") })
        publisher.publish(many)
        try { publisher.publish(intent(src)); fail("501") } catch (_: IllegalArgumentException) { }
        assertEquals(500, library.items.value.single().examples.size)
    }
    @Test fun staleSectionAndFutureEnvelopeAreBlocked() = runBlocking {
        val root = temp.newFolder(); val sources = LibrarySourceStore(File(root, "sources")); val src = source(sources)
        val library = TemplateStore(root, TemplateKind.LIBRARY); val publisher = TemplateImportPublisher(library, sources)
        val request = intent(src); publisher.publish(request)
        val next = intent(src).copy(sectionId = request.sectionId, expectedSection = null)
        try { publisher.publish(next); fail("stale section") } catch (_: IllegalStateException) { }
        val file = File(root, "templates.json"); file.writeText("{\"schemaVersion\":99,\"sections\":[]}")
        assertNotNull(TemplateStore(root, TemplateKind.LIBRARY).loadError.value)
        assertTrue(file.readText().contains("99"))
    }
    @Test fun emptyAnswerPreservesLegacyContentFingerprint() {
        assertEquals("content-v1:e1fa406b28efb577110ce16c5535a14d4d6b28af7d36acacee4fdc317d8374a3",
            ExampleFingerprint.key(TemplateExample(condition = "condition", solution = "solution")))
    }
}
