package com.smartcalc.app.ui.settings

import androidx.test.platform.app.InstrumentationRegistry
import com.smartcalc.app.SmartCalcApplication
import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.history.SolutionStep
import com.smartcalc.app.data.library.extract.PageSelection
import com.smartcalc.app.data.library.importer.ImportDraft
import com.smartcalc.app.data.library.importer.ImportJob
import com.smartcalc.app.data.library.importer.ImportStatus
import com.smartcalc.app.data.library.source.SourceInput
import com.smartcalc.app.data.plot.Plot
import com.smartcalc.app.data.plot.PlotAxis
import com.smartcalc.app.data.plot.PlotSeries
import com.smartcalc.app.data.template.ExampleDraft
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Explicit opt-in visual fixtures. Never touches the production package or sends an API request. */
class LayoutFixturesDeviceTest {
    @Test fun prepareOfflineVisualFixtures() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.smartcalc.app.connectionscheck")
        val app = context.applicationContext as SmartCalcApplication
        val example = ExampleDraft("Найдите 12 + 8", emptyList(), "Складываем: 12 + 8 = 20.", emptyList(), answer = "20")
        app.templateStore.save(null, "Образец с длинным названием для проверки переноса текста", "Решать по действиям", listOf(example))
        app.libraryStore.save(null, "Проверка интерфейса — синтетические задачи", "", listOf(example))
        val source = app.librarySources.copy(SourceInput("Учебный пример с длинным названием.txt", "text/plain") {
            instrumentation.context.assets.open("library-import/i01-utf8.txt")
        }).source
        val count = app.libraryExtractor.inspect(source)
        val selection = PageSelection((1..count).toList())
        val segments = app.libraryExtractor.extract(source, selection) {}.segments
        app.libraryImports.save(ImportJob(
            id = UUID.randomUUID().toString(), sourceIds = listOf(source.id),
            selections = mapOf(source.id to selection), status = ImportStatus.REVIEW_REQUIRED,
            segments = segments, drafts = listOf(ImportDraft(UUID.randomUUID().toString(),
                condition = "Найдите 12 + 8", solution = "12 + 8 = 20", answer = "20", refs = listOf(segments.first().ref))),
        ))
        app.historyStore.add(Solution(id = "layout-fixture", problem = "Проверка компоновки — без API", answer = "20",
            steps = listOf(
                SolutionStep("Длинный заголовок шага: вычисление суммы и проверка результата несколькими способами",
                    "Складываем 12 + 8 = 20.\n\n| Метод | Значение |\n| --- | --- |\n| Сложение | 20 |\n| Проверка | 20 |",
                    plot = Plot(title = "Линейная функция", x = PlotAxis(from = "0", to = "10"), series = listOf(PlotSeries(y = "2*x")))),
                SolutionStep("Схема", "Связь исходных данных и ответа.",
                    svg = """<svg xmlns="http://www.w3.org/2000/svg" width="320" height="120"><rect x="10" y="10" width="300" height="100" fill="#dde4ff"/><text x="80" y="65" font-size="24">12 + 8 = 20</text></svg>"""),
            )))
        assertTrue(app.historyStore.items.value.any { it.id == "layout-fixture" })
    }
}
