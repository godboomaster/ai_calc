package com.smartcalc.app.data.library.agent

import com.smartcalc.app.data.library.*
import com.smartcalc.app.data.library.source.*
import com.smartcalc.app.data.template.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LibraryToolsTest {
    private val id = UUID.randomUUID().toString()
    private fun example(id: String = this.id, condition: String = "На резисторе 2 Ом напряжение 6 В. Найти ток.",
        enabled: Boolean = true, verification: LibraryVerification = LibraryVerification.UNVERIFIED) =
        TemplateExample(condition, solution = "I=U/R", answer = "3 A",
            library = LibraryMetadata(id = id, enabled = enabled, verification = verification))
    private fun catalog(vararg examples: TemplateExample) = LibraryCatalog(listOf(TaskTemplate(name = "Электричество", examples = examples.toList())))
    private fun JsonObject.status() = getValue("status").jsonPrimitive.content
    private fun readArgs(id: String = this.id, version: Int = 1) = """{"id":"$id","version":$version}"""

    @Test fun searchThenReadReturnsFullTextVersionAndNoPaths() = runBlocking {
        val tools = LibraryTools { catalog(example()) }
        assertEquals("not_allowed", tools.execute("read_example", readArgs()).status())
        val hits = tools.execute("search_examples", """{"query":"резистор"}""")
        assertEquals("matches", hits.status())
        assertEquals(id, hits["matches"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        val read = tools.execute("read_example", readArgs())
        assertEquals("ok", read.status())
        assertEquals("3 A", read["example"]!!.jsonObject["answer"]!!.jsonPrimitive.content)
        assertEquals(id, tools.examples.single().id)
        assertFalse(read.toString().contains("relativePath"))
    }

    @Test fun disabledRejectedAndInactiveSourcesNeverEnterSearchOrRead() = runBlocking {
        val sourceId = UUID.randomUUID().toString()
        val ref = SourceSegmentRef(sourceId, "seg", SegmentKind.PDF_PAGE, 2)
        val fromFile = example().copy(library = LibraryMetadata(id = id, sourceRefs = listOf(ref)))
        var current = catalog(fromFile).copy(sources = SourceCatalog(listOf(LibrarySource(sourceId,
            "book.pdf", SourceFormat.PDF, 10, "a".repeat(64), "secret/path", 2))))
        val tools = LibraryTools { current }
        assertEquals("matches", tools.execute("search_examples", """{"query":"резистор"}""").status())
        assertEquals("ok", tools.execute("read_example", readArgs()).status())
        assertTrue(tools.examples.single().sources.single().name == "book.pdf")
        assertFalse(Json.encodeToString(LibraryExampleSnapshot.serializer(), tools.examples.single()).contains("secret/path"))
        current = current.copy(sources = current.sources.copy(sources = current.sources.sources.map { it.copy(status = SourceStatus.DISABLED) }))
        assertEquals("stale", tools.execute("read_example", readArgs()).status())
        assertEquals("no_match", tools.execute("search_examples", """{"query":"резистор"}""").status())
        val excluded = LibraryTools { catalog(example(enabled = false), example(UUID.randomUUID().toString(), verification = LibraryVerification.REJECTED)) }
        assertEquals("no_match", excluded.execute("search_examples", """{"query":"резистор"}""").status())
    }

    @Test fun changedVersionCannotBeReadWithoutNewSearch() = runBlocking {
        var ex = example()
        val tools = LibraryTools { catalog(ex) }
        tools.execute("search_examples", """{"query":"резистор"}""")
        ex = ex.copy(library = ex.library!!.copy(version = 2), answer = "правка")
        assertEquals("stale", tools.execute("read_example", readArgs()).status())
        assertEquals("not_allowed", tools.execute("read_example", readArgs(version = 2)).status())
        tools.execute("search_examples", """{"query":"резистор"}""")
        assertEquals("ok", tools.execute("read_example", readArgs(version = 2)).status())
    }

    @Test fun strictArgumentsAndUnavailableAreDifferentFromNoMatch() = runBlocking {
        val tools = LibraryTools { catalog(example()) }
        listOf("{}", "[]", "oops", """{"query":""}""", """{"query":"x","limit":6}""",
            """{"query":"x","limit":"1"}""", """{"query":"x","path":"/secret"}""").forEach {
            assertEquals(it, "invalid_arguments", tools.execute("search_examples", it).status())
        }
        assertEquals("invalid_tool", tools.execute("delete_file", "{}").status())
        assertEquals("invalid_arguments", tools.execute("read_example", """{"id":"../secret","version":1}""").status())
        assertEquals("no_match", tools.execute("search_examples", """{"query":"термодинамика"}""").status())
        val broken = LibraryTools { catalog(example()).copy(error = "/secret/credential") }
        val result = broken.execute("search_examples", """{"query":"резистор"}""")
        assertEquals("unavailable", result.status())
        assertFalse(result.toString().contains("credential"))
    }

    @Test fun imagesOnlyAreExplicitAndLargeExamplesAreNotSilentlyTruncated() = runBlocking {
        val photo = example().copy(condition = "", solution = "", answer = "", conditionImages = listOf("/secret/image"))
        val tools = LibraryTools { catalog(photo) }
        tools.execute("search_examples", """{"query":"электричество"}""")
        assertEquals("needs_image", tools.execute("read_example", readArgs()).status())
        val long = LibraryTools { catalog(example(condition = "резистор " + "😀".repeat(4000))) }
        val search = long.execute("search_examples", """{"query":"резистор"}""")
        val hit = search["matches"]!!.jsonArray.single().jsonObject
        assertTrue(hit["preview_truncated"]!!.jsonPrimitive.boolean)
        assertEquals("too_large", long.execute("read_example", readArgs()).status())
        assertTrue(long.examples.isEmpty())
    }

    @Test fun readLimitAndRepeatedReadsDoNotDuplicateSnapshots() = runBlocking {
        val examples = (1..6).map { example(UUID.randomUUID().toString(), condition = "Резистор номер $it") }
        val tools = LibraryTools { catalog(*examples.toTypedArray()) }
        for (ex in examples) {
            tools.execute("search_examples", """{"query":"${ex.condition}","limit":5}""")
            val result = tools.execute("read_example", readArgs(ex.library!!.id))
            assertEquals(if (tools.examples.size <= 5 && ex != examples.last()) "ok" else "read_limit", result.status())
        }
        assertEquals(5, tools.examples.size)
        assertEquals("already_read", tools.execute("read_example", readArgs(examples.first().library!!.id)).status())
        assertEquals(5, tools.examples.size)
    }
}
