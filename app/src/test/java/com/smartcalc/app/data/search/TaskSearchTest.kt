package com.smartcalc.app.data.search

import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.template.TaskTemplate
import com.smartcalc.app.data.template.TemplateExample
import com.smartcalc.app.data.template.TemplateMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TaskSearchTest {

    private fun section(name: String, vararg tasks: Pair<String, String>) = TaskTemplate(
        id = name,
        name = name,
        examples = tasks.map { (condition, solution) -> TemplateExample(condition = condition, solution = solution) },
    )

    private val base = listOf(
        section(
            "Кинематика",
            "Автомобиль проехал 120 км за 1,5 ч. Найдите среднюю скорость." to "120 / 1.5 = 80\nОтвет: 80 км/ч",
            "Тело падает с высоты 45 м. Найдите время падения." to "t = sqrt(2h/g) = 3\nОтвет: 3 с",
            "Поезд шёл 2 ч со скоростью 60 км/ч. Найдите путь." to "60 * 2 = 120\nОтвет: 120 км",
        ),
        section(
            "Динамика",
            "На тело массой 2 кг действует сила 10 Н. Найдите ускорение." to "a = F/m = 5\nОтвет: 5 м/с²",
        ),
    )

    private fun search(query: String, type: String? = null) =
        TaskSearch.search(TaskSearch.entries(base, emptyList(), emptyList()), query, type)

    @Test
    fun numbersAreNormalized() {
        assertEquals("3.5", TaskSearch.normalizeNumber("3,50"))
        assertEquals("7", TaskSearch.normalizeNumber("007"))
        assertEquals("0.25", TaskSearch.normalizeNumber("0,25"))
        assertEquals("10", TaskSearch.normalizeNumber("10"))
        assertEquals("3", TaskSearch.normalizeNumber("3.0"))
    }

    @Test
    fun wordFormsShareStem() {
        val forms = listOf("скорость", "скорости", "скоростью").map(TaskSearch::stem).toSet()
        assertEquals(1, forms.size)
        assertEquals(TaskSearch.stem("массой"), TaskSearch.stem("масса"))
        assertEquals(TaskSearch.stem("ускорения"), TaskSearch.stem("ускорение"))
    }

    @Test
    fun findsTaskByNumbersFromCondition() {
        val hits = search("120 1,5")
        assertTrue(hits.first().entry.condition.startsWith("Автомобиль"))
        assertEquals(listOf("120", "1,5"), hits.first().matched)
    }

    @Test
    fun conditionNumbersBeatNumbersInsideSolution() {
        // 120 есть в условии первой задачи и только в решении третьей.
        val hits = search("120")
        assertTrue(hits.first().entry.condition.startsWith("Автомобиль"))
        assertTrue(hits.any { it.entry.condition.startsWith("Поезд") })
    }

    @Test
    fun findsByWhatIsAsked() {
        val hits = search("время падения")
        assertTrue(hits.first().entry.condition.startsWith("Тело падает"))
        // Другая форма слова — та же задача.
        assertTrue(search("ускорением").first().entry.condition.contains("ускорение"))
    }

    @Test
    fun typeLimitsResultsAndMatchesByName() {
        val dynamics = search("тело", type = "Динамика")
        assertEquals(listOf("Динамика"), dynamics.map { it.entry.type }.distinct())
        assertEquals(4, search("").size)
        assertEquals(3, search("", type = "Кинематика").size)
        assertTrue(search("динамика").first().entry.type == "Динамика")
    }

    @Test
    fun nothingMatchesNothingFound() {
        assertTrue(search("интеграл 999").isEmpty())
    }

    @Test
    fun taskAttachedToTemplateIsListedOnce() {
        val template = section("Шаблон скорости", base[0].examples[0].condition to base[0].examples[0].solution)
        val entries = TaskSearch.entries(base, listOf(template), emptyList())
        assertEquals(4, entries.size)
        assertEquals(FoundSource.BASE, entries.first().source)
    }

    @Test
    fun repositoryBaseExampleIsSearchable() {
        val examples = File("../examples").takeIf { it.exists() } ?: File("examples")
        val sections = TemplateMarkdown.parse(File(examples, "example-base.md").readText())
        assertEquals(listOf("Кинематика", "Динамика"), sections.map { it.name })
        val entries = TaskSearch.entries(sections, emptyList(), emptyList())

        val fall = FoundSolution.of(TaskSearch.search(entries, "45").first().entry)
        assertEquals("3 с", fall.answer)
        assertEquals(2, fall.steps.size)
        assertEquals("База · Кинематика", fall.source)

        val block = TaskSearch.search(entries, "ускорение бруска").first().entry
        assertTrue(block.condition.startsWith("Брусок"))
        assertEquals("2 м/с²", FoundSolution.of(block).answer)
    }

    @Test
    fun openedBaseTaskIsNotRepeatedFromHistory() {
        val entry = TaskSearch.entries(base, emptyList(), emptyList()).first()
        val opened = FoundSolution.of(entry)
        val other = Solution(problem = "Своя задача 77", answer = "1")
        val entries = TaskSearch.entries(base, emptyList(), listOf(opened, other))
        assertEquals(5, entries.size)
        val history = entries.single { it.source == FoundSource.HISTORY }
        assertEquals(other.id, history.history?.id)
        assertEquals("Своя задача 77", TaskSearch.search(entries, "77").single().entry.condition)
    }
}
