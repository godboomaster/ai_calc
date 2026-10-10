package com.smartcalc.app.data.plot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlotBuilderTest {

    @Test
    fun functionUsesDomainAndParams() {
        val model = PlotBuilder.build(
            Plot(
                x = PlotAxis(variable = "t", from = "0", to = "5*T"),
                params = mapOf("k" to "2", "T" to "0.5"),
                series = listOf(PlotSeries(label = "h(t)", y = "k*(1-exp(-t/T))")),
            ),
        )!!
        assertEquals(0.0, model.x.min, 1e-12)
        assertEquals(2.5, model.x.max, 1e-12)
        // Ноль рядом с данными попадает в ось, сверху — небольшой запас.
        assertEquals(0.0, model.y.min, 1e-12)
        assertTrue(model.y.max > 2.0 * 0.99)
        assertEquals(0, model.failed)
    }

    @Test
    fun parametricCurveAndLogAxis() {
        val nyquist = PlotBuilder.build(
            Plot(
                params = mapOf("k" to "1", "T" to "1"),
                series = listOf(
                    PlotSeries(x = "k/(1+(w*T)^2)", y = "-k*w*T/(1+(w*T)^2)", variable = "w", from = "0", to = "100"),
                ),
            ),
        )!!
        val points = nyquist.curves.single().segments.flatten()
        assertEquals(1.0, points.first().x, 1e-12)
        assertTrue(points.all { it.y <= 1e-12 })

        val bode = PlotBuilder.build(
            Plot(x = PlotAxis(variable = "w", from = "0.01", to = "100", log = true), series = listOf(PlotSeries(y = "1/w"))),
        )!!
        assertTrue(bode.x.log)
        assertEquals(listOf(0.01, 0.1, 1.0, 10.0, 100.0), bode.x.ticks().map { Math.round(it * 1000) / 1000.0 })
    }

    @Test
    fun parametricCurvesKeepShape() {
        // Окружность: один масштаб по осям (с учётом пропорций области графика), не овал.
        val circle = PlotBuilder.build(
            Plot(series = listOf(PlotSeries(x = "cos(t)", y = "sin(t)", from = "0", to = "2*pi"))),
            aspect = 1.5,
        )!!
        assertEquals(1.5, (circle.x.max - circle.x.min) / (circle.y.max - circle.y.min), 1e-9)

        // АФЧХ при малых ω бежит быстро: там точки добавляются, и соседние оказываются близко.
        val nyquist = PlotBuilder.build(
            Plot(series = listOf(PlotSeries(x = "2/(1+(w/2)^2)", y = "-w/(1+(w/2)^2)", variable = "w", from = "0", to = "200"))),
        )!!
        val points = nyquist.curves.single().segments.flatten()
        val maxStep = points.zipWithNext().maxOf { (a, b) -> Math.hypot(a.x - b.x, a.y - b.y) }
        assertTrue("шаг $maxStep", maxStep < 0.05)
    }

    @Test
    fun tangentIsSplitAtAsymptotes() {
        val model = PlotBuilder.build(Plot(x = PlotAxis(from = "-3", to = "3"), series = listOf(PlotSeries(y = "tan(x)"))))!!
        // Видимая область не растянута выбросами у асимптот, а линия разорвана на три ветви.
        assertTrue(model.y.max < 100)
        assertTrue(model.curves.single().segments.size >= 3)
    }

    @Test
    fun brokenFormulaIsSkippedAndCounted() {
        val model = PlotBuilder.build(
            Plot(series = listOf(PlotSeries(y = "x^2"), PlotSeries(y = "foo(x)"), PlotSeries(points = listOf(listOf(1.0, 2.0))))),
        )
        assertNotNull(model)
        assertEquals(2, model!!.curves.size)
        assertEquals(1, model.failed)
        assertNull(PlotBuilder.build(Plot(series = listOf(PlotSeries(y = "foo(x)")))))
    }

    @Test
    fun formatting() {
        val axis = AxisRange(0.0, 1.0, false)
        assertEquals("0.2", PlotFormat.tick(0.2, axis))
        assertEquals("0", PlotFormat.tick(-1e-17, axis))
        assertEquals("−1.5", PlotFormat.value(-1.5))
        assertEquals("3.142", PlotFormat.value(Math.PI))
        assertEquals("1.5·10⁶", PlotFormat.value(1.5e6))
        assertEquals("10⁻⁵", PlotFormat.tick(1e-5, AxisRange(1e-6, 1.0, true)))
    }
}
