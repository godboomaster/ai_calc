package com.smartcalc.app.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoundSolutionTest {

    @Test
    fun answerLineGoesToDisplay() {
        assertEquals("80 км/ч", FoundSolution.answer("v = s/t\nОтвет: 80 км/ч"))
        assertEquals("${'$'}x = 2${'$'}", FoundSolution.answer("**Ответ:** ${'$'}x = 2${'$'}"))
        // «Ответ» отдельной строкой или заголовком — ответ на следующей.
        assertEquals("5 м/с", FoundSolution.answer("Решаем.\n### Ответ\n\n5 м/с"))
        // Несколько строк «Ответ» — последняя.
        assertEquals("б) 4", FoundSolution.answer("Ответ: а) 3\nДалее.\nОтвет: б) 4"))
    }

    @Test
    fun withoutAnswerLineLastLineIsUsed() {
        assertEquals("Итого 12 деталей", FoundSolution.answer("Считаем.\nИтого 12 деталей\n"))
        assertNull(FoundSolution.answer(""))
        // «Ответы» — не строка ответа.
        assertEquals("конец", FoundSolution.answer("Ответы на вопросы ниже\nконец"))
    }

    @Test
    fun numberedPointsBecomeSteps() {
        val steps = FoundSolution.steps(
            """
            Дано: s = 120 км, t = 1,5 ч
            1) Скорость:
            v = s/t
            2) Подставим числа
            v = 120/1,5 = 80
            Ответ: 80 км/ч
            """.trimIndent(),
        )
        assertEquals(listOf("Скорость", "Подставим числа"), steps.map { it.title })
        // Текст до первого пункта — начало первого шага.
        assertEquals("Дано: s = 120 км, t = 1,5 ч\nv = s/t", steps[0].content)
        assertEquals("v = 120/1,5 = 80\nОтвет: 80 км/ч", steps[1].content)
    }

    @Test
    fun stepWordMarksAndParagraphs() {
        val marked = FoundSolution.steps("Шаг 1. Найдём a\na = 5\n**Шаг 2:** Найдём v\nv = 10")
        assertEquals(listOf("Найдём a", "Найдём v"), marked.map { it.title })

        val paragraphs = FoundSolution.steps("Сначала найдём площадь основания.\n\nПотом умножим на высоту.")
        assertEquals(2, paragraphs.size)
        assertEquals("Сначала найдём площадь основания.", paragraphs[0].title)
        assertEquals("Сначала найдём площадь основания.", paragraphs[0].content)
    }

    @Test
    fun decimalAtLineStartIsNotStep() {
        val steps = FoundSolution.steps("3.5 м — длина\n2.5 м — ширина")
        assertEquals(1, steps.size)
        assertEquals("Решение", steps.single().title)
    }

    @Test
    fun longFirstLineIsShortenedWithoutBreakingFormula() {
        val line = "Подставим в формулу ${'$'}v = \\frac{s}{t}${'$'} известные значения пути и времени и получим скорость"
        val step = FoundSolution.steps("1) $line\n2) Готово").first()
        assertEquals(line, step.content)
        assertEquals(0, step.title.count { it == '$' } % 2)
        assertEquals('…', step.title.last())
    }

    @Test
    fun photosBecomeSteps() {
        val entry = SearchEntry(
            key = "k",
            source = FoundSource.BASE,
            type = "Геометрия",
            condition = "",
            solution = "",
            conditionImages = listOf("/c1.jpg", "/c2.jpg"),
            solutionImages = listOf("/s.jpg"),
        )
        val s = FoundSolution.of(entry)
        assertEquals("Задача с фото", s.problem)
        assertEquals("Решение на фото", s.answer)
        assertEquals("/c1.jpg", s.imagePath)
        assertEquals(listOf("Условие на фото", "Решение"), s.steps.map { it.title })
        assertEquals(listOf("/s.jpg"), s.steps[1].images)
        assertEquals("База · Геометрия", s.source)
        // Та же задача — тот же id: повторное открытие не дублирует историю.
        assertEquals(s.id, FoundSolution.of(entry).id)
    }
}
