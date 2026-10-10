package com.smartcalc.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ParseSolutionTest {

    private val client = OpenAiClient()

    @Test
    fun latexBackslashesAreRepaired() {
        val raw = """{"problem": "${'$'}\omega${'$'}", "answer": "A = k", "steps": [{"title": "АЧХ", "content": "A(\omega) = |W(j\omega)|"}]}"""
        val s = client.parseSolution(raw)
        assertEquals("${'$'}\\omega${'$'}", s.problem)
        assertEquals("A(\\omega) = |W(j\\omega)|", s.steps.single().content)
    }

    @Test
    fun validEscapesSurviveRepair() {
        val raw = """{"problem": "a\\b \mathcal{L}", "answer": "строка1\nстрока2", "steps": []}"""
        val s = client.parseSolution(raw)
        assertEquals("a\\b \\mathcal{L}", s.problem)
        assertEquals("строка1\nстрока2", s.answer)
    }

    @Test
    fun markdownFenceAndBackticksAreStripped() {
        val raw = "```json\n{\"problem\": \"`dy/dt = ku`\", \"answer\": \"`k/s`\", \"steps\": [\"шаг\"]}\n```"
        val s = client.parseSolution(raw)
        assertEquals("dy/dt = ku", s.problem)
        assertEquals("k/s", s.answer)
        assertEquals("шаг", s.steps.single().content)
    }

    @Test
    fun latexCommandsLookingLikeJsonEscapesAreRepaired() {
        // \f, \t и \n — допустимые escape JSON, поэтому такой ответ парсится, но портит формулы.
        val raw = """{"problem": "${'$'}\frac{1}{2} \times 3 \neq 1${'$'}", "answer": "${'$'}\text{да}${'$'}", "steps": [{"title": "Шаг", "content": "а\nб"}]}"""
        val s = client.parseSolution(raw)
        assertEquals("${'$'}\\frac{1}{2} \\times 3 \\neq 1${'$'}", s.problem)
        assertEquals("${'$'}\\text{да}${'$'}", s.answer)
        assertEquals("а\nб", s.steps.single().content)
    }

    @Test
    fun properlyEscapedLatexIsUntouched() {
        val raw = """{"problem": "${'$'}\\frac{a}{b}${'$'}", "answer": "1", "steps": []}"""
        assertEquals("${'$'}\\frac{a}{b}${'$'}", client.parseSolution(raw).problem)
    }

    @Test
    fun plotIsParsedLeniently() {
        // Числа строками и формулами, логическое значение строкой — всё это модели пишут.
        val raw = """{"problem": "p", "answer": "a", "steps": [
            {"title": "АЧХ", "content": "c", "plot": {"x": {"var": "w", "from": 0.01, "to": "2*pi", "log": "true"},
              "params": {"k": 2}, "series": [{"y": "k/w"}, {"points": [[1, 2], ["3", 4]]}]}},
            {"title": "Без графика", "content": "c", "plot": "сломано"}
        ], "plot": {"series": [{"y": "x^2"}]}}"""
        val s = client.parseSolution(raw)
        val plot = s.steps[0].plot!!
        assertEquals("2*pi", plot.x.to)
        assertEquals("0.01", plot.x.from)
        assertEquals(true, plot.x.log)
        assertEquals("2", plot.params["k"])
        assertEquals(listOf(listOf(1.0, 2.0), listOf(3.0, 4.0)), plot.series[1].points)
        assertEquals(null, s.steps[1].plot)
        // График вне шагов становится отдельным шагом.
        assertEquals(3, s.steps.size)
        assertEquals("График", s.steps[2].title)
    }

    @Test
    fun svgIsExtractedFromWrappers() {
        val raw = """{"problem": "p", "answer": "a", "steps": [
            {"title": "Схема", "content": "c", "svg": "Вот схема:\n```svg\n<svg viewBox='0 0 10 10'><line x1='0' y1='0' x2='10' y2='10' stroke='black'/></svg>\n```"},
            {"title": "Не SVG", "content": "c", "svg": "нарисовать не могу"}
        ], "scheme": "<svg viewBox='0 0 1 1'></svg>"}"""
        val s = client.parseSolution(raw)
        assertEquals("<svg viewBox='0 0 10 10'><line x1='0' y1='0' x2='10' y2='10' stroke='black'/></svg>", s.steps[0].svg)
        assertEquals(null, s.steps[1].svg)
        // Схема вне шагов становится отдельным шагом.
        assertEquals("Схема", s.steps[2].title)
        assertEquals("<svg viewBox='0 0 1 1'></svg>", s.steps[2].svg)
    }

    @Test(expected = ApiException::class)
    fun brokenJsonStillFails() {
        client.parseSolution("""{"problem": "обрыв""")
    }
}
