package com.smartcalc.app.data.library.agent

import com.smartcalc.app.data.library.LibraryMetadata
import com.smartcalc.app.data.library.LibraryVerification
import com.smartcalc.app.data.library.source.*
import com.smartcalc.app.data.template.TaskTemplate
import com.smartcalc.app.data.template.TemplateExample
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LibraryToolsIndexTest {
    private val id = "11111111-1111-1111-1111-111111111111"
    private val sourceId = "22222222-2222-2222-2222-222222222222"
    private fun example() = TemplateExample("Резистор 2 Ом. Найти ток.", solution = "I=U/R", answer = "3 А", library = LibraryMetadata(id = id))
    private fun catalog(ex: TemplateExample = example()) = LibraryCatalog(
        listOf(TaskTemplate(id = "section", name = "Электричество", examples = listOf(ex), updated = 0)),
    )
    private fun LibraryCatalog.withExample(ex: TemplateExample) = copy(sections = sections.map { it.copy(examples = listOf(ex)) })
    private fun JsonObject.status() = getValue("status").jsonPrimitive.content
    private suspend fun LibraryTools.search() = execute("search_examples", """{"query":"резистор"}""")
    private suspend fun LibraryTools.read(version: Int = 1) = execute("read_example", """{"id":"$id","version":$version}""")

    @Test fun sharedIndexDoesNotShareReadPermissionsOrSnapshotsBetweenRequests() = runBlocking {
        val index = LibrarySearchIndex()
        val current = catalog()
        val first = LibraryTools(index) { current }
        val second = LibraryTools(index) { current.copy() }
        assertTrue(first.hasExamples())
        assertEquals("matches", first.search().status())
        assertEquals("ok", first.read().status())
        assertEquals("not_allowed", second.read().status())
        assertTrue(second.examples.isEmpty())
        assertEquals("matches", second.search().status())
        assertEquals("ok", second.read().status())
        assertEquals("already_read", first.read().status())
        assertEquals(1, first.examples.size)
        assertEquals(1, second.examples.size)
    }

    @Test fun cachedSearchAndReadRecheckVersionAndKeepPreviouslyReadSnapshot() = runBlocking {
        val index = LibrarySearchIndex()
        val base = catalog()
        var current = base
        val tools = LibraryTools(index) { current }
        assertTrue(tools.hasExamples())
        tools.search()
        assertEquals("ok", tools.read().status())
        val ex = base.sections.single().examples.single()
        current = base.withExample(ex.copy(answer = "4 А", library = ex.library!!.copy(version = 2)))
        assertEquals("stale", tools.read().status())
        assertEquals("not_allowed", tools.read(2).status())
        val updatedHit = tools.search()["matches"]!!.jsonArray.single().jsonObject
        assertEquals(2, updatedHit["version"]!!.jsonPrimitive.int)
        assertEquals("ok", tools.read(2).status())
        assertEquals(listOf("3 А", "4 А"), tools.examples.map { it.answer })
        assertEquals(listOf(1, 2), tools.examples.map { it.version })
    }

    @Test fun disablingRejectingOrDeletingExampleBlocksEvenPreviouslyReadVersion() = runBlocking {
        val index = LibrarySearchIndex()
        val base = catalog()
        val ex = base.sections.single().examples.single()
        val variants = listOf(
            base.withExample(ex.copy(library = ex.library!!.copy(enabled = false))),
            base.withExample(ex.copy(library = ex.library!!.copy(verification = LibraryVerification.REJECTED))),
            base.copy(sections = emptyList()),
        )
        for (excluded in variants) {
            var current = base
            val tools = LibraryTools(index) { current }
            assertEquals("matches", tools.search().status())
            assertEquals("ok", tools.read().status())
            current = excluded
            assertEquals("stale", tools.read().status())
            assertEquals("no_match", tools.search().status())
            assertFalse(tools.hasExamples())
            current = base
            assertEquals("matches", tools.search().status())
            assertEquals("already_read", tools.read().status())
            assertEquals("3 А", tools.examples.single().answer)
        }
    }

    @Test fun sourceAvailabilityAndErrorsAreRecheckedAfterCacheHit() = runBlocking {
        val index = LibrarySearchIndex()
        val ref = SourceSegmentRef(sourceId, "page", SegmentKind.PDF_PAGE, 1)
        val ex = example().let { it.copy(library = it.library!!.copy(sourceRefs = listOf(ref))) }
        val source = LibrarySource(sourceId, "book.pdf", SourceFormat.PDF, 10, "a".repeat(64), "$sourceId/original", 1)
        val base = catalog(ex).copy(sources = SourceCatalog(listOf(source)))
        var current = base
        val variants = listOf(
            base.copy(sources = SourceCatalog(listOf(source.copy(status = SourceStatus.DISABLED)))) to "stale",
            base.copy(sources = SourceCatalog(listOf(source.copy(status = SourceStatus.DELETED)))) to "stale",
            base.copy(sources = SourceCatalog()) to "stale",
            base.copy(sources = SourceCatalog(error = "source error")) to "unavailable",
            base.copy(error = "library error") to "unavailable",
        )
        for ((excluded, status) in variants) {
            current = base
            val tools = LibraryTools(index) { current }
            assertEquals("matches", tools.search().status())
            assertEquals("ok", tools.read().status())
            current = excluded
            assertEquals(status, tools.read().status())
            assertEquals(if (status == "stale") "no_match" else "unavailable", tools.search().status())
            current = base
            assertEquals("matches", tools.search().status())
            assertEquals("already_read", tools.read().status())
        }
    }

    @Test fun sourceRenameAndVerificationChangeAppearInFreshRequestUsingSameIndex() = runBlocking {
        val index = LibrarySearchIndex()
        val ref = SourceSegmentRef(sourceId, "page", SegmentKind.PDF_PAGE, 1)
        val ex = example().let { it.copy(library = it.library!!.copy(sourceRefs = listOf(ref))) }
        val source = LibrarySource(sourceId, "old.pdf", SourceFormat.PDF, 10, "a".repeat(64), "$sourceId/original", 1)
        var current = catalog(ex).copy(sources = SourceCatalog(listOf(source)))
        val first = LibraryTools(index) { current }
        first.search()
        assertEquals("ok", first.read().status())
        current = current.withExample(ex.copy(library = ex.library!!.copy(verification = LibraryVerification.VERIFIED_BY_USER)))
            .copy(sources = SourceCatalog(listOf(source.copy(name = "new.pdf"))))
        val second = LibraryTools(index) { current }
        assertEquals("VERIFIED_BY_USER", second.search()["matches"]!!.jsonArray.single().jsonObject["verification"]!!.jsonPrimitive.content)
        assertEquals("ok", second.read().status())
        assertEquals("new.pdf", second.examples.single().sources.single().name)
        assertEquals("old.pdf", first.examples.single().sources.single().name)
        assertEquals(LibraryVerification.VERIFIED_BY_USER, second.examples.single().verification)
    }

    @Test fun failedCatalogLoadInvalidatesWarmIndexWithoutServingStaleData() = runBlocking {
        val index = LibrarySearchIndex()
        val current = catalog()
        val prepared = index.entries(current)
        var failed = true
        val tools = LibraryTools(index) { if (failed) throw IOException("unreadable catalog") else current }
        assertEquals("unavailable", tools.search().status())
        failed = false
        assertEquals("matches", tools.search().status())
        assertNotSame(prepared, index.entries(current))
        assertEquals("ok", tools.read().status())
    }
}
