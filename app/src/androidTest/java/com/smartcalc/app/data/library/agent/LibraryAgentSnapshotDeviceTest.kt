package com.smartcalc.app.data.library.agent

import androidx.test.platform.app.InstrumentationRegistry
import com.smartcalc.app.SmartCalcApplication
import com.smartcalc.app.data.history.FollowUp
import com.smartcalc.app.data.history.HistoryStore
import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.history.SolutionStep
import com.smartcalc.app.data.library.*
import com.smartcalc.app.data.library.source.*
import com.smartcalc.app.data.template.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Seeds only an isolated app with synthetic snapshots for Computer Use acceptance; no API. */
class LibraryAgentSnapshotDeviceTest {
    @Test fun syntheticReadSnapshotsPersistAndRemainAvailableForUiAcceptance() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.smartcalc.app.importcheck") { "Fixture must never modify the user's main app" }
        val id = "11111111-1111-1111-1111-111111111111"
        val importedId = "22222222-2222-2222-2222-222222222222"
        val sourceId = "33333333-3333-3333-3333-333333333333"
        val ref = SourceSegmentRef(sourceId, "page-2", SegmentKind.PDF_PAGE, 2)
        val examples = listOf(
            TemplateExample("Резистор 2 Ом, напряжение 6 В. Найти ток.", solution = "По закону Ома I=U/R=3 А.\n" +
                "Синтетическое пояснение для проверки прокрутки. ".repeat(12), answer = "3 А", conditionImages = listOf("synthetic-image-not-uploaded"),
                library = LibraryMetadata(id = id, origin = LibraryOrigin(LibraryOriginKind.FILE_IMPORT, "synthetic-demo.pdf"), sourceRefs = listOf(ref))),
            TemplateExample("Резистор 4 Ом, напряжение 8 В. Найти ток.", solution = "I=8/4=2 А", answer = "2 А",
                library = LibraryMetadata(id = importedId, origin = LibraryOrigin(LibraryOriginKind.FILE_IMPORT, "synthetic-examples.json"),
                    verification = LibraryVerification.VERIFIED_BY_USER)),
        )
        val catalog = LibraryCatalog(listOf(TaskTemplate(name = "Синтетические примеры", examples = examples)),
            SourceCatalog(listOf(LibrarySource(sourceId, "synthetic-demo.pdf", SourceFormat.PDF, 10, "a".repeat(64), "synthetic/not-read.pdf", 2))))
        val tools = LibraryTools { catalog }
        assertEquals("matches", tools.execute("search_examples", """{"query":"резистор"}""")["status"]!!.jsonPrimitive.content)
        for (exampleId in listOf(id, importedId)) {
            assertEquals("ok", tools.execute("read_example", """{"id":"$exampleId","version":1}""")["status"]!!.jsonPrimitive.content)
        }
        val report = LibraryAgentReport(posts = 0, events = listOf(LibraryToolEvent("search_examples", "matches"), LibraryToolEvent("read_example", "ok")),
            examples = tools.examples)
        val fixture = Solution(id = "synthetic-agent-tools-gui-2026-10-13", problem = "Проверка интерфейса агента — синтетические данные, без API",
            answer = "3 А", timestamp = 1791878400000, source = "Синтетическая проверка без API",
            steps = listOf(SolutionStep("Синтетическое решение", "Этот ответ создан локальным тестом для проверки интерфейса.")), agentReport = report,
            followUps = listOf(FollowUp("Проверка отдельного источника дополнения", "2 А", timestamp = 1791878401000,
                agentReport = report.copy(examples = listOf(tools.examples.last().copy(version = 2))))))
        val app = context.applicationContext as SmartCalcApplication
        app.historyStore.add(fixture)
        val restored = HistoryStore(context).items.value.first { it.id == fixture.id }
        assertEquals(fixture, restored)
        assertEquals(2, restored.agentReport!!.examples.single { it.id == id }.sources.single().ordinal)
        assertEquals("synthetic-examples.json", restored.followUps.single().agentReport!!.examples.single().originName)
    }
}
