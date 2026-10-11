package com.smartcalc.app.data.search

import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.history.SolutionStep
import java.util.UUID

/**
 * Готовая задача из базы или шаблона в виде решения, как от ИИ: ответ — на дисплей калькулятора,
 * шаги — на цифры. Ответ — строка «Ответ: …» из решения, шаги — его пункты («1)», «Шаг 2») или абзацы.
 */
object FoundSolution {

    private val ANSWER = Regex("""^[\s#*_>-]*(?:ответ|answer)(?!\p{L})[\s*_]*[:.\-—–]?\s*(.*)$""", RegexOption.IGNORE_CASE)
    /** Начало пункта: «1)», «2.», «Шаг 3:», «**Шаг 4**», «### Шаг 5». Сам знак в заголовок не идёт. */
    private val STEP_MARK = Regex("""^[\s#*]*(?:шаг\s*\d{1,2}\s*[.):]?|\d{1,2}\s*[.)](?=\s|$))[\s*]*""", RegexOption.IGNORE_CASE)
    private val PARAGRAPH = Regex("""\n\s*\n""")

    /** Заголовок шага длиннее — уже не заголовок, а текст: он остаётся в шаге целиком. */
    private const val MAX_TITLE = 60

    /** Абзацев больше — это не шаги, а сплошной текст: он идёт одним шагом. */
    private const val MAX_PARAGRAPH_STEPS = 12

    /** Ответ без строки «Ответ:» — последняя строка решения, если она не слишком длинная. */
    private const val MAX_ANSWER = 160

    /** Одна и та же задача всегда получает тот же id — открытая повторно, она не дублируется в истории. */
    fun idOf(key: String): String = UUID.nameUUIDFromBytes("found:$key".toByteArray()).toString()

    fun of(entry: SearchEntry): Solution {
        entry.history?.let { return it }
        val steps = buildList {
            // Первое фото условия видно над решением; несколько фото — отдельным шагом, крупно.
            if (entry.conditionImages.size > 1) add(SolutionStep("Условие на фото", "", images = entry.conditionImages))
            val text = steps(entry.solution)
            addAll(text)
            if (entry.solutionImages.isNotEmpty()) {
                add(SolutionStep(if (text.isEmpty()) "Решение" else "Решение на фото", "", images = entry.solutionImages))
            }
        }
        return Solution(
            id = idOf(entry.key),
            problem = entry.condition.trim().ifEmpty { if (entry.conditionImages.isNotEmpty()) "Задача с фото" else "Задача" },
            answer = answer(entry.solution) ?: when {
                entry.solutionImages.isNotEmpty() -> "Решение на фото"
                else -> "Решения нет"
            },
            steps = steps,
            imagePath = entry.conditionImages.firstOrNull(),
            source = listOfNotNull(entry.source.label, entry.type).joinToString(" · "),
        )
    }

    /**
     * Ответ из текста решения: строка «Ответ: …» (последняя, если их несколько) или, если после «Ответ:»
     * пусто, следующая строка. Нет такой строки — последняя строка решения.
     */
    internal fun answer(solution: String): String? {
        val lines = solution.lines()
        var found: String? = null
        lines.forEachIndexed { i, line ->
            val m = ANSWER.find(line) ?: return@forEachIndexed
            val rest = m.groupValues[1].trim().trim('*', '_').trim()
            found = rest.ifEmpty { lines.drop(i + 1).firstOrNull { it.isNotBlank() }?.trim() }
        }
        return found?.takeIf { it.isNotBlank() }
            ?: lines.lastOrNull { it.isNotBlank() }?.trim()?.takeIf { it.length <= MAX_ANSWER }
    }

    /** Шаги решения: по пунктам «1)», «2.», «Шаг 3», а если их нет — по абзацам. */
    internal fun steps(solution: String): List<SolutionStep> {
        val text = solution.trim()
        if (text.isEmpty()) return emptyList()
        val chunks = mutableListOf<StringBuilder>()
        val preamble = StringBuilder()
        text.lines().forEach { line ->
            if (STEP_MARK.containsMatchIn(line)) chunks += StringBuilder()
            (chunks.lastOrNull() ?: preamble).append(line).append('\n')
        }
        if (chunks.size >= 2) {
            val steps = chunks.mapIndexed { i, chunk -> step(chunk.toString().trim(), i) }
            // Текст до первого пункта («Дано: …») — начало первого шага.
            val intro = preamble.toString().trim()
            if (intro.isEmpty()) return steps
            return listOf(steps[0].copy(content = "$intro\n${steps[0].content}".trim())) + steps.drop(1)
        }
        val paragraphs = text.split(PARAGRAPH).map { it.trim() }.filter { it.isNotEmpty() }
        if (paragraphs.size !in 2..MAX_PARAGRAPH_STEPS) return listOf(SolutionStep("Решение", text))
        return paragraphs.mapIndexed { i, part -> step(part, i) }
    }

    /** Короткая первая строка — заголовок шага; длинная — начало заголовка, а строка остаётся в тексте. */
    private fun step(part: String, index: Int): SolutionStep {
        val lines = part.lines()
        val first = lines.first().replaceFirst(STEP_MARK, "").trim().trim('*').trim()
        val rest = lines.drop(1).joinToString("\n").trim()
        if (first.isNotEmpty() && rest.isNotEmpty() && first.length <= MAX_TITLE && balanced(first)) {
            return SolutionStep(first.removeSuffix(":"), rest)
        }
        val content = (listOf(first) + lines.drop(1)).joinToString("\n").trim()
        return SolutionStep(shortTitle(first) ?: "Шаг ${index + 1}", content)
    }

    /** Начало строки до пробела, не разрывая формулу $…$; null — если так не получается. */
    private fun shortTitle(line: String): String? {
        if (line.isEmpty()) return null
        if (line.length <= MAX_TITLE && balanced(line)) return line.removeSuffix(":")
        val cut = (MAX_TITLE downTo 20).firstOrNull { i ->
            i < line.length && line[i] == ' ' && balanced(line.substring(0, i))
        } ?: return null
        return line.substring(0, cut).trimEnd(',', ':', ';', ' ') + "…"
    }

    private fun balanced(text: String) = text.count { it == '$' } % 2 == 0
}
