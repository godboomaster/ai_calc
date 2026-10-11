package com.smartcalc.app.ui.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExpressionParserTest {

    private fun eval(expr: String) = ExpressionParser.evaluate(expr)

    @Test
    fun arithmetic() {
        assertEquals(7.0, eval("3+4"), 1e-9)
        assertEquals(14.0, eval("2+3×4"), 1e-9)
        assertEquals(20.0, eval("(2+3)×4"), 1e-9)
        assertEquals(2.5, eval("5÷2"), 1e-9)
        assertEquals(1.0, eval("10−9"), 1e-9)
    }

    @Test
    fun unaryAndPercent() {
        assertEquals(-5.0, eval("-5"), 1e-9)
        assertEquals(3.0, eval("--3"), 1e-9)
        assertEquals(0.5, eval("50%"), 1e-9)
        assertEquals(1.5, eval("150%"), 1e-9)
        assertEquals(100.5, eval("100+50%"), 1e-9)
    }

    @Test
    fun decimals() {
        assertEquals(0.3, eval("0.1+0.2"), 1e-9)
        assertEquals(0.5, eval("2.5−2"), 1e-9)
    }

    @Test
    fun errors() {
        assertThrows(ExpressionParser.ParseException::class.java) { eval("1/0") }
        assertThrows(ExpressionParser.ParseException::class.java) { eval("(1+2") }
        assertThrows(ExpressionParser.ParseException::class.java) { eval("abc") }
    }

    @Test
    fun formatting() {
        assertEquals("42", ExpressionParser.format(42.0))
        assertEquals("2.5", ExpressionParser.format(2.5))
        assertEquals("0.3", ExpressionParser.format(0.30000000000000004))
    }

    @Test
    fun formattingOutOfDoubleRange() {
        assertEquals("1.219326312E17", ExpressionParser.format(eval("123456789×987654321+123456789")))
        assertEquals("-2.5E20", ExpressionParser.format(-2.5e20))
        assertEquals("1E-10", ExpressionParser.format(1e-10))
    }

    @Test fun formattedResultsCanBeUsedInAnotherCalculation() {
        assertEquals(1_000_000_000_000_001.0, eval(ExpressionParser.format(1e15) + "+1"), 0.0)
        assertEquals(1e-9, eval(ExpressionParser.format(1e-10) + "×10"), 1e-20)
        assertEquals(-2500.0, eval("-2.5E+3"), 0.0)
        assertEquals(0.02, eval("2e-2"), 1e-12)
        assertEquals(Double.POSITIVE_INFINITY, eval("∞+1"), 0.0)
    }

    @Test fun incompleteExponentsAreRejected() {
        for (input in listOf("1E", "1E+", "1E-", "1E2.5", "1EE2")) {
            assertThrows(ExpressionParser.ParseException::class.java) { eval(input) }
        }
    }
}
