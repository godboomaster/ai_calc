package com.smartcalc.app.ui.calculator

import java.util.Locale
import kotlin.math.abs

object ExpressionParser {

    class ParseException(message: String) : Exception(message)

    fun evaluate(expression: String): Double {
        val normalized = expression
            .replace('×', '*')
            .replace('÷', '/')
            .replace('−', '-')
            .replace(',', '.')
        if (normalized.isBlank()) throw ParseException("Пустое выражение")
        return Parser(normalized).parse()
    }

    fun format(value: Double): String {
        if (value.isNaN()) return "Не число"
        if (value.isInfinite()) return if (value > 0) "∞" else "-∞"
        if (value == Math.floor(value) && abs(value) < 1e15) {
            return value.toLong().toString()
        }
        // За пределами точности double фиксированная запись выдаёт ложные цифры
        if (abs(value) >= 1e15 || abs(value) < 1e-8) {
            val (mantissa, exponent) = "%.9E".format(Locale.US, value).split('E')
            return mantissa.trimEnd('0').trimEnd('.') + "E" + exponent.toInt()
        }
        val s = "%.8f".format(Locale.US, value).trimEnd('0').trimEnd('.')
        return s.ifEmpty { "0" }
    }

    private class Parser(private val input: String) {
        private var pos = 0

        fun parse(): Double {
            val value = parseExpression()
            skipWhitespace()
            if (pos != input.length) throw ParseException("Неожиданный символ на позиции $pos")
            return value
        }

        private fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                skipWhitespace()
                when {
                    match('+') -> value += parseTerm()
                    match('-') -> value -= parseTerm()
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (true) {
                skipWhitespace()
                when {
                    match('*') -> value *= parseFactor()
                    match('/') -> {
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw ParseException("Деление на ноль")
                        value /= divisor
                    }

                    else -> return value
                }
            }
        }

        private fun parseFactor(): Double {
            skipWhitespace()
            if (match('-')) return -parseFactor()
            if (match('+')) return parseFactor()
            var value = parsePrimary()
            skipWhitespace()
            while (match('%')) {
                value /= 100.0
                skipWhitespace()
            }
            return value
        }

        private fun parsePrimary(): Double {
            skipWhitespace()
            if (match('(')) {
                val value = parseExpression()
                skipWhitespace()
                if (!match(')')) throw ParseException("Нет закрывающей скобки")
                return value
            }
            val start = pos
            while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
            if (start == pos) throw ParseException("Ожидалось число на позиции $pos")
            return input.substring(start, pos).toDoubleOrNull()
                ?: throw ParseException("Некорректное число")
        }

        private fun match(c: Char): Boolean {
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
