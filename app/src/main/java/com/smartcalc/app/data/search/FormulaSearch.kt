package com.smartcalc.app.data.search

/** A conservative plain-text formula signal, not symbolic algebra or an equivalence checker. */
internal object FormulaSearch {
    data class Formula(val exact: String, val shape: String)

    private val runs = Regex("""[a-zA-Z0-9.,+*/^=(){}\[\]|\s-]+""")
    private val token = Regex("""\d+(?:\.\d+)?|[a-zA-Z]+|[+*/^=()|-]""")
    private val number = Regex("""\d+(?:\.\d+)?""")
    private val functionAssignment = Regex("""^[a-zA-Z]\([a-zA-Z]\)=""")

    fun extract(text: String): List<Formula> {
        val normalized = text.replace('−', '-').replace('–', '-').replace('×', '*')
            .replace('·', '*').replace('÷', '/').replace('[', '(').replace(']', ')')
            .replace('{', '(').replace('}', ')')
        return runs.findAll(normalized).mapNotNull { match ->
            val raw = match.value.trim().trimEnd('.', ',').replace(Regex("\\s+"), "")
                .replace(',', '.').replace(functionAssignment, "")
            if (raw.none { it in "+-*/^=|" }) return@mapNotNull null
            val tokens = token.findAll(raw).map { it.value.lowercase() }.toList()
            // Do not guess at prose or unsupported functions. The lexical channel remains available.
            if (tokens.joinToString("") != raw.lowercase() || tokens.any { it.length > 1 && it.all(Char::isLetter) }) return@mapNotNull null
            if (tokens.none { it.first().isLetterOrDigit() }) return@mapNotNull null
            val explicit = mutableListOf<String>()
            tokens.forEachIndexed { index, value ->
                val previous = tokens.getOrNull(index - 1)
                if (previous != null && (number.matches(previous) || previous == ")") &&
                    (value.singleOrNull()?.isLetter() == true || value == "(")) explicit += "*"
                explicit += value
            }
            val exact = explicit.joinToString("") { if (number.matches(it)) TaskSearch.normalizeNumber(it) else it }
            val shape = explicit.mapIndexed { index, value ->
                // Degree is structural; coefficients can differ between analogous tasks.
                if (number.matches(value) && explicit.getOrNull(index - 1) != "^") "#" else value
            }.joinToString("")
            Formula(exact, shape)
        }.distinct().toList()
    }

    fun similarity(query: List<Formula>, candidate: List<Formula>): Double = when {
        query.any { q -> candidate.any { it.exact == q.exact } } -> 40.0
        query.any { q -> candidate.any { it.shape == q.shape } } -> 24.0
        else -> 0.0
    }
}
