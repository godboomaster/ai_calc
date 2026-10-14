package com.smartcalc.app.data.search

/** A conservative plain-text formula signal, not symbolic algebra or an equivalence checker. */
internal object FormulaSearch {
    data class Formula(val exact: String, val shape: String)

    private val runs = Regex("""[a-zA-Z\u0370-\u03FF0-9.,+*/^=(){}\[\]|\s-]+""")
    private val token = Regex("""\d+(?:\.\d+)?|[a-zA-Z]+|[\u0370-\u03FF]|[+*/^=()|-]""")
    private val number = Regex("""\d+(?:\.\d+)?""")
    private val functionAssignment = Regex("""^[a-zA-Z]\([a-zA-Z]\)=""")

    fun extract(text: String): List<Formula> {
        val normalized = plain(text).replace('[', '(').replace(']', ')').replace('{', '(').replace('}', ')')
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

    private val environment = Regex("""\\(?:begin|end)\{[^{}]*\}""")
    private val spacing = Regex("""\\[,;:! ]|\\[{}]""")
    private val fraction = Regex("""\\[dt]?frac\{([^{}]*)\}\{([^{}]*)\}""")
    private val root = Regex("""\\sqrt\{([^{}]*)\}""")
    private val script = Regex("""([\^_])\{([^{}]*)\}""")
    private val wrapper = Regex(
        """\\(?:math[a-z]+|text[a-z]*|operatorname|boxed|hat|widehat|bar|overline|underline|vec|dot|ddot|tilde|widetilde)\{([^{}]*)\}""",
    )
    private val command = Regex("""\\([a-zA-Z]+)""")
    private val superscript = Regex("[⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻ⁿ]+")
    private const val SUPERSCRIPTS = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻ⁿ"
    private const val PLAIN_SCRIPTS = "0123456789+-n"

    private val greek = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "θ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ", "sigma" to "σ",
        "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    )
    private val greekVariants = mapOf("ϕ" to "φ", "ϵ" to "ε", "ϑ" to "θ", "ς" to "σ")
    private val functions = setOf(
        "sin", "cos", "tg", "tan", "ctg", "cot", "sec", "csc", "arcsin", "arccos", "arctg", "arctan", "arcctg",
        "sinh", "cosh", "tanh", "sh", "ch", "th", "exp", "ln", "lg", "log", "sqrt", "lim", "max", "min", "det",
        "arg", "sgn", "sign", "mod", "re", "im",
    )
    private val commandSymbols = mapOf(
        "cdot" to "*", "times" to "*", "ast" to "*", "div" to "/", "pm" to "±", "mp" to "∓",
        "le" to "≤", "leq" to "≤", "leqslant" to "≤", "ge" to "≥", "geq" to "≥", "geqslant" to "≥",
        "ne" to "≠", "neq" to "≠", "approx" to "≈", "lt" to "<", "gt" to ">", "infty" to "∞",
        "to" to "→", "rightarrow" to "→", "in" to "∈", "int" to "∫", "sum" to "∑", "partial" to "∂", "prime" to "'",
    )

    private fun group(s: String) = if (s.any { it in "+-*/=,; " } || '\\' in s) "($s)" else s

    /**
     * Linear keyboard notation for LaTeX and typographic math, so a typed query and a rendered
     * condition meet: \dfrac{1}{s-\alpha} → 1/(s-α), e^{\alpha t} → e^(α t), x² → x^(2).
     * Unknown commands such as \left or \displaystyle are dropped; text without LaTeX keeps its words.
     */
    fun plain(text: String): String {
        var s = superscript.replace(text) { m -> "^(" + m.value.map { PLAIN_SCRIPTS[SUPERSCRIPTS.indexOf(it)] }.joinToString("") + ")" }
            .replace(environment, " ").replace("\\\\", "\n").replace('&', ' ').replace(spacing, " ")
        // Nested groups are unwrapped from the inside out: \dfrac{1}{s^{2}} needs s^{2} first.
        // Every pass removes a pair of braces, so the loop ends.
        do {
            val before = s
            s = wrapper.replace(s) { " " + it.groupValues[1] + " " }
            s = script.replace(s) { it.groupValues[1] + "(" + it.groupValues[2] + ")" }
            s = fraction.replace(s) { group(it.groupValues[1]) + "/" + group(it.groupValues[2]) }
            s = root.replace(s) { "sqrt(" + it.groupValues[1] + ")" }
        } while (s != before)
        s = command.replace(s) { m ->
            val name = m.groupValues[1]
            commandSymbols[name] ?: greek[name] ?: greek[name.lowercase()]?.uppercase()?.takeIf { name.first().isUpperCase() }
                ?: if (name.lowercase() in functions) " $name " else " "
        }
        return s.replace('{', '(').replace('}', ')').replace('$', ' ')
            .replace('−', '-').replace('–', '-').replace('·', '*').replace('⋅', '*').replace('×', '*').replace('÷', '/')
    }

    private val symbolRun = Regex("""[a-zA-Z\u0370-\u03FF0-9.,+\-*/^=()\[\]|<>≤≥≠≈→∈∞±∓∫∑∂√'!_ \t]+""")
    private val symbolToken = Regex("""\d+(?:[.,]\d+)?|[a-zA-Z]+|[\u0370-\u03FF]|[+\-/^=|<>≤≥≠≈→∈∞±∓∫∑∂√'!]""")
    private val word = Regex("""[a-zA-Z]{3,}""")

    /**
     * Symbols of one formula in order: numbers, variables, function names, operations. Brackets,
     * multiplication and subscripts are notation, not content: e^{\alpha t}, e^(αt) and e^αt are the
     * same, as are T_1 and T1. Letter runs other than function and Greek names are products: RC is R·C.
     */
    private fun tokens(run: String): List<String> = symbolToken.findAll(run).flatMap { m ->
        val value = m.value.lowercase()
        when {
            value.first().isDigit() -> sequenceOf(TaskSearch.normalizeNumber(value))
            value == "√" -> sequenceOf("sqrt")
            value.length == 1 -> sequenceOf(greekVariants[value] ?: value)
            value in functions -> sequenceOf(value)
            else -> greek[value]?.let { sequenceOf(it) } ?: value.asSequence().map(Char::toString)
        }
    }.toList()

    /** Formulas of [text], each as its symbols; Cyrillic prose and line breaks separate formulas. */
    fun symbols(text: String): List<List<String>> =
        symbolRun.findAll(plain(text)).map { tokens(it.value) }.filter { it.isNotEmpty() }.toList()

    /**
     * Formula fragments of a query to match symbol by symbol: f(t), e, 1/(s-a). Numbers alone and
     * whole Latin words are left to the lexical channel. [query] may name a letter by its Cyrillic twin.
     */
    fun fragments(query: String): List<Pair<String, List<String>>> =
        symbolRun.findAll(plain(latinTwins(query))).mapNotNull { m ->
            val shown = m.value.trim().trimEnd('.', ',')
            val tokens = tokens(m.value)
            if (tokens.none { it.first().isLetter() } || word.matches(shown)) null else shown to tokens
        }.distinctBy { it.second }.toList()

    /** Greek letters a phone keyboard lacks are typed as these Latin ones: e^(at) for e^{\alpha t}, w for ω. */
    private val latinStandIns = mapOf("α" to "a", "β" to "b", "γ" to "g", "δ" to "d", "λ" to "l", "ω" to "w")

    private fun same(formula: String, query: String) = formula == query || latinStandIns[formula] == query

    private fun startsAt(formula: List<String>, start: Int, fragment: List<String>) =
        fragment.indices.all { same(formula[start + it], fragment[it]) }

    fun contains(formulas: List<List<String>>, fragment: List<String>) =
        formulas.any { f -> (0..f.size - fragment.size).any { startsAt(f, it, fragment) } }

    fun isWhole(formulas: List<List<String>>, fragment: List<String>) =
        formulas.any { it.size == fragment.size && startsAt(it, 0, fragment) }

    private val twins = mapOf(
        'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'у' to 'y', 'х' to 'x', 'к' to 'k',
        'А' to 'A', 'В' to 'B', 'Е' to 'E', 'К' to 'K', 'М' to 'M', 'Н' to 'H', 'О' to 'O', 'Р' to 'P',
        'С' to 'C', 'Т' to 'T', 'Х' to 'X',
    )

    /**
     * A query typed on a Russian layout: «е» alone or next to formula signs (е^(-t), f(t)=е) is the
     * Latin e. Inside words and in «1.2 а)» or «45 с» Cyrillic stays: there it is a letter or a unit.
     */
    private fun latinTwins(query: String): String {
        val alone = query.trim().length == 1
        fun cyrillic(c: Char?) = c != null && c in '\u0400'..'\u04FF'
        fun formula(c: Char?) = c != null && (c in 'a'..'z' || c in 'A'..'Z' || c in '\u0370'..'\u03FF' || c in "^_+-*/=")
        return String(CharArray(query.length) { i ->
            val c = query[i]
            val twin = twins[c]
            val previous = query.getOrNull(i - 1)
            val next = query.getOrNull(i + 1)
            if (twin != null && !cyrillic(previous) && !cyrillic(next) && (alone || formula(previous) || formula(next))) twin else c
        })
    }
}
