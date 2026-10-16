package com.smartcalc.app.data.library

import com.smartcalc.app.data.history.FollowUp
import com.smartcalc.app.data.history.HistoryStore
import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.library.source.LibrarySourceStore
import com.smartcalc.app.data.search.FoundSource
import com.smartcalc.app.data.search.SearchEntry
import com.smartcalc.app.data.template.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExampleWorkspaceTest {
    @get:Rule val temp = TemporaryFolder()

    private suspend fun fixture(block: suspend (ExampleWorkspace, HistoryStore, File) -> Unit) {
        val root = temp.newFolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val history = HistoryStore(root)
        val workspace = ExampleWorkspace(TemplateStore(File(root, "templates")),
            TemplateStore(File(root, "library"), TemplateKind.LIBRARY), history,
            LibrarySourceStore(File(root, "library/sources")), scope)
        try { withTimeout(5_000) { block(workspace, history, root) } }
        finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun completedRequestDoesNotDisarmNewlySelectedTemplate() = runBlocking { fixture { workspace, _, _ ->
        workspace.saveTemplate(null, "first", "", emptyList())
        workspace.saveTemplate(null, "second", "", emptyList())
        val first = workspace.templates.value.single { it.name == "first" }
        val second = workspace.templates.value.single { it.name == "second" }
        workspace.armTemplate(first)
        workspace.armedTemplate.first { it?.id == first.id }
        workspace.armTemplate(second)
        workspace.armedTemplate.first { it?.id == second.id }
        workspace.disarmIfSelected(first.id)
        assertEquals(second.id, workspace.armedTemplate.value?.id)
        workspace.disarmIfSelected(second.id)
        workspace.armedTemplate.first { it == null }
    } }

    @Test fun followUpUsesSelectionThenOriginalTemplateIfItStillExists() = runBlocking { fixture { workspace, _, _ ->
        workspace.saveTemplate(null, "original", "", emptyList())
        workspace.saveTemplate(null, "selected", "", emptyList())
        val original = workspace.templates.value.single { it.name == "original" }
        val selected = workspace.templates.value.single { it.name == "selected" }
        assertEquals(original.id, workspace.followUpTemplate(original.id)?.id)
        workspace.armTemplate(selected)
        workspace.armedTemplate.first { it?.id == selected.id }
        assertEquals(selected.id, workspace.followUpTemplate(original.id)?.id)
        workspace.disarmTemplate()
        workspace.armedTemplate.first { it == null }
        workspace.deleteTemplate(original.id)
        assertNull(workspace.followUpTemplate(original.id))
    } }

    @Test fun collectionOperationsKeepLibraryAndTemplatesSeparate() = runBlocking { fixture { workspace, _, _ ->
        workspace.saveTemplate(null, "template", "requirements", emptyList())
        workspace.saveTemplate(null, "library", "", listOf(
            ExampleDraft("12+8", emptyList(), "12+8=20", emptyList(), answer = "20")), TemplateKind.LIBRARY)
        val section = workspace.library.value.single()
        val output = ByteArrayOutputStream()
        assertEquals(1, workspace.exportTemplates(listOf(section.id), output, TemplateFormat.JSON, TemplateKind.LIBRARY))
        assertEquals(1, workspace.importTemplates(output.toByteArray().inputStream(), TemplateKind.TEMPLATES).added)
        assertEquals(setOf("template", "library"), workspace.templates.value.map { it.name }.toSet())
        assertEquals(listOf("library"), workspace.items(TemplateKind.LIBRARY).value.map { it.name })
    } }

    private fun entry() = SearchEntry(key = "stable-example", source = FoundSource.BASE, type = "sum",
        condition = "12+8", solution = "1) Add\n12+8=20", answer = "20")

    @Test fun reopeningFoundExampleKeepsExistingFollowUpsInsteadOfDuplicatingHistory() = runBlocking { fixture { workspace, history, _ ->
        val first = workspace.resolveFound(entry())
        val withFollowUp = history.add(first.copy(followUps = listOf(FollowUp("why", "because"))))
        assertEquals(withFollowUp, workspace.resolveFound(entry()))
        assertEquals(1, history.items.value.size)
    } }

    @Test fun historyEntryIsReturnedAsIsWithoutCreatingLibrarySnapshot() = runBlocking { fixture { workspace, history, _ ->
        val saved = Solution(id = "history", problem = "saved", answer = "42")
        val entry = SearchEntry("history-key", FoundSource.HISTORY, null, "different", "different", history = saved)
        assertEquals(saved, workspace.resolveFound(entry))
        assertTrue(history.items.value.isEmpty())
    } }

    @Test fun failedHistoryWriteDoesNotExposeUnpersistedLibrarySolution() = runBlocking { fixture { workspace, history, root ->
        File(root, "history.json.tmp").mkdir()
        assertTrue(runCatching { workspace.resolveFound(entry()) }.isFailure)
        assertTrue(history.items.value.isEmpty())
    } }

    @Test fun searchIncludesEachExistingCollection() = runBlocking { fixture { workspace, history, _ ->
        val examples = listOf(ExampleDraft("12+8", emptyList(), "20", emptyList()))
        workspace.saveTemplate(null, "template", "", examples)
        workspace.saveTemplate(null, "section", "", listOf(
            ExampleDraft("15+5", emptyList(), "20", emptyList())), TemplateKind.LIBRARY)
        history.add(Solution(problem = "history", answer = "42"))
        val entries = workspace.searchEntries.first { it?.size == 3 }!!
        assertEquals(setOf(FoundSource.BASE, FoundSource.TEMPLATE, FoundSource.HISTORY), entries.map { it.source }.toSet())
    } }
}
