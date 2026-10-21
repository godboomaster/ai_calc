package com.smartcalc.app.data.search

import com.smartcalc.app.data.library.LibraryMetadata
import com.smartcalc.app.data.library.LibraryVerification
import com.smartcalc.app.data.template.TaskTemplate
import com.smartcalc.app.data.template.TemplateExample
import org.junit.Assert.*
import org.junit.Test

class SimilarTasksTest {
    private fun task(id: String, condition: String, solution: String = "Решение", enabled: Boolean = true,
        verification: LibraryVerification = LibraryVerification.UNVERIFIED) =
        TemplateExample(condition = condition, solution = solution,
            library = LibraryMetadata(id = id, enabled = enabled, verification = verification))

    private val laplace = TaskTemplate(id = "laplace", name = "Практика. Преобразование Лапласа", updated = 0, examples = listOf(
        task("l1", "Найти изображение по Лапласу функции $ f(t) = e^{-2t} $", "Табличное изображение $\\frac{1}{s+2}$"),
        task("l2", "Найти изображение по Лапласу функции $ f(t) = \\sin 3t $", "По таблице: $\\frac{3}{s^2+9}$"),
    ))
    private val mikhailov = TaskTemplate(id = "mikhailov", name = "Лекции. Критерий Михайлова", updated = 0, examples = listOf(
        task("m1", "Исследовать устойчивость системы с характеристическим уравнением $ D(s) = s^3 + 2s^2 + 3s + 1 $ по годографу",
            "Строим годограф \$D(j\\omega)\$ и считаем квадранты"),
        task("m2", "Построить кривую и определить, устойчива ли САУ: $ D(s) = 2s^3 + s^2 + 4s + 5 $",
            "Кривая проходит три квадранта"),
    ))
    private val circuit = TaskTemplate(id = "circuit", name = "ДЗ 3. Математическая модель электрической цепи", updated = 0,
        examples = listOf(task("c1", "Составить уравнения RLC-цепи и найти передаточную функцию по напряжению")))

    private val library = SimilarTasks.candidates(listOf(laplace, mikhailov, circuit))

    @Test fun sameTaskComesFirstEvenWhenModelNamesAnotherSection() {
        val found = SimilarTasks.rank(library, TaskDescription(
            condition = "Найти изображение по Лапласу функции $ f(t) = \\sin 3t $",
            sections = listOf("Лекции. Критерий Михайлова"),
        ))
        assertEquals("l2", found.first().example.library?.id)
    }

    @Test fun taskOfTheNamedTypeIsFoundWithoutCommonWords() {
        // «Кривая» и «САУ» не встречаются в запросе, но раздел назван моделью: это ближайшая похожая задача.
        val found = SimilarTasks.rank(library, TaskDescription(
            condition = "Проверить устойчивость по Михайлову, если $ D(p) = p^4 + 3p^3 + 5p^2 + 4p + 2 $",
            topic = "Устойчивость линейной системы по годографу Михайлова",
            keywords = listOf("годограф", "устойчивость", "характеристический полином"),
            sections = listOf("критерий михайлова"),
        )).map { it.example.library?.id }
        assertEquals(listOf("m1", "m2"), found.take(2))
        assertFalse("Unrelated tasks must not be offered without any shared signal", "c1" in found)
    }

    @Test fun nothingSharedMeansNothingOffered() {
        val found = SimilarTasks.rank(library, TaskDescription(condition = "Сколько будет дважды два?", sections = listOf("История")))
        assertTrue(found.isEmpty())
    }

    @Test fun shortOrForeignSectionNamesDoNotMatchBySubstring() {
        val found = SimilarTasks.rank(library, TaskDescription(sections = listOf("ДЗ", "Лекции")))
        assertTrue(found.isEmpty())
        val quoted = SimilarTasks.rank(library, TaskDescription(sections = listOf("«Практика. Преобразование Лапласа.»")))
        assertEquals(listOf("l1", "l2"), quoted.map { it.example.library?.id })
    }

    @Test fun offersOnlyAvailableTasksOnceAndAtMostTheLimit() {
        val hidden = TaskTemplate(id = "hidden", name = "Скрытое", updated = 0, examples = listOf(
            task("d1", "Найти изображение по Лапласу", enabled = false),
            task("r1", "Найти изображение по Лапласу", verification = LibraryVerification.REJECTED),
        ))
        val copy = laplace.copy(id = "copy", name = "Лекции. Преобразование Лапласа")
        val candidates = SimilarTasks.candidates(listOf(laplace, hidden, copy))
        assertEquals(listOf("l1", "l2"), candidates.map { it.example.library?.id })
        assertEquals(listOf("laplace#0", "laplace#1"), candidates.map { it.id })
        val many = TaskTemplate(id = "many", name = "Много", updated = 0,
            examples = (1..20).map { task("x$it", "Найти изображение по Лапласу номер $it") })
        assertEquals(SimilarTasks.LIMIT, SimilarTasks.rank(SimilarTasks.candidates(listOf(many)),
            TaskDescription(condition = "изображение по Лапласу")).size)
    }

    @Test fun chosenTasksBecomeOneOffTemplateWithTheirOwnSolutions() {
        val template = SimilarTasks.template(library.filter { it.section == mikhailov.name })
        assertEquals(SimilarTasks.TEMPLATE_ID, template.id)
        assertEquals(mikhailov.examples, template.examples)
    }
}
