package com.smartcalc.app.data.library.source

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.smartcalc.app.data.library.LibraryMetadata
import com.smartcalc.app.data.library.LibraryVerification
import com.smartcalc.app.data.template.TaskTemplate
import com.smartcalc.app.data.template.TemplateExample
import com.smartcalc.app.data.template.TemplateStore
import com.smartcalc.app.data.template.TemplateKind
import com.smartcalc.app.data.template.ExampleDraft
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class LibrarySourceStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private fun input(text: String, name: String = "tasks.txt") =
        SourceInput(name, "text/plain") { ByteArrayInputStream(text.toByteArray()) }

    @Test fun copySurvivesRestartAndDeduplicatesBytes() = runBlocking {
        val root = temporaryFolder.newFolder()
        val first = LibrarySourceStore(root).copy(input("Условие"))
        val restarted = LibrarySourceStore(root)
        val second = restarted.copy(input("Условие", "renamed.txt"))
        assertEquals(first.source.id, second.source.id)
        assertTrue(second.duplicate)
        assertEquals(1, restarted.state.value.sources.size)
        assertEquals("Условие", restarted.originalFile(first.source.id).readText())
    }
    @Test fun sameNameDoesNotIdentifyDifferentContent() = runBlocking {
        val store = LibrarySourceStore(temporaryFolder.newFolder())
        assertNotEquals(store.copy(input("A")).source.id, store.copy(input("B")).source.id)
    }
    @Test fun copyFailureDoesNotPublishOrLeakOriginal() = runBlocking {
        val root = temporaryFolder.newFolder()
        val store = LibrarySourceStore(root)
        val failing = SourceInput("tasks.txt", "text/plain") { object : InputStream() {
            override fun read(): Int = throw IOException("injected failure")
        } }
        try { store.copy(failing); fail("copy should fail") } catch (_: IOException) { }
        assertTrue(store.state.value.sources.isEmpty())
        assertTrue(root.walkTopDown().filter { it.isFile }.none())
    }
    @Test fun manifestFailureRollsBackCopy() = runBlocking {
        val root = temporaryFolder.newFolder()
        val store = LibrarySourceStore(root)
        File(root, "sources.json").mkdir()
        try { store.copy(input("A")); fail("write should fail") } catch (_: Exception) { }
        assertTrue(store.state.value.sources.isEmpty())
        assertTrue(root.walkTopDown().none { it.isFile && it.name == "original" })
    }
    @Test fun missingOrCorruptOriginalIsRestoredByExplicitRepeat() = runBlocking {
        val root = temporaryFolder.newFolder()
        val store = LibrarySourceStore(root)
        val first = store.copy(input("Original"))
        store.originalFile(first.source.id).writeText("Corruption")
        assertEquals(first.source.id, store.copy(input("Original")).source.id)
        assertEquals("Original", store.originalFile(first.source.id).readText())
        store.deleteOriginal(first.source.id)
        assertEquals(SourceStatus.DELETED, store.state.value.sources.single().status)
        assertEquals(first.source.id, store.copy(input("Original")).source.id)
        assertEquals(SourceStatus.ACTIVE, store.state.value.sources.single().status)
    }
    @Test fun disablingDoesNotDestroyOriginal() = runBlocking {
        val root = temporaryFolder.newFolder()
        val store = LibrarySourceStore(root)
        val source = store.copy(input("Keep")).source
        store.setEnabled(source.id, false)
        assertEquals(SourceStatus.DISABLED, LibrarySourceStore(root).state.value.sources.single().status)
        assertEquals("Keep", store.originalFile(source.id).readText())
    }
    @Test fun corruptCatalogBlocksMutationAndPreservesBytes() = runBlocking {
        val root = temporaryFolder.newFolder()
        File(root, "sources.json").writeText("broken")
        val store = LibrarySourceStore(root)
        assertNotNull(store.state.value.error)
        try { store.copy(input("A")); fail("catalog must be read only") } catch (_: IllegalStateException) { }
        assertEquals("broken", File(root, "sources.json").readText())
    }
    @Test fun rejectsTraversalAndFutureSchema() = runBlocking {
        val root = temporaryFolder.newFolder()
        val source = LibrarySourceStore(root).copy(input("A")).source
        val manifest = File(root, "sources.json")
        manifest.writeText(manifest.readText().replace(source.relativePath, "../outside"))
        assertNotNull(LibrarySourceStore(root).state.value.error)
        manifest.writeText("{\"schemaVersion\":99,\"sources\":[]}")
        assertNotNull(LibrarySourceStore(root).state.value.error)
    }
    @Test fun rejectsUnsupportedLegacyWordAndBrokenZip() = runBlocking {
        val store = LibrarySourceStore(temporaryFolder.newFolder())
        for (name in listOf("old.doc", "broken.docx")) {
            try { store.copy(input("fake", name)); fail("unsupported format") } catch (_: IllegalArgumentException) { }
        }
        assertTrue(store.state.value.sources.isEmpty())
    }
    @Test fun fileLimitUsesActualBytesAndDoesNotAllocateWholeSource() = runBlocking {
        val store = LibrarySourceStore(temporaryFolder.newFolder())
        val huge = SourceInput("large.txt", "text/plain") { object : InputStream() {
            var left = 100L * 1024 * 1024 + 1
            override fun read(): Int = if (left-- > 0) 65 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val count = minOf(left, len.toLong()).toInt()
                b.fill(65, off, off + count); left -= count
                return count
            }
        } }
        try { store.copy(huge); fail("actual limit must apply") } catch (_: IllegalArgumentException) { }
        assertTrue(store.state.value.sources.isEmpty())
    }
    @Test fun ordinaryEditingPreservesCurrentAndArchivedSourceReferences() = runBlocking {
        val root = temporaryFolder.newFolder()
        val sourceId = UUID.randomUUID().toString()
        val ref = SourceSegmentRef(sourceId, "$sourceId:page:2", SegmentKind.PDF_PAGE, 2)
        val metadata = LibraryMetadata(id = UUID.randomUUID().toString(), sourceRefs = listOf(ref))
        val section = TaskTemplate(name = "Source", examples = listOf(TemplateExample("Condition", solution = "Old", library = metadata)))
        File(root, "templates.json").writeText(Json.encodeToString(listOf(section)))
        val store = TemplateStore(root, TemplateKind.LIBRARY)
        store.save(section.id, section.name, "", listOf(ExampleDraft("Condition", emptyList(), "New", emptyList(),
            libraryId = metadata.id, expectedVersion = 1)), expectedSection = store.items.value.single())
        val current = TemplateStore(root, TemplateKind.LIBRARY).items.value.single().examples.single().library!!
        assertEquals(listOf(ref), current.sourceRefs)
        assertEquals(listOf(ref), current.previousVersions.single().sourceRefs)
        assertEquals(LibraryVerification.UNVERIFIED, current.verification)
    }
}
