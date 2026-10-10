package com.smartcalc.app.data.plot

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/** Значения переменных при вычислении формулы. */
typealias Variables = Map<String, Double>

/** Скомпилированная формула: разбирается один раз, считается в сотнях точек графика. */
fun interface Formula {
    fun eval(vars: Variables): Double
}

/**
 * Формулы для графиков: переменные, функции, «^», неявное умножение («2x», «2(x+1)», «wT» при
 * переменных w и T). В отличие от калькулятора деление на ноль не ошибка, а бесконечность —
 * на графике это разрыв.
 */
object FormulaParser {

    class ParseException(message: String) : Exception(message)

    private const val MAX_GLUED_NAME = 8

    private val functions1: Map<String, (Double) -> Double> = mapOf(
        "sin" to ::sin, "cos" to ::cos, "tan" to ::tan, "tg" to ::tan,
        "cot" to { v -> 1 / tan(v) }, "ctg" to { v -> 1 / tan(v) },
        "asin" to ::asin, "arcsin" to ::asin, "acos" to ::acos, "arccos" to ::acos,
        "atan" to ::atan, "arctan" to ::atan, "arctg" to ::atan,
        "sinh" to ::sinh, "sh" to ::sinh, "cosh" to ::cosh, "ch" to ::cosh, "tanh" to ::tanh, "th" to ::tanh,
        "sqrt" to ::sqrt, "cbrt" to ::cbrt, "exp" to ::exp,
        // ln и log — натуральный (как в языках программирования), lg — десятичный.
        "ln" to ::ln, "log" to ::ln, "lg" to ::log10, "log10" to ::log10, "log2" to ::log2,
        "abs" to ::abs, "sign" to { v -> sign(v) }, "floor" to ::floor, "ceil" to ::ceil, "round" to ::round,
    )
    private val functions2: Map<String, (Double, Double) -> Double> = mapOf(
        "atan2" to ::atan2, "pow" to { a, b -> a.pow(b) }, "min" to ::min, "max" to ::max,
    )
    private val constants = mapOf("pi" to Math.PI, "π" to Math.PI, "e" to Math.E)

    /** [names] — переменные и параметры, которые формула может использовать. */
    fun compile(source: String, names: Set<String>): Formula {
        val normalized = source
            .replace('·', '*').replace('×', '*').replace('−', '-').replace('–', '-').replace('÷', '/')
            .replace("²", "^2").replace("³", "^3")
            .replace("**", "^")
        if (normalized.isBlank()) throw ParseException("Пустая формула")
        return Parser(normalized, names).parse()
    }

    private class Parser(private val input: String, private val names: Set<String>) {
        private var pos = 0

        fun parse(): Formula {
            val f = expression()
            skipWhitespace()
            if (pos != input.length) throw ParseException("Неожиданный символ «${input[pos]}»")
            return f
        }

        private fun expression(): Formula {
            var f = term()
            while (true) {
                val left = f
                f = when {
                    match('+') -> term().let { r -> Formula { v -> left.eval(v) + r.eval(v) } }
                    match('-') -> term().let { r -> Formula { v -> left.eval(v) - r.eval(v) } }
                    else -> return f
                }
            }
        }

        private fun term(): Formula {
            var f = unary()
            while (true) {
                val left = f
                f = when {
                    match('*') -> unary().let { r -> Formula { v -> left.eval(v) * r.eval(v) } }
                    match('/') -> unary().let { r -> Formula { v -> left.eval(v) / r.eval(v) } }
                    // Неявное умножение: «2x», «2(x+1)», «(x+1)(x-1)», «2 sin(x)».
                    startsPrimary() -> power().let { r -> Formula { v -> left.eval(v) * r.eval(v) } }
                    else -> return f
                }
            }
        }

        private fun unary(): Formula = when {
            match('-') -> unary().let { f -> Formula { v -> -f.eval(v) } }
            match('+') -> unary()
            else -> power()
        }

        /** Степень правоассоциативна, а минус слабее неё: -x^2 = -(x^2), 2^-1 = 0.5. */
        private fun power(): Formula {
            val base = primary()
            if (!match('^')) return base
            val exponent = unary()
            return Formula { v -> base.eval(v).pow(exponent.eval(v)) }
        }

        private fun startsPrimary(): Boolean {
            skipWhitespace()
            if (pos >= input.length) return false
            val c = input[pos]
            return c.isDigit() || c == '.' || c == '(' || c == '√' || c.isLetter()
        }

        private fun primary(): Formula {
            skipWhitespace()
            if (match('(')) {
                val f = expression()
                if (!match(')')) throw ParseException("Нет закрывающей скобки")
                return f
            }
            if (match('√')) {
                val f = power()
                return Formula { v -> sqrt(f.eval(v)) }
            }
            if (match('|')) {
                val f = expression()
                if (!match('|')) throw ParseException("Нет закрывающего «|»")
                return Formula { v -> abs(f.eval(v)) }
            }
            if (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) return number()
            if (pos < input.length && input[pos].isLetter()) return identifier()
            throw ParseException(if (pos < input.length) "Неожиданный символ «${input[pos]}»" else "Формула оборвалась")
        }

        private fun number(): Formula {
            val start = pos
            while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
            // «1e-3» — экспонента, а «2e» — два на число e.
            if (pos < input.length && (input[pos] == 'e' || input[pos] == 'E')) {
                val sign = if (pos + 1 < input.length && input[pos + 1] in "+-") 1 else 0
                if (pos + 1 + sign < input.length && input[pos + 1 + sign].isDigit()) {
                    pos += 1 + sign
                    while (pos < input.length && input[pos].isDigit()) pos++
                }
            }
            val value = input.substring(start, pos).toDoubleOrNull() ?: throw ParseException("Некорректное число")
            return Formula { value }
        }

        private fun identifier(): Formula {
            val start = pos
            while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '_')) pos++
            val name = input.substring(start, pos)
            skipWhitespace()
            if (pos < input.length && input[pos] == '(') {
                functions1[name.lowercase()]?.let { fn ->
                    pos++
                    val arg = expression()
                    if (!match(')')) throw ParseException("Нет закрывающей скобки после $name")
                    return Formula { v -> fn(arg.eval(v)) }
                }
                functions2[name.lowercase()]?.let { fn ->
                    pos++
                    val a = expression()
                    if (!match(',') && !match(';')) throw ParseException("$name ждёт два аргумента")
                    val b = expression()
                    if (!match(')')) throw ParseException("Нет закрывающей скобки после $name")
                    return Formula { v -> fn(a.eval(v), b.eval(v)) }
                }
            }
            return reference(name) ?: throw ParseException("Неизвестное имя «$name»")
        }

        /** Переменная, константа или слитно записанное произведение имён: «wT» = w·T. */
        private fun reference(name: String): Formula? {
            if (name in names) return Formula { v -> v[name] ?: Double.NaN }
            constants[name]?.let { c -> return Formula { c } }
            // Разбиение перебирается рекурсивно — длинное неизвестное слово не стоит разбирать.
            if (name.length > MAX_GLUED_NAME) return null
            for (split in name.length - 1 downTo 1) {
                val head = reference(name.substring(0, split)) ?: continue
                val tail = reference(name.substring(split)) ?: continue
                return Formula { v -> head.eval(v) * tail.eval(v) }
            }
            return null
        }

        private fun match(c: Char): Boolean {
            skipWhitespace()
            if (pos < input.length && input[pos] == c) {
                pos++
                return true
            }
            return false
        }

        private fun skipWhitespace() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }
    }
}
