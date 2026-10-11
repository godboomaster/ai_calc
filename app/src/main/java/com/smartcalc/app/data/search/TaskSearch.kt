package com.smartcalc.app.data.search

import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.template.TaskTemplate
import com.smartcalc.app.data.template.ExampleFingerprint
import kotlin.math.ln

/** Откуда решённая задача: база задач, пример шаблона или история решений. */
enum class FoundSource(val label: String) { BASE("База"), TEMPLATE("Шаблон"), HISTORY("История") }

/**
 * Решённая задача, по которой идёт поиск. Числа и основы слов считаются один раз, при сборке списка,
 * а не на каждую букву запроса.
 */
class SearchEntry(
    /** Постоянный ключ задачи: из него же получается id записи в истории, когда её открывают. */
    val key: String,
    val source: FoundSource,
    /** Тип задачи — название раздела базы или шаблона; у истории — шаблон, по которому решал ИИ. */
    val type: String?,
    val condition: String,
    val solution: String,
    val conditionImages: List<String> = emptyList(),
    val solutionImages: List<String> = emptyList(),
    /** Запись истории открывается как есть — со всеми дополнениями. */
    val history: Solution? = null,
) {
    internal val conditionTerms = TaskSearch.terms(condition)
    internal val solutionTerms = TaskSearch.terms(solution)
    internal val typeTerms = TaskSearch.terms(type.orEmpty())
    internal val conditionFormulas = FormulaSearch.extract(condition)
    internal val solutionFormulas = FormulaSearch.extract(solution)
}

/** Найденная задача: [matched] — что из запроса в ней нашлось, в том виде, как это набрано. */
class SearchHit(val entry: SearchEntry, val score: Double, val matched: List<String>)

/**
 * Поиск похожих задач без ИИ и без сети: по числам из условия и по словам — что дано, что найти,
 * тип задачи. Слова сравниваются по основе («скорость», «скорости», «скоростью» — одно слово).
 * Чем реже слово или число встречается во всей базе, тем больше оно значит: «найти» есть почти
 * везде, а «12,5» — в одной-двух задачах.
 */
object TaskSearch {

    private val NUMBER = Regex("""\d+(?:[.,]\d+)?""")
    private val WORD = Regex("""\p{L}+""")

    /** Короче — это обозначения и единицы («м», «кг», «x»): совпадают почти у всех задач. */
    private const val MIN_WORD = 3

    /** Основы короче не сравниваются по началу: «сил» не должно находить «сильный». */
    private const val MIN_PREFIX = 4

    private val STOP_WORDS = setOf(
        "что", "как", "для", "это", "при", "или", "его", "она", "они", "так", "все", "чем", "если",
        "этот", "эта", "эти", "который", "которая", "которое", "которые", "and", "the",
        "найти", "найди", "найдите", "решить", "реши", "вычислить", "определить", "пример",
        "похожий", "похожую", "решенную", "решённую", "задачу", "задача",
    )

    /**
     * Окончания существительных и прилагательных, которые отрезаются от слова, — длинные раньше
     * коротких. Глагольных нет: «скорость» не должна превращаться в «скоро».
     */
    private val ENDINGS = listOf(
        "иями", "ями", "ами", "иях", "ого", "его", "ому", "ему", "ыми", "ими", "ием", "ией",
        "ях", "ах", "ий", "ый", "ой", "ей", "ая", "яя", "ое", "ее", "ые", "ие", "ых", "их",
        "ым", "им", "ом", "ем", "ам", "ям", "ую", "юю", "ов", "ев", "ью", "ия", "ии", "ию",
        "а", "я", "о", "е", "ы", "и", "у", "ю", "ь", "й",
    )

    /** Слово запроса: [term] — с чем сравнивать, [shown] — как показать среди совпадений. */
    private class QueryTerm(val term: String, val shown: String, val number: Boolean)

    /** «3,50» и «3.5» — одно число, «007» и «7» — тоже. */
    internal fun normalizeNumber(raw: String): String {
        val text = raw.replace(',', '.')
        val whole = text.substringBefore('.').trimStart('0').ifEmpty { "0" }
        val fraction = text.substringAfter('.', "").trimEnd('0')
        return if (fraction.isEmpty()) whole else "$whole.$fraction"
    }

    internal fun stem(word: String): String {
        val w = word.lowercase().replace('ё', 'е')
        val ending = ENDINGS.firstOrNull { w.endsWith(it) && w.length - it.length >= MIN_WORD }
        return if (ending == null) w else w.dropLast(ending.length)
    }

    /** Числа и основы слов текста — то, по чему его можно найти. */
    internal fun terms(text: String): Set<String> = queryTerms(text).mapTo(HashSet()) { it.term }

    private fun queryTerms(text: String): List<QueryTerm> {
        val numbers = NUMBER.findAll(text).map { QueryTerm(normalizeNumber(it.value), it.value, number = true) }
        val words = WORD.findAll(text)
            .map { it.value }
            .filter { it.length >= MIN_WORD && it.lowercase() !in STOP_WORDS }
            .map { QueryTerm(stem(it), it, number = false) }
        return (numbers + words).distinctBy { it.term }.toList()
    }

    /** Есть ли слово в тексте: точно или как начало другого слова («врем» — «времен»). */
    private fun contains(terms: Set<String>, t: QueryTerm): Boolean {
        if (t.term in terms) return true
        if (t.number || t.term.length < MIN_PREFIX) return false
        return terms.any { it.length >= MIN_PREFIX && !it.first().isDigit() && (it.startsWith(t.term) || t.term.startsWith(it)) }
    }

    /** Насколько важно, где нашлось: в условии и типе — главное, в решении — промежуточные числа. */
    private fun placeWeight(e: SearchEntry, t: QueryTerm): Int = when {
        contains(e.conditionTerms, t) -> 3
        contains(e.typeTerms, t) -> 2
        contains(e.solutionTerms, t) -> 1
        else -> 0
    }

    /**
     * Задачи, похожие на [query], лучшие — первыми; [type] — только задачи этого типа. Пустой запрос —
     * все задачи (или все задачи типа) по порядку: сначала база, потом шаблоны, потом история.
     */
    fun search(entries: List<SearchEntry>, query: String, type: String? = null): List<SearchHit> {
        val pool = if (type == null) entries else entries.filter { it.type == type }
        val terms = queryTerms(query)
        if (query.isBlank()) return pool.map { SearchHit(it, 0.0, emptyList()) }
        val formulas = FormulaSearch.extract(query)
        if (terms.isEmpty() && formulas.isEmpty()) return emptyList()
        val weights = pool.map { e -> IntArray(terms.size) { placeWeight(e, terms[it]) } }
        // В скольких задачах есть каждое слово запроса: частые весят меньше редких.
        val found = IntArray(terms.size) { i -> weights.count { it[i] > 0 } }
        return pool.indices.mapNotNull { k ->
            val e = pool[k]
            val formulaScore = maxOf(
                FormulaSearch.similarity(formulas, e.conditionFormulas),
                FormulaSearch.similarity(formulas, e.solutionFormulas) * 0.5,
            )
            // A textual question needs a subject match; incidental numbers alone are insufficient.
            if (terms.any { !it.number } && terms.indices.none { !terms[it].number && weights[k][it] > 0 } && formulaScore == 0.0) return@mapNotNull null
            var score = 0.0
            val matched = mutableListOf<String>()
            terms.forEachIndexed { i, t ->
                val place = weights[k][i]
                if (place == 0) return@forEachIndexed
                val rarity = ln(1.0 + pool.size.toDouble() / found[i])
                // Числа из условия — самый точный признак той же задачи.
                score += place * rarity * (if (t.number) 1.5 else 1.0)
                matched += t.shown
            }
            if (matched.isEmpty() && formulaScore == 0.0) return@mapNotNull null
            // Задача, где нашлось всё набранное, выше той, где одно слово, но редкое.
            val coverage = if (terms.isEmpty()) 1.0 else matched.size.toDouble() / terms.size
            SearchHit(e, formulaScore + score * (0.5 + 0.5 * coverage), matched)
        }.sortedByDescending { it.score }
    }

    /**
     * Все задачи для поиска: разделы базы, примеры шаблонов и история. Задача, прикреплённая из базы
     * к шаблону, есть в обоих местах — в списке она одна. Решение из базы, однажды открытое, попадает
     * в историю — там оно тоже не повторяется, пока задача есть в базе.
     */
    fun entries(base: List<TaskTemplate>, templates: List<TaskTemplate>, history: List<Solution>): List<SearchEntry> {
        val result = mutableListOf<SearchEntry>()
        val seen = HashSet<String>()
        val savedById = history.associateBy { it.id }
        fun addAll(sections: List<TaskTemplate>, source: FoundSource) = sections.forEach { section ->
            section.examples.forEach { ex ->
                if (ex.isEmpty) return@forEach
                val key = ExampleFingerprint.key(ex)
                if (seen.add(key)) {
                    val saved = savedById[FoundSolution.idOf(key)]
                    val searchable = listOfNotNull(ex.solution, saved?.searchableText()).joinToString("\n")
                    result += SearchEntry(key, source, section.name, ex.condition, searchable, ex.conditionImages, ex.solutionImages, history = saved)
                }
            }
        }
        addAll(base, FoundSource.BASE)
        addAll(templates, FoundSource.TEMPLATE)
        val opened = result.mapTo(HashSet()) { FoundSolution.idOf(it.key) }
        history.filter { it.id !in opened }.forEach { s ->
            result += SearchEntry("history:${s.id}", FoundSource.HISTORY, s.template, s.problem, s.searchableText(), history = s)
        }
        return result
    }

    private fun Solution.searchableText() = buildString {
        steps.forEach { appendLine(it.title).appendLine(it.content) }
        appendLine(answer)
        followUps.forEach { f ->
            appendLine(f.question)
            f.steps.forEach { appendLine(it.title).appendLine(it.content) }
            appendLine(f.answer)
        }
    }
}
