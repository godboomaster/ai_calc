package com.smartcalc.app.data.request

import com.smartcalc.app.data.history.*
import com.smartcalc.app.data.library.LibraryVerification
import com.smartcalc.app.data.library.agent.*
import com.smartcalc.app.data.library.source.SegmentKind
import com.smartcalc.app.data.settings.ApiSettings
import com.smartcalc.app.data.template.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryAgentCheckpointTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun report(version: Int = 1, token: String = "") = LibraryAgentReport(posts = 3,
        events = listOf(LibraryToolEvent("search_examples", "matches"), LibraryToolEvent("read_example", "ok")),
        examples = listOf(LibraryExampleSnapshot("11111111-1111-1111-1111-111111111111", version, "Раздел $token",
            "Условие $token", "Решение $token", "42 $token", LibraryVerification.UNVERIFIED, "FILE_IMPORT",
            listOf(LibraryExampleSource("22222222-2222-2222-2222-222222222222", "book $token.pdf", SegmentKind.PDF_PAGE, 2)),
            originName = "Imported $token.json")))

    @Test fun agentCheckpointSurvivesReloadIsIdBoundAndClearLeavesOtherFiles() = runBlocking {
        val directory = temporary.newFolder()
        val keep = File(directory, "keep.txt").apply { writeText("user content") }
        val files = RequestFiles(directory)
        files.saveAgentReport("request", report())
        val reopened = RequestFiles(directory)
        assertEquals(report(), reopened.readAgentReport("request"))
        assertNull(reopened.readAgentReport("other"))
        reopened.clear()
        assertNull(reopened.readAgentReport("request"))
        assertEquals("user content", keep.readText())
    }

    @Test fun historyKeepsIndependentReportsAndImmutableSourceVersions() = runBlocking {
        val directory = temporary.newFolder()
        val original = Solution(id = "solution", problem = "Задача", answer = "42", timestamp = 10, agentReport = report(),
            followUps = listOf(FollowUp("А при другом K?", "43", timestamp = 20, agentReport = report(version = 2))))
        HistoryStore(directory).add(original)
        val restored = HistoryStore(directory).items.value.single()
        assertEquals(original, restored)
        assertEquals(1, restored.agentReport!!.examples.single().version)
        assertEquals(2, restored.followUps.single().agentReport!!.examples.single().version)
        assertEquals(2, restored.agentReport!!.examples.single().sources.single().ordinal)
        assertEquals("Imported .json", restored.agentReport!!.examples.single().originName)
    }

    @Test fun oldHistoryAndDisabledSettingUseLegacyRequestPathWithoutLoadingLibrary() = runBlocking {
        val old = Json.decodeFromString(Solution.serializer(), """{"problem":"old","answer":"1","followUps":[{"question":"why","answer":"because"}]}""")
        assertNull(old.agentReport)
        assertNull(old.followUps.single().agentReport)
        var reads = 0
        val tools = libraryToolsForRequest(ApiSettings().libraryToolsEnabled, "Найди похожую") { reads++; LibraryCatalog(emptyList()) }
        assertNull(tools)
        assertEquals(0, reads)
        assertNull(libraryToolsForRequest(true, "Реши задачу") { LibraryCatalog(emptyList()) })
        assertNotNull(libraryToolsForRequest(true, "Найди похожую") { LibraryCatalog(emptyList()) })
        val ex = TemplateExample("Резистор", solution = "3", library = com.smartcalc.app.data.library.LibraryMetadata(id = "11111111-1111-1111-1111-111111111111"))
        assertNotNull(libraryToolsForRequest(true, "Реши задачу") { LibraryCatalog(listOf(TaskTemplate(name = "Раздел", examples = listOf(ex)))) })
    }

    @Test fun protectedReportPersistsWithoutCredentialOrHeaders() = runBlocking {
        val directory = temporary.newFolder()
        val files = RequestFiles(directory)
        val protected = report(token = "sensitive-fixture-token").redact("sensitive-fixture-token")
        files.saveAgentReport("request", protected)
        val text = directory.listFiles()!!.joinToString { it.readText() }
        assertFalse(text.contains("sensitive-fixture-token"))
        assertFalse(text.contains("Authorization"))
        assertTrue(text.contains("[скрыто]"))
        assertEquals(protected, RequestFiles(directory).readAgentReport("request"))
    }
}
