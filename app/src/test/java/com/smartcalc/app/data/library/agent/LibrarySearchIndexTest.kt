package com.smartcalc.app.data.library.agent

import com.smartcalc.app.data.library.LibraryMetadata
import com.smartcalc.app.data.library.LibraryVerification
import com.smartcalc.app.data.library.source.*
import com.smartcalc.app.data.search.TaskSearch
import com.smartcalc.app.data.template.TaskTemplate
import com.smartcalc.app.data.template.TemplateExample
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibrarySearchIndexTest {
    @get:Rule val temporary = TemporaryFolder()
    private val firstId = "11111111-1111-1111-1111-111111111111"
    private val secondId = "22222222-2222-2222-2222-222222222222"
    private val sourceId = "33333333-3333-3333-3333-333333333333"
    private fun example(id: String = firstId, condition: String = "Резистор 2 Ом, найти ток") =
        TemplateExample(condition, solution = "I=U/R", answer = "3 А", library = LibraryMetadata(id = id))
    private fun catalog(vararg examples: TemplateExample) = LibraryCatalog(
        listOf(TaskTemplate(id = "section", name = "Электричество", examples = examples.toList(), updated = 0)),
    )
    private fun LibraryCatalog.withExamples(vararg examples: TemplateExample) =
        copy(sections = listOf(sections.single().copy(examples = examples.toList())))
    private fun source(status: SourceStatus = SourceStatus.ACTIVE) = LibrarySource(
        sourceId, "book.pdf", SourceFormat.PDF, 10, "a".repeat(64), "$sourceId/original", 2, status,
    )

    @Test fun unchangedAndValueEqualCatalogReusePreparedEntries() {
        val index = LibrarySearchIndex()
        val current = catalog(example())
        val prepared = index.entries(current)
        assertEquals(firstId, prepared.single().library!!.id)
        assertSame(prepared, index.entries(current))
        val equalCopy = current.copy(sections = current.sections.map { it.copy(examples = it.examples.map { ex -> ex.copy() }) })
        assertSame(prepared, index.entries(equalCopy))
    }

    @Test fun changedVersionReplacesOldSearchTextAndMetadata() {
        val index = LibrarySearchIndex()
        val old = example()
        val current = catalog(old)
        val prepared = index.entries(current)
        val changed = old.copy(condition = "Конденсатор, найти заряд", library = old.library!!.copy(version = 2))
        val updated = index.entries(current.withExamples(changed))
        assertTrue(TaskSearch.search(updated, "резистор").isEmpty())
        assertEquals(2, TaskSearch.search(updated, "конденсатор").single().entry.library!!.version)
        assertNotSame(prepared, updated)
    }

    @Test fun contentChangeWithoutVersionBumpDoesNotReuseOldTerms() {
        val index = LibrarySearchIndex()
        val old = example()
        val current = catalog(old)
        index.entries(current)
        val updated = index.entries(current.withExamples(old.copy(condition = "Конденсатор, найти заряд")))
        assertTrue(TaskSearch.search(updated, "резистор").isEmpty())
        assertEquals(firstId, TaskSearch.search(updated, "конденсатор").single().entry.library!!.id)
    }

    @Test fun answerChangesAndDeletedExamplesInvalidateTheIndex() {
        val index = LibrarySearchIndex()
        val old = example()
        val current = catalog(old)
        index.entries(current)
        val changed = index.entries(current.withExamples(old.copy(answer = "ОсобаяЁмкость")))
        assertEquals(firstId, TaskSearch.search(changed, "ОсобаяЁмкость").single().entry.library!!.id)
        assertTrue(index.entries(current.withExamples()).isEmpty())
    }

    @Test fun enabledAndVerificationChangesApplyWithoutVersionChange() {
        val index = LibrarySearchIndex()
        val old = example()
        val current = catalog(old)
        index.entries(current)
        assertTrue(index.entries(current.withExamples(old.copy(library = old.library!!.copy(enabled = false)))).isEmpty())
        assertTrue(index.entries(current.withExamples(old.copy(library = old.library!!.copy(verification = LibraryVerification.REJECTED)))).isEmpty())
        val verified = index.entries(current.withExamples(old.copy(library = old.library!!.copy(verification = LibraryVerification.VERIFIED_BY_USER))))
        assertEquals(LibraryVerification.VERIFIED_BY_USER, verified.single().library!!.verification)
    }

    @Test fun sectionRenameAndOrderAreReflectedInSearch() {
        val index = LibrarySearchIndex()
        val current = catalog(example(condition = "Резистор номер 1"), example(secondId, "Резистор номер 2"))
        assertEquals(firstId, TaskSearch.search(index.entries(current), "резистор").first().entry.library!!.id)
        val renamed = current.copy(sections = current.sections.map { it.copy(name = "Цепи", examples = it.examples.reversed()) })
        val hits = TaskSearch.search(index.entries(renamed), "резистор")
        assertEquals(listOf(secondId, firstId), hits.map { it.entry.library!!.id })
        assertTrue(hits.all { it.entry.type == "Цепи" })
    }

    @Test fun sourceDisableDeleteRemovalAndRestoreInvalidateAvailability() {
        val index = LibrarySearchIndex()
        val ref = SourceSegmentRef(sourceId, "page", SegmentKind.PDF_PAGE, 1)
        val ex = example().let { it.copy(library = it.library!!.copy(sourceRefs = listOf(ref))) }
        val current = catalog(ex).copy(sources = SourceCatalog(listOf(source())))
        assertEquals(firstId, index.entries(current).single().library!!.id)
        for (status in listOf(SourceStatus.DISABLED, SourceStatus.DELETED)) {
            assertTrue(index.entries(current.copy(sources = SourceCatalog(listOf(source(status))))).isEmpty())
        }
        assertTrue(index.entries(current.copy(sources = SourceCatalog())).isEmpty())
        assertEquals(firstId, index.entries(current).single().library!!.id)
    }

    @Test fun catalogErrorsFailClosedAndRecoveryRebuilds() {
        val index = LibrarySearchIndex()
        val current = catalog(example())
        val prepared = index.entries(current)
        assertTrue(index.entries(current.copy(error = "unreadable library")).isEmpty())
        val recovered = index.entries(current)
        assertEquals(firstId, recovered.single().library!!.id)
        assertNotSame(prepared, recovered)
        assertTrue(index.entries(current.copy(sources = SourceCatalog(error = "unreadable sources"))).isEmpty())
        assertNotSame(recovered, index.entries(current))
    }

    @Test fun imageReplacementWithSameSizeAndTimestampInvalidatesDeduplication() {
        val index = LibrarySearchIndex()
        val a = temporary.newFile("a.img").apply { writeBytes(byteArrayOf(1, 2)) }
        val b = temporary.newFile("b.img").apply { writeBytes(byteArrayOf(1, 2)) }
        val current = catalog(example().copy(conditionImages = listOf(a.path)), example(secondId).copy(conditionImages = listOf(b.path)))
        assertEquals(1, index.entries(current).size)
        val timestamp = b.lastModified()
        b.writeBytes(byteArrayOf(3, 4))
        assertTrue(b.setLastModified(timestamp))
        assertEquals(2, index.entries(current).size)
    }

    @Test fun missingAndRestoredImageInvalidateDeduplication() {
        val index = LibrarySearchIndex()
        val a = temporary.newFile("a.img").apply { writeBytes(byteArrayOf(1, 2)) }
        val b = temporary.newFile("b.img").apply { writeBytes(byteArrayOf(1, 2)) }
        val current = catalog(example().copy(conditionImages = listOf(a.path)), example(secondId).copy(conditionImages = listOf(b.path)))
        assertEquals(1, index.entries(current).size)
        assertTrue(b.delete())
        assertEquals(2, index.entries(current).size)
        b.writeBytes(byteArrayOf(1, 2))
        assertEquals(1, index.entries(current).size)
    }

    @Test fun excludedImagesDoNotInvalidateOrParticipateInPreparedIndex() {
        val image = temporary.newFile("excluded.img")
        val excluded = example(secondId, "Конденсатор").copy(conditionImages = listOf(image.path))
        val ref = SourceSegmentRef(sourceId, "page", SegmentKind.PDF_PAGE, 1)
        val variants = listOf(
            catalog(example(), excluded.copy(library = excluded.library!!.copy(enabled = false))),
            catalog(example(), excluded.copy(library = excluded.library!!.copy(verification = LibraryVerification.REJECTED))),
            catalog(example(), excluded.copy(library = excluded.library!!.copy(sourceRefs = listOf(ref))))
                .copy(sources = SourceCatalog(listOf(source(SourceStatus.DISABLED)))),
        )
        for (current in variants) {
            image.writeBytes(byteArrayOf(1, 2))
            val index = LibrarySearchIndex()
            val prepared = index.entries(current)
            assertEquals(firstId, prepared.single().library!!.id)
            image.writeBytes(byteArrayOf(3, 4))
            assertSame(prepared, index.entries(current))
        }
    }

    @Test fun concurrentCallersReuseOnePublishedIndex() {
        val index = LibrarySearchIndex()
        val current = catalog(example())
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..4).map { pool.submit<List<com.smartcalc.app.data.search.SearchEntry>> { start.await(); index.entries(current) } }
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(firstId, results.first().single().library!!.id)
            results.drop(1).forEach { assertSame(results.first(), it) }
        } finally { pool.shutdownNow() }
    }
}
