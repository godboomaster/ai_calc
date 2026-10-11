package com.smartcalc.app.data.api

import com.smartcalc.app.data.history.FollowUp
import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.history.SolutionStep
import com.smartcalc.app.data.settings.ApiSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun followUpSendsWholeThread() {
        val base = Solution(
            problem = "Решите ${'$'}x^2 = 4${'$'}",
            answer = "${'$'}x = \\pm 2${'$'}",
            steps = listOf(SolutionStep("Корни", "Извлекаем корень", svg = "<svg>большой код</svg>")),
            followUps = listOf(FollowUp("А если ${'$'}x^2 = 9${'$'}?", "${'$'}x = \\pm 3${'$'}")),
        )
        val messages = client.followUpMessages(ApiSettings(), base, "Почему два корня?")
        val roles = messages.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("system", "user", "assistant", "user", "assistant", "user"), roles)
        fun content(i: Int) = messages[i].jsonObject["content"]!!.jsonPrimitive.content
        assertEquals("Задача: Решите ${'$'}x^2 = 4${'$'}", content(1))
        // Прежний ответ — тем же JSON, что прислала модель, но без кода схемы.
        val first = Json.parseToJsonElement(content(2)).jsonObject
        assertEquals("${'$'}x = \\pm 2${'$'}", first["answer"]!!.jsonPrimitive.content)
        assertEquals("Извлекаем корень\n[схема]", first["steps"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("Дополнение: А если ${'$'}x^2 = 9${'$'}?", content(3))
        assertTrue(content(5).startsWith("Дополнение: Почему два корня?"))
    }

    @Test
    fun followUpCarriesTemplateBeforeTheQuestion() {
        val base = Solution(problem = "p", answer = "a")
        val template = TemplatePrompt("Гурвиц", "", listOf(TemplatePrompt.Example("", listOf("EX_IMG"), "решение", emptyList())))
        val messages = client.followUpMessages(ApiSettings(), base, "исправь шаг 2", template)
        // Шаблон — в последнем сообщении, прямо перед вопросом; картинки примера тоже там.
        val last = messages.last().jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertTrue(last.first()["text"]!!.jsonPrimitive.content.startsWith("Ответ на дополнение дай по шаблону «Гурвиц»"))
        assertTrue(last.any { it["type"]!!.jsonPrimitive.content == "image_url" })
        assertTrue(last.last()["text"]!!.jsonPrimitive.content.startsWith("Дополнение: исправь шаг 2"))
        // Без шаблона дополнение — обычный текст, как раньше.
        val plain = client.followUpMessages(ApiSettings(), base, "исправь шаг 2").last().jsonObject["content"]!!
        assertTrue(plain.jsonPrimitive.content.startsWith("Дополнение: исправь шаг 2"))
    }

    @Test
    fun templateGoesBeforeTheNewTask() {
        val template = TemplatePrompt(
            name = "Гурвиц",
            requirements = "Вывод об устойчивости в конце",
            examples = listOf(
                TemplatePrompt.Example("Условие примера", emptyList(), "", listOf("SOLUTION_IMG")),
            ),
        )
        val parts = client.taskContent(listOf("TASK_IMG"), null, null, null, template)
        fun describe(p: kotlinx.serialization.json.JsonElement): String {
            val o = p.jsonObject
            return if (o["type"]!!.jsonPrimitive.content == "text") o["text"]!!.jsonPrimitive.content
            else "IMG:" + o["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.substringAfter("base64,")
        }
        val described = parts.map(::describe)
        assertTrue(described[0].startsWith("Это задача по шаблону «Гурвиц»"))
        assertTrue(described[0].endsWith("Вывод об устойчивости в конце"))
        assertEquals("Пример 1. Условие:\nУсловие примера", described[1])
        assertEquals("Пример 1. Решение: на фото ниже.", described[2])
        assertEquals("IMG:SOLUTION_IMG", described[3])
        // Новая задача — последней и с явной пометкой, чтобы модель не перепутала её с примером.
        assertEquals("Новая задача, которую нужно решить, — на следующем фото.", described[4])
        assertEquals("IMG:TASK_IMG", described[5])
        assertEquals(6, described.size)
    }

    @Test(expected = ApiException::class)
    fun brokenJsonStillFails() {
        client.parseSolution("""{"problem": "обрыв""")
    }
}
