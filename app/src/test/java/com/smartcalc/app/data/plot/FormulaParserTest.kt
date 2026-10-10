package com.smartcalc.app.data.plot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.sqrt

class FormulaParserTest {

    private fun eval(src: String, vararg vars: Pair<String, Double>) =
        FormulaParser.compile(src, vars.map { it.first }.toSet()).eval(vars.toMap())

    @Test
    fun precedence() {
        assertEquals(14.0, eval("2+3*4"), 1e-12)
        assertEquals(-9.0, eval("-3^2"), 1e-12)
        assertEquals(512.0, eval("2^3^2"), 1e-12)
        assertEquals(0.5, eval("2^-1"), 1e-12)
        assertEquals(2.0, eval("|1-3|"), 1e-12)
    }

    @Test
    fun variablesAndFunctions() {
        val k = 2.0
        val t = 0.5
        val w = 3.0
        assertEquals(k / sqrt(1 + (w * t) * (w * t)), eval("k/sqrt(1+(w*T)^2)", "k" to k, "T" to t, "w" to w), 1e-12)
        assertEquals(-atan(w * t), eval("-atan(w*T)", "T" to t, "w" to w), 1e-12)
        assertEquals(k * (1 - exp(-1.0 / t)), eval("k*(1-exp(-t/T))", "k" to k, "T" to t, "t" to 1.0), 1e-12)
        assertEquals(PI / 4, eval("atan2(1, 1)"), 1e-12)
        assertEquals(1.0, eval("lg(10)"), 1e-12)
        assertEquals(1.0, eval("ln(e)"), 1e-12)
    }

    @Test
    fun implicitMultiplicationAndUnicode() {
        assertEquals(10.0, eval("2x", "x" to 5.0), 1e-12)
        assertEquals(12.0, eval("2(x+1)", "x" to 5.0), 1e-12)
        assertEquals(24.0, eval("(x+1)(x-1)", "x" to 5.0), 1e-12)
        assertEquals(1.5, eval("wT", "w" to 3.0, "T" to 0.5), 1e-12)
        assertEquals(2 * PI, eval("2π"), 1e-12)
        assertEquals(3.0, eval("√x", "x" to 9.0), 1e-12)
        assertEquals(25.0, eval("x²", "x" to 5.0), 1e-12)
        assertEquals(6.0, eval("ω·2", "ω" to 3.0), 1e-12)
        assertEquals(0.001, eval("1e-3"), 1e-15)
    }

    @Test
    fun divisionByZeroIsInfinityNotError() {
        assertTrue(eval("1/x", "x" to 0.0).isInfinite())
    }

    @Test
    fun errors() {
        assertThrows(FormulaParser.ParseException::class.java) { eval("y+1", "x" to 1.0) }
        assertThrows(FormulaParser.ParseException::class.java) { eval("sin(x", "x" to 1.0) }
        assertThrows(FormulaParser.ParseException::class.java) { eval("undefinedvariablename") }
    }
}
