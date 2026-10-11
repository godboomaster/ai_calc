package com.smartcalc.app.ui.math

/** Кусок ответа модели: обычный текст или формула LaTeX (в строке или отдельной строкой). */
sealed interface MathPart {
    data class Text(val text: String) : MathPart
    data class Inline(val latex: String) : MathPart
    data class Block(val latex: String) : MathPart
}

enum class CellAlign { Start, Center, End }

/** Таблица markdown: заголовок (если есть строка-разделитель) и строки ячеек с текстом и формулами. */
data class MathTable(val header: List<String>?, val rows: List<List<String>>, val align: List<CellAlign>) {
    /** Модели нередко пишут строки разной длины — столбцов столько, сколько в самой длинной. */
    val columns: Int get() = maxOf(header?.size ?: 0, rows.maxOfOrNull { it.size } ?: 0)
}

/** Крупные части ответа: обычный текст с формулами или таблица. */
sealed interface TextBlock {
    data class Prose(val text: String) : TextBlock
    data class Table(val table: MathTable) : TextBlock
}

/**
 * Разбирает текст с формулами: `$…$` и `\(…\)` — в строке, `$$…$$` и `\[…\]` — отдельной строкой.
 * Незакрытый разделитель остаётся текстом; LaTeX без разделителей, который модели всё же иногда пишут,
 * распознаётся эвристикой [bareLatex].
 */
object MathMarkup {

    private val delimiters = listOf(
        Triple("$$", "$$", true),
        Triple("\\[", "\\]", true),
        Triple("\\(", "\\)", false),
        Triple("$", "$", false),
    )

    fun parse(source: String): List<MathPart> {
        val parts = mutableListOf<MathPart>()
        val text = StringBuilder()
        var i = 0

        fun flushText() {
            if (text.isNotEmpty()) {
                parts += bareLatex(text.toString())
                text.clear()
            }
        }

        while (i < source.length) {
            if (source.startsWith("\\$", i)) {
                text.append('$')
                i += 2
                continue
            }
            val match = delimiters.firstNotNullOfOrNull { (open, close, block) ->
                if (!source.startsWith(open, i)) return@firstNotNullOfOrNull null
                val end = source.indexOf(close, i + open.length)
                if (end < 0) return@firstNotNullOfOrNull null
                val inner = source.substring(i + open.length, end)
                if (inner.isBlank()) return@firstNotNullOfOrNull null
                // «от $5 до $10» — не формула: как в pandoc, у $…$ нет пробелов у разделителей
                // и цифры сразу после закрывающего.
                val money = open == "$" && (inner.first().isWhitespace() || inner.last().isWhitespace() ||
                    source.getOrNull(end + 1)?.isDigit() == true)
                if (money || (!block && '\n' in inner)) null
                else Triple(inner.trim(), block, end + close.length)
            }
            if (match == null) {
                text.append(source[i])
                i++
                continue
            }
            val (latex, block, next) = match
            flushText()
            parts += if (block) MathPart.Block(latex) else MathPart.Inline(latex)
            i = next
        }
        flushText()
        return parts
    }

    private val SEPARATOR_CELL = Regex(""":?-+:?""")
    private val BARE_RELATION = Regex("""[=<>≤≥≠≈≃≅∼]|\\(?:leq?|geq?|neq?|approx|sim)\b""")

    /** Делит текст на абзацы и таблицы markdown (строки, начинающиеся с «|»). */
    fun blocks(source: String): List<TextBlock> {
        val result = mutableListOf<TextBlock>()
        val prose = mutableListOf<String>()
        fun flushProse() {
            if (prose.isNotEmpty()) result += TextBlock.Prose(prose.joinToString("\n"))
            prose.clear()
        }
        val lines = source.split('\n')
        var i = 0
        while (i < lines.size) {
            if (!lines[i].trimStart().startsWith("|")) {
                prose += lines[i++]
                continue
            }
            val start = i
            while (i < lines.size && lines[i].trimStart().startsWith("|")) i++
            val run = lines.subList(start, i)
            val table = table(run)
            if (table == null) {
                prose += run
            } else {
                flushProse()
                result += TextBlock.Table(table)
            }
        }
        flushProse()
        return result
    }

    private fun table(lines: List<String>): MathTable? {
        if (lines.size < 2) return null
        val rows = lines.map(::splitRow)
        fun isSeparator(row: List<String>) = row.isNotEmpty() && row.all { SEPARATOR_CELL.matches(it) }
        val separator = rows.indexOfFirst(::isSeparator)
        // Without a Markdown separator, ambiguous equations must retain their absolute-value bars.
        // Formula cells explicitly enclosed in $...$ are already protected by splitRow.
        if (separator < 0 && (rows.any { row -> row.size < 2 || row.any { cell ->
                '$' !in cell && BARE_RELATION.containsMatchIn(cell)
            } } || lines.any {
                !it.trimEnd().endsWith("|") || it.trimEnd().endsWith("\\|")
            })) return null
        val header = rows.getOrNull(0)?.takeIf { separator == 1 }
        val body = rows.drop(if (header != null) 1 else 0).filterNot(::isSeparator)
        val align = rows.getOrNull(separator)?.map { cell ->
            when {
                cell.startsWith(':') && cell.endsWith(':') -> CellAlign.Center
                cell.endsWith(':') -> CellAlign.End
                else -> CellAlign.Start
            }
        }.orEmpty()
        val table = MathTable(header, body, align)
        return table.takeIf { it.columns >= 2 && (body.isNotEmpty() || header != null) }
    }

    /** Ячейки строки таблицы; «|» внутри формулы ($|x|$) и экранированный «\|» ячейку не делят. */
    internal fun splitRow(line: String): List<String> {
        var s = line.trim().removePrefix("|")
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var inMath = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && s.getOrNull(i + 1) == '|' -> {
                    // В формуле «\|» — двойная черта (норма), в тексте — просто «|».
                    cell.append(if (inMath) "\\|" else "|")
                    i++
                }
                c == '\\' && s.getOrNull(i + 1) == '$' -> {
                    cell.append("\\$")
                    i++
                }
                c == '$' -> {
                    if (s.getOrNull(i + 1) == '$') {
                        cell.append('$')
                        i++
                    }
                    cell.append('$')
                    inMath = !inMath
                }
                c == '|' && !inMath -> {
                    cells += cell.toString().trim()
                    cell.clear()
                }
                else -> cell.append(c)
            }
            i++
        }
        cells += cell.toString().trim()
        return cells
    }

    private val CYRILLIC = Regex("[\\u0400-\\u04FF\\n]+")
    private val LATEX_HINT = Regex("""\\[a-zA-Z]{2,}|[\^_]\{""")
    private val EDGE_JUNK = Regex("""^[\s:;,.!?)]+|[\s:;,.!?(]+$""")

    /** Выделяет в тексте куски LaTeX без разделителей: «Подставим: x = \frac{1}{2}.» → текст + формула. */
    internal fun bareLatex(text: String): List<MathPart> {
        if (!LATEX_HINT.containsMatchIn(text)) return listOf(MathPart.Text(text))
        val parts = mutableListOf<MathPart>()
        var last = 0
        // Формула не может содержать кириллицу, поэтому кандидаты — промежутки между русскими словами.
        val gaps = buildList {
            var start = 0
            CYRILLIC.findAll(text).forEach { m ->
                add(start until m.range.first)
                start = m.range.last + 1
            }
            add(start until text.length)
        }
        for (gap in gaps) {
            if (gap.isEmpty()) continue
            val raw = text.substring(gap.first, gap.last + 1)
            if (!LATEX_HINT.containsMatchIn(raw)) continue
            val lead = EDGE_JUNK.find(raw)?.takeIf { it.range.first == 0 }?.value?.length ?: 0
            val trail = EDGE_JUNK.findAll(raw).lastOrNull()
                ?.takeIf { it.range.last == raw.lastIndex && it.range.first >= lead }?.value?.length ?: 0
            val latex = raw.substring(lead, raw.length - trail)
            if (latex.isEmpty() || !balanced(latex)) continue
            val start = gap.first + lead
            if (start > last) parts += MathPart.Text(text.substring(last, start))
            parts += MathPart.Inline(latex)
            last = start + latex.length
        }
        if (last < text.length) parts += MathPart.Text(text.substring(last))
        return parts
    }

    private fun balanced(s: String): Boolean {
        var depth = 0
        for (c in s) {
            if (c == '{') depth++
            if (c == '}' && --depth < 0) return false
        }
        return depth == 0
    }

    private val symbols = mapOf(
        "cdot" to "·", "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓",
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "ne" to "≠", "neq" to "≠",
        "approx" to "≈", "infty" to "∞", "to" to "→", "rightarrow" to "→", "Rightarrow" to "⇒",
        "Leftrightarrow" to "⇔", "in" to "∈", "degree" to "°", "circ" to "°", "angle" to "∠",
        "perp" to "⊥", "parallel" to "∥", "triangle" to "△", "sqrt" to "√", "sum" to "∑",
        "int" to "∫", "partial" to "∂", "ldots" to "…", "dots" to "…", "cdots" to "⋯",
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "Delta" to "Δ",
        "epsilon" to "ε", "varepsilon" to "ε", "theta" to "θ", "lambda" to "λ", "mu" to "μ",
        "nu" to "ν", "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "phi" to "φ",
        "varphi" to "φ", "omega" to "ω", "Omega" to "Ω",
    )
    private val silent = setOf(
        "left", "right", "big", "bigl", "bigr", "Big", "Bigl", "Bigr", "text", "mathrm", "mathbf", "textbf",
        "operatorname", "displaystyle", "limits", "quad", "qquad", "boxed",
    )
    private val COMMAND = Regex("""\\([a-zA-Z]+)(\s?)""")
    private val ENVIRONMENT = Regex("""\\(begin|end)\{[^{}]*\}""")
    private val FRAC = Regex("""\\[dt]?frac\{([^{}]*)\}\{([^{}]*)\}""")
    private val SQRT = Regex("""\\sqrt\{([^{}]*)\}""")
    private val SCRIPT = Regex("""([\^_])\{?([0-9n+\-]+)\}?""")
    private const val SUPERSCRIPTS = "⁰¹²³⁴⁵⁶⁷⁸⁹ⁿ⁺⁻"
    private const val SUBSCRIPTS = "₀₁₂₃₄₅₆₇₈₉ₙ₊₋"

    private fun group(s: String) = if (s.length > 1 && s.any { it in "+-−·× " }) "($s)" else s

    /** Текстовая замена формулы — когда LaTeX не удалось нарисовать или нужна строка (копирование). */
    fun toPlain(latex: String): String {
        var s = ENVIRONMENT.replace(latex, "").replace("\\\\", "\n").replace("&", "")
        // Вложенные дроби и корни раскрываются изнутри наружу.
        repeat(3) {
            s = FRAC.replace(s) { m -> group(m.groupValues[1]) + "/" + group(m.groupValues[2]) }
            s = SQRT.replace(s) { m -> "√" + group(m.groupValues[1]) }
        }
        s = SCRIPT.replace(s) { m ->
            val table = if (m.groupValues[1] == "^") SUPERSCRIPTS else SUBSCRIPTS
            m.groupValues[2].map { c -> table["0123456789n+-".indexOf(c)] }.joinToString("")
        }
        // Неизвестная команда остаётся словом (\sin x → sin x), служебные пропадают.
        return COMMAND.replace(s) { m ->
            val name = m.groupValues[1]
            (symbols[name] ?: if (name in silent) "" else name) + m.groupValues[2]
        }
            .replace("\\,", " ").replace("\\;", " ").replace("\\!", "")
            .replace("{", "").replace("}", "")
    }

    /** Весь текст без разметки формул — для буфера обмена и коротких подписей. */
    fun plain(source: String): String = parse(source).joinToString("") { part ->
        when (part) {
            is MathPart.Text -> part.text
            is MathPart.Inline -> toPlain(part.latex)
            is MathPart.Block -> "\n" + toPlain(part.latex) + "\n"
        }
    }.replace(Regex("\n{3,}"), "\n\n").trim()
}
