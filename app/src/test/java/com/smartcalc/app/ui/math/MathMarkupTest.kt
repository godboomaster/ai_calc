package com.smartcalc.app.ui.math

import org.junit.Assert.assertEquals
import org.junit.Test

class MathMarkupTest {

    private fun parse(s: String) = MathMarkup.parse(s)

    @Test
    fun inlineAndBlockFormulas() {
        assertEquals(
            listOf(
                MathPart.Text("Корни: "),
                MathPart.Inline("x_1 = 2"),
                MathPart.Text("\n"),
                MathPart.Block("\\frac{a}{b}"),
            ),
            parse("Корни: \$x_1 = 2\$\n\$\$ \\frac{a}{b} \$\$"),
        )
        assertEquals(
            listOf(MathPart.Inline("a"), MathPart.Text(" и "), MathPart.Block("b")),
            parse("\\(a\\) и \\[b\\]"),
        )
    }

    @Test
    fun moneyAndUnclosedDollarsStayText() {
        assertEquals(listOf(MathPart.Text("от \$5 до \$10")), parse("от \$5 до \$10"))
        assertEquals(listOf(MathPart.Text("цена \$5")), parse("цена \$5"))
        assertEquals(listOf(MathPart.Text("\$x\$")), parse("\\\$x\\\$"))
    }

    @Test
    fun bareLatexIsDetected() {
        assertEquals(
            listOf(MathPart.Text("Подставим: "), MathPart.Inline("x = \\frac{1}{2}"), MathPart.Text(".")),
            parse("Подставим: x = \\frac{1}{2}."),
        )
        // Без команд LaTeX текст остаётся текстом.
        assertEquals(listOf(MathPart.Text("x = 1/2")), parse("x = 1/2"))
    }

    @Test
    fun plainText() {
        assertEquals("x = (−b ± √D)/2a", MathMarkup.plain("\$x = \\frac{−b \\pm \\sqrt{D}}{2a}\$"))
        assertEquals("x² + y₁ = 5 м", MathMarkup.plain("\$x^2 + y_1 = 5\\,\\text{м}\$"))
        assertEquals("sin x · 2", MathMarkup.plain("\$\\sin x \\cdot 2\$"))
        assertEquals("42", MathMarkup.plain("\$42\$"))
    }

    @Test
    fun markdownTable() {
        // Как на скриншоте: в заголовке три ячейки, в строках — четыре, в ячейках формулы.
        val text = """Строим таблицу:

| Степень | Колонка 1 | Колонка 2 |
|---|:---:|---:|
| ${'$'}s^6${'$'} | 1 | 15 | 6 |
| ${'$'}s^4${'$'} | ${'$'}\frac{6 \cdot 15 - 20}{6} = 10${'$'} | ${'$'}|x|${'$'} |

Первый столбец положительный."""
        val blocks = MathMarkup.blocks(text)
        assertEquals(3, blocks.size)
        assertEquals(TextBlock.Prose("Строим таблицу:\n"), blocks[0])
        val table = (blocks[1] as TextBlock.Table).table
        assertEquals(listOf("Степень", "Колонка 1", "Колонка 2"), table.header)
        assertEquals(listOf(CellAlign.Start, CellAlign.Center, CellAlign.End), table.align)
        assertEquals(4, table.columns)
        assertEquals(listOf("${'$'}s^6${'$'}", "1", "15", "6"), table.rows[0])
        // «|» внутри формулы ячейку не делит.
        assertEquals("${'$'}|x|${'$'}", table.rows[1][2])
        assertEquals(TextBlock.Prose("\nПервый столбец положительный."), blocks[2])
    }

    @Test
    fun notATable() {
        // Модуль в начале строки — не таблица.
        assertEquals(listOf(TextBlock.Prose("|x| = 5\nзначит x = ±5")), MathMarkup.blocks("|x| = 5\nзначит x = ±5"))
        assertEquals(listOf("a", "b|c"), MathMarkup.splitRow("| a | b\\|c |"))
    }
}
