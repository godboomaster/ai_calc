package com.smartcalc.app.data.search

import com.smartcalc.app.data.template.TaskTemplate
import com.smartcalc.app.data.template.TemplateExample
import com.smartcalc.app.data.history.FollowUp
import com.smartcalc.app.data.history.Solution
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskSearchRegressionTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun entry(key: String, condition: String, solution: String = "") =
        SearchEntry(key, FoundSource.BASE, null, condition, solution)

    @Test fun distinguishesDenominatorSignEvenWithSameNumbers() {
        val entries = listOf(entry("plus", "4/(0.5s+1)"), entry("minus", "4/(0.5s-1)"))
        assertEquals("minus", TaskSearch.search(entries, "4/(0.5s-1)").first().entry.key)
        assertEquals("plus", TaskSearch.search(entries.reversed(), "4/(0.5s+1)").first().entry.key)
    }

    @Test fun integratorStructureOutweighsMatchingNumbers() {
        val entries = listOf(
            entry("lag", "АЧХ и ФЧХ W(s)=6/(3s+1)"),
            entry("integrator", "АЧХ и ФЧХ W(s)=10/[s*(2s+1)]"),
        )
        assertEquals("integrator", TaskSearch.search(entries, "АЧХ и ФЧХ W(s)=6/[s*(3s+1)]").first().entry.key)
    }

    @Test fun absoluteValueFindsSameStructureWithOtherNumbers() {
        val entries = listOf(entry("quadratic", "Решить x^2-6x+2=0"), entry("absolute", "Решить |x-4|=5"))
        assertEquals("absolute", TaskSearch.search(entries, "Найди похожий пример для |x-6|=2").first().entry.key)
    }

    @Test fun exponentIsNotTreatedAsInterchangeableCoefficient() {
        val entries = listOf(entry("cubic", "x^3-7x+12=0"), entry("quadratic", "x^2-4x+3=0"))
        assertEquals("quadratic", TaskSearch.search(entries, "x^2-7x+12=0").first().entry.key)
    }

    @Test fun unmatchedSubjectDoesNotReturnUnrelatedNumericCoincidences() {
        val entries = listOf(entry("lag", "Найти АЧХ W(s)=4/(0.5s+1)", "При omega=0 ответ 4"))
        assertTrue(TaskSearch.search(entries, "Вычислить определённый интеграл от sin(x) на [0, pi].").isEmpty())
        assertEquals(1, TaskSearch.search(entries, "0").size)
        assertEquals(1, TaskSearch.search(entries, "").size)
        assertTrue(TaskSearch.search(entries, "???").isEmpty())
    }

    private fun entries(vararg examples: TemplateExample) = TaskSearch.entries(
        listOf(TaskTemplate(id = "section", name = "Задачи", examples = examples.toList())), emptyList(), emptyList(),
    )

    @Test fun photoOnlyKeysFollowContentWhenReorderedAndReplaced() {
        val a = tmp.newFile().apply { writeBytes(byteArrayOf(1, 2)) }
        val b = tmp.newFile().apply { writeBytes(byteArrayOf(3, 4)) }
        val first = TemplateExample(conditionImages = listOf(a.path))
        val second = TemplateExample(conditionImages = listOf(b.path))
        val before = entries(first, second)
        val after = entries(second, first)
        assertEquals(before[0].key, after[1].key)
        assertEquals(before[1].key, after[0].key)
        assertNotEquals(entries(first).single().key, entries(second).single().key)
    }

    @Test fun equalTextWithDifferentPhotosIsNotDeduplicated() {
        val a = tmp.newFile().apply { writeBytes(byteArrayOf(1, 2)) }
        val b = tmp.newFile().apply { writeBytes(byteArrayOf(3, 4)) }
        val first = TemplateExample(condition = "Условие", solutionImages = listOf(a.path))
        assertEquals(2, entries(first, first.copy(solutionImages = listOf(b.path))).size)
    }

    @Test fun photoCopiesHaveSameKeyButSamePathWithNewBytesHasNewKey() {
        val a = tmp.newFile().apply { writeBytes(byteArrayOf(1, 2)) }
        val b = tmp.newFile().apply { writeBytes(a.readBytes()) }
        val ex = TemplateExample(solutionImages = listOf(a.path))
        val key = entries(ex).single().key
        assertEquals(key, entries(ex.copy(solutionImages = listOf(b.path))).single().key)
        a.writeBytes(byteArrayOf(3, 4))
        assertNotEquals(key, entries(ex).single().key)
    }

    @Test fun openedLibraryTaskStillSearchesSavedFollowUpsWithoutDuplicate() {
        val example = TemplateExample(condition = "Условие", solution = "Исходное решение")
        val section = TaskTemplate(id = "library", name = "Задачи", examples = listOf(example))
        val original = TaskSearch.entries(listOf(section), emptyList(), emptyList()).single()
        val saved = FoundSolution.of(original).copy(followUps = listOf(FollowUp("Проверить размерность", "Метры в секунду")))
        val indexed = TaskSearch.entries(listOf(section), emptyList(), listOf(saved))
        val hit = TaskSearch.search(indexed, "размерность").single()
        assertEquals(1, indexed.size)
        assertEquals(saved, FoundSolution.of(hit.entry))
    }

    @Test fun ownHistoryMatchesQuestionAnswerAndFollowUp() {
        val saved = Solution(problem = "Дальность полёта", answer = "Метры", followUps = listOf(FollowUp("Проверить размерность", "Согласовано")))
        val indexed = TaskSearch.entries(emptyList(), emptyList(), listOf(saved))
        listOf("дальность", "метры", "размерность", "согласовано").forEach { query ->
            assertEquals(saved, FoundSolution.of(TaskSearch.search(indexed, query).single().entry))
        }
    }

    @Test fun removedLibrarySnapshotRemainsInHistoryButNotInNewSearch() {
        val example = TemplateExample(condition = "Скорость 12 м/с", solution = "Ответ: 24 м")
        val saved = FoundSolution.of(entries(example).single())
        val own = Solution(problem = "Скорость 18 м/с", answer = "36 м")
        val indexed = TaskSearch.entries(emptyList(), emptyList(), listOf(saved, own))
        assertEquals(listOf(own), indexed.map { it.history })
        assertEquals("24 м", saved.answer)
    }
}
