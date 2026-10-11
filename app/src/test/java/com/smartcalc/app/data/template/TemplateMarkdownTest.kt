package com.smartcalc.app.data.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateMarkdownTest {

    @Test
    fun handWrittenFileIsUnderstood() {
        // Как пишут руками: без «Шаблон:», требования сразу под заголовком, условие без «### Условие».
        val md = """
            # Устойчивость по Гурвицу
            Решать определителями Гурвица.
            В конце — вывод.

            ## Пример 1
            ${'$'}D(s) = s^3 + s^2 + 2s + 1${'$'}
            ### Решение
            ${'$'}\Delta_2 = 1 > 0${'$'} — устойчива.

            # Второй шаблон
            ## Требования
            Кратко.
        """.trimIndent()
        val templates = TemplateMarkdown.parse(md)
        assertEquals(listOf("Устойчивость по Гурвицу", "Второй шаблон"), templates.map { it.name })
        val first = templates[0]
        assertEquals("Решать определителями Гурвица.\nВ конце — вывод.", first.requirements)
        assertEquals("${'$'}D(s) = s^3 + s^2 + 2s + 1${'$'}", first.examples.single().condition)
        assertEquals("${'$'}\\Delta_2 = 1 > 0${'$'} — устойчива.", first.examples.single().solution)
        assertEquals("Кратко.", templates[1].requirements)
        assertTrue(templates[1].examples.isEmpty())
    }

    @Test
    fun roundTrip() {
        val original = TaskTemplate(
            id = "abc-123",
            name = "Гурвиц",
            requirements = "Вывод в конце",
            examples = listOf(TemplateExample("Условие", listOf("/x.jpg"), "Решение\n\n#### подзаголовок", emptyList())),
        )
        val text = TemplateMarkdown.write(listOf(original))
        assertTrue(text.contains("<!-- фото: 1, в Markdown не сохраняются -->"))
        val back = TemplateMarkdown.parse(text).single()
        assertEquals("abc-123", back.id)
        assertEquals(original.requirements, back.requirements)
        assertEquals("Условие", back.examples.single().condition)
        assertEquals("Решение\n\n#### подзаголовок", back.examples.single().solution)
    }

    @Test
    fun textWithoutHeadingIsNotATemplate() {
        assertTrue(TemplateMarkdown.parse("Просто задача: решите 2x = 4").isEmpty())
    }
}
