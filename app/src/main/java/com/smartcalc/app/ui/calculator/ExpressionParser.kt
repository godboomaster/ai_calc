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

        // Keep a percentage rate until +/− can apply it to the left-hand amount.
        // Scaling a rate keeps it a rate; dividing two rates produces an ordinary number.
        private data class Operand(val number: Double, val percentDegree: Int = 0)

        fun parse(): Double {
            val value = parseExpression()
            skipWhitespace()
            if (pos != input.length) throw ParseException("Неожиданный символ на позиции $pos")
            return value.number
        }

        private fun parseExpression(): Operand {
            var value = parseTerm()
            while (true) {
                skipWhitespace()
                val subtract = when {
                    match('+') -> false
                    match('-') -> true
                    else -> return value
                }
                val right = parseTerm()
                val amount = if (right.percentDegree > 0) value.number * right.number else right.number
                value = Operand(if (subtract) value.number - amount else value.number + amount)
            }
        }

        private fun parseTerm(): Operand {
            var value = parseFactor()
            while (true) {
                skipWhitespace()
                when {
                    match('*') -> {
                        val right = parseFactor()
                        value = Operand(value.number * right.number, percentDegree = value.percentDegree + right.percentDegree)
                    }
                    match('/') -> {
                        val divisor = parseFactor()
                        if (divisor.number == 0.0) throw ParseException("Деление на ноль")
                        value = Operand(value.number / divisor.number, percentDegree = value.percentDegree - divisor.percentDegree)
                    }

                    else -> return value
                }
            }
        }

        private fun parseFactor(): Operand {
            skipWhitespace()
            if (match('-')) return parseFactor().let { it.copy(number = -it.number) }
            if (match('+')) return parseFactor()
            var value = parsePrimary()
            skipWhitespace()
            while (match('%')) {
                value = Operand(value.number / 100.0, percentDegree = value.percentDegree + 1)
                skipWhitespace()
            }
            return value
        }

        private fun parsePrimary(): Operand {
            skipWhitespace()
            if (match('(')) {
                val value = parseExpression()
                skipWhitespace()
                if (!match(')')) throw ParseException("Нет закрывающей скобки")
                return value
            }
            if (match('∞')) return Operand(Double.POSITIVE_INFINITY)
            val start = pos
            while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
            if (start == pos) throw ParseException("Ожидалось число на позиции $pos")
            if (match('E') || match('e')) {
                if (!match('+')) match('-')
                val exponentStart = pos
                while (pos < input.length && input[pos].isDigit()) pos++
                if (pos == exponentStart) throw ParseException("Ожидался показатель степени на позиции $pos")
            }
            return Operand(input.substring(start, pos).toDoubleOrNull()
                ?: throw ParseException("Некорректное число"))
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
