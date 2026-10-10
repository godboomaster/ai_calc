package com.smartcalc.app.data.plot

import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class PlotPoint(val x: Double, val y: Double)

/** Кривая на графике: непрерывные куски (разрывы — где функция не определена) и маркеры для таблиц. */
class PlotCurve(val label: String?, val segments: List<List<PlotPoint>>, val markers: Boolean)

class AxisRange(val min: Double, val max: Double, val log: Boolean) {

    /** Положение значения на оси: 0 — начало, 1 — конец. */
    fun fraction(v: Double): Double =
        if (log) (log10(v) - log10(min)) / (log10(max) - log10(min)) else (v - min) / (max - min)

    fun ticks(): List<Double> = if (log) logTicks() else linearTicks()

    /** Шаг делений для подписи чисел: у линейной оси — расстояние между делениями. */
    val step: Double get() = niceStep((max - min) / 5)

    private fun linearTicks(): List<Double> {
        val step = step
        val first = ceil(min / step - 1e-9) * step
        return generateSequence(first) { it + step }
            .takeWhile { it <= max + step * 1e-9 }
            .map { if (abs(it) < step * 1e-9) 0.0 else it }
            .toList()
    }

    private fun logTicks(): List<Double> {
        val decades = (ceil(log10(min) - 1e-9).toInt()..floor(log10(max) + 1e-9).toInt()).toList()
        if (decades.size < 2) {
            // Меньше двух декад — добавляем 2 и 5 внутри каждой.
            val lo = floor(log10(min)).toInt()
            return (lo..ceil(log10(max)).toInt()).flatMap { k ->
                listOf(1.0, 2.0, 5.0).map { it * 10.0.pow(k) }
            }.filter { it >= min * (1 - 1e-9) && it <= max * (1 + 1e-9) }
        }
        val every = (decades.size + 5) / 6
        return decades.filter { (it - decades.first()) % every == 0 }.map { 10.0.pow(it) }
    }

    companion object {
        fun niceStep(raw: Double): Double {
            val exponent = floor(log10(raw))
            val fraction = raw / 10.0.pow(exponent)
            val nice = when {
                fraction < 1.5 -> 1.0
                fraction < 3 -> 2.0
                fraction < 7 -> 5.0
                else -> 10.0
            }
            return nice * 10.0.pow(exponent)
        }
    }
}

/** Готовый к рисованию график: точки посчитаны, диапазоны осей выбраны. */
class PlotModel(
    val title: String?,
    val xLabel: String?,
    val yLabel: String?,
    val x: AxisRange,
    val y: AxisRange,
    val curves: List<PlotCurve>,
    /** Сколько кривых не удалось построить (ошибка в формуле от модели). */
    val failed: Int,
)

object PlotBuilder {

    private const val FUNCTION_SAMPLES = 400
    private const val PARAMETRIC_SAMPLES = 300

    /** Параметрическая кривая дробится, пока соседние точки дальше этой доли размера графика. */
    private const val MAX_STEP = 0.01
    private const val MAX_REFINE_DEPTH = 10

    /** Годограф и окружность рисуются в одном масштабе по осям, если их размахи сравнимы. */
    private const val EQUAL_SCALE_RATIO = 5.0

    /**
     * null — построить нечего: ни одна кривая не дала точек. [aspect] — ширина области графика,
     * делённая на высоту (холст 1.3 : 1 за вычетом полей под подписи): нужна, чтобы
     * параметрические кривые не искажались.
     */
    fun build(plot: Plot, aspect: Double = 1.45): PlotModel? {
        val params = evaluateParams(plot.params)
        fun bound(s: String?): Double? = s?.let { constant(it, params) }?.takeIf { it.isFinite() }

        val xVar = plot.x.variable?.trim().takeUnless { it.isNullOrEmpty() } ?: "x"
        val xLog = plot.x.log
        // Область кривых y(x): заданные границы оси или разумные по умолчанию.
        var domainFrom = bound(plot.x.from) ?: if (xLog) 0.01 else -10.0
        val domainTo = bound(plot.x.to) ?: if (xLog) 100.0 else 10.0
        if (xLog && domainFrom <= 0) domainFrom = domainTo / 1e4

        var failed = 0
        val raw = plot.series.mapNotNull { s ->
            try {
                when {
                    s.points != null -> PlotCurve(
                        s.label,
                        listOf(s.points.filter { it.size >= 2 }.map { PlotPoint(it[0], it[1]) }),
                        markers = true,
                    )

                    s.x != null && s.y != null -> {
                        val v = s.variable?.trim().takeUnless { it.isNullOrEmpty() } ?: "t"
                        val names = params.keys + v
                        val fx = FormulaParser.compile(s.x, names)
                        val fy = FormulaParser.compile(s.y, names)
                        var from = bound(s.from) ?: 0.0
                        val to = bound(s.to) ?: 10.0
                        if (s.log && from <= 0) from = to / 1e4
                        val point = { t: Double ->
                            val vars = params + (v to t)
                            PlotPoint(fx.eval(vars), fy.eval(vars))
                        }
                        val samples = refine(sampleRange(from, to, PARAMETRIC_SAMPLES, s.log && to > 0), point)
                        PlotCurve(s.label, split(samples, xLog, plot.y.log), markers = false)
                    }

                    s.y != null -> {
                        val v = s.variable?.trim().takeUnless { it.isNullOrEmpty() } ?: xVar
                        val fy = FormulaParser.compile(s.y, params.keys + v)
                        val samples = sampleRange(domainFrom, domainTo, FUNCTION_SAMPLES, xLog).map { x ->
                            PlotPoint(x, fy.eval(params + (v to x)))
                        }
                        PlotCurve(s.label, split(samples, xLog, plot.y.log), markers = false)
                    }

                    else -> null
                }
            } catch (e: FormulaParser.ParseException) {
                failed++
                null
            }
        }.filter { c -> c.segments.any { it.isNotEmpty() } }
        if (raw.isEmpty()) return null

        val points = raw.flatMap { c -> c.segments.flatten() }
        val hasFunctions = plot.series.any { it.points == null && it.x == null && it.y != null }
        var x = range(
            points.map { it.x }, bound(plot.x.from), bound(plot.x.to), xLog,
            robust = false, pad = !hasFunctions,
        )
        var y = range(points.map { it.y }, bound(plot.y.from), bound(plot.y.to), plot.y.log, robust = true, pad = true)
        val parametricOnly = plot.series.all { it.x != null && it.y != null }
        if (parametricOnly && !xLog && !plot.y.log && plot.x.from == null && plot.y.from == null) {
            equalScale(x, y, aspect)?.let { (ex, ey) ->
                x = ex
                y = ey
            }
        }
        val curves = raw.map { c ->
            if (c.markers) c else PlotCurve(c.label, c.segments.flatMap { splitAsymptotes(it, y) }, false)
        }
        return PlotModel(
            plot.title?.takeIf { it.isNotBlank() },
            plot.x.label?.takeIf { it.isNotBlank() },
            plot.y.label?.takeIf { it.isNotBlank() },
            x, y, curves, failed,
        )
    }

    /**
     * Параметр кривой с равным шагом даёт углы там, где точка движется быстро (у АФЧХ — при малых ω).
     * Такие промежутки делим пополам, пока соседние точки не окажутся близко.
     */
    private fun refine(params: List<Double>, point: (Double) -> PlotPoint): List<PlotPoint> {
        val coarse = params.map { it to point(it) }
        val finite = coarse.map { it.second }.filter { it.x.isFinite() && it.y.isFinite() }
        if (finite.size < 2) return coarse.map { it.second }
        val spanX = (finite.maxOf { it.x } - finite.minOf { it.x }).takeIf { it > 0 } ?: 1.0
        val spanY = (finite.maxOf { it.y } - finite.minOf { it.y }).takeIf { it > 0 } ?: 1.0
        fun far(a: PlotPoint, b: PlotPoint) =
            a.x.isFinite() && b.x.isFinite() && a.y.isFinite() && b.y.isFinite() &&
                hypot((a.x - b.x) / spanX, (a.y - b.y) / spanY) > MAX_STEP

        val result = mutableListOf(coarse.first().second)
        fun between(t1: Double, p1: PlotPoint, t2: Double, p2: PlotPoint, depth: Int) {
            if (depth < MAX_REFINE_DEPTH && far(p1, p2)) {
                val tm = (t1 + t2) / 2
                val pm = point(tm)
                between(t1, p1, tm, pm, depth + 1)
                between(tm, pm, t2, p2, depth + 1)
            } else {
                result += p2
            }
        }
        for (i in 1 until coarse.size) {
            between(coarse[i - 1].first, coarse[i - 1].second, coarse[i].first, coarse[i].second, 0)
        }
        return result
    }

    /** Один масштаб по осям: меньший размах расширяется вокруг середины. */
    private fun equalScale(x: AxisRange, y: AxisRange, aspect: Double): Pair<AxisRange, AxisRange>? {
        val perX = (x.max - x.min) / aspect
        val perY = y.max - y.min
        if (max(perX, perY) / min(perX, perY) > EQUAL_SCALE_RATIO) return null
        return if (perX > perY) {
            val mid = (y.min + y.max) / 2
            x to AxisRange(mid - perX / 2, mid + perX / 2, false)
        } else {
            val mid = (x.min + x.max) / 2
            AxisRange(mid - perY * aspect / 2, mid + perY * aspect / 2, false) to y
        }
    }

    /** Параметры считаются по порядку и могут ссылаться на предыдущие: {"w0": "2*pi", "T": "1/w0"}. */
    private fun evaluateParams(raw: Map<String, String>): Map<String, Double> {
        val params = mutableMapOf<String, Double>()
        for ((name, value) in raw) {
            constant(value, params)?.takeIf { it.isFinite() }?.let { params[name.trim()] = it }
        }
        return params
    }

    private fun constant(source: String, params: Map<String, Double>): Double? = try {
        FormulaParser.compile(source, params.keys).eval(params)
    } catch (e: FormulaParser.ParseException) {
        null
    }

    private fun sampleRange(from: Double, to: Double, count: Int, log: Boolean): List<Double> =
        (0 until count).map { i ->
            val f = i.toDouble() / (count - 1)
            if (log) 10.0.pow(log10(from) + f * (log10(to) - log10(from))) else from + f * (to - from)
        }

    /** Куски между точками, где функция не определена (или не помещается на логарифмическую ось). */
    private fun split(samples: List<PlotPoint>, xLog: Boolean, yLog: Boolean): List<List<PlotPoint>> {
        val segments = mutableListOf<List<PlotPoint>>()
        var current = mutableListOf<PlotPoint>()
        for (p in samples) {
            val valid = p.x.isFinite() && p.y.isFinite() && (!xLog || p.x > 0) && (!yLog || p.y > 0)
            if (valid) {
                current += p
            } else if (current.isNotEmpty()) {
                segments += current
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) segments += current
        return segments
    }

    /** Асимптота (как у tg): соседние точки по разные стороны видимой области — линию не соединяем. */
    private fun splitAsymptotes(segment: List<PlotPoint>, y: AxisRange): List<List<PlotPoint>> {
        val result = mutableListOf<List<PlotPoint>>()
        var current = mutableListOf<PlotPoint>()
        for (p in segment) {
            val prev = current.lastOrNull()
            if (prev != null && ((prev.y > y.max && p.y < y.min) || (prev.y < y.min && p.y > y.max))) {
                result += current
                current = mutableListOf()
            }
            current += p
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    /**
     * Диапазон оси. [robust] отбрасывает выбросы у асимптот (иначе tg(x) сплющит график в линию),
     * ноль рядом с данными включается — графики школьных функций привычнее видеть от нуля.
     */
    private fun range(
        values: List<Double>,
        from: Double?,
        to: Double?,
        log: Boolean,
        robust: Boolean,
        pad: Boolean,
    ): AxisRange {
        if (from != null && to != null && to > from && (!log || from > 0)) return AxisRange(from, to, log)
        fun t(v: Double) = if (log) log10(v) else v
        val data = values.filter { it.isFinite() && (!log || it > 0) }.map(::t).sorted()
        var lo = from?.takeIf { !log || it > 0 }?.let(::t) ?: data.firstOrNull() ?: 0.0
        var hi = to?.takeIf { !log || it > 0 }?.let(::t) ?: data.lastOrNull() ?: 1.0
        if (robust && data.size >= 20) {
            val qLo = data[(data.size * 0.02).roundToInt()]
            val qHi = data[(data.size * 0.98).roundToInt().coerceAtMost(data.lastIndex)]
            if (qHi > qLo && hi - lo > 4 * (qHi - qLo)) {
                if (from == null) lo = qLo
                if (to == null) hi = qHi
            }
        }
        var snappedLo = false
        var snappedHi = false
        if (!log) {
            val span = hi - lo
            if (from == null && lo >= 0 && lo <= span * 0.3) lo = 0.0.also { snappedLo = true }
            if (to == null && hi <= 0 && -hi <= span * 0.3) hi = 0.0.also { snappedHi = true }
        }
        if (hi - lo < 1e-12) {
            val d = if (log) 0.5 else max(abs(lo) * 0.1, 1.0)
            lo -= d
            hi += d
        } else if (pad) {
            val p = (hi - lo) * 0.05
            if (from == null && !snappedLo) lo -= p
            if (to == null && !snappedHi) hi += p
        }
        return if (log) AxisRange(10.0.pow(lo), 10.0.pow(hi), true) else AxisRange(lo, hi, false)
    }
}

/** Подписи чисел на графике: компактно, с «−» вместо дефиса. */
object PlotFormat {

    private const val SUPERSCRIPTS = "⁰¹²³⁴⁵⁶⁷⁸⁹"

    fun tick(v: Double, axis: AxisRange): String {
        if (axis.log) {
            val k = log10(v).roundToInt()
            if (abs(v - 10.0.pow(k)) < v * 1e-6 && (k < -3 || k > 4)) return "10" + superscript(k)
            return value(v)
        }
        val decimals = max(0, -floor(log10(axis.step) + 1e-9).toInt())
        val text = String.format(Locale.US, "%.${decimals}f", v)
        return text.replace("-", "−").let { if (it.trimStart('−').all { c -> c == '0' || c == '.' }) "0" else it }
    }

    /** Значение под пальцем: четыре значащие цифры. */
    fun value(v: Double): String {
        if (!v.isFinite()) return "—"
        val a = abs(v)
        val text = if (a != 0.0 && (a >= 1e6 || a < 1e-3)) {
            val exponent = floor(log10(a)).toInt()
            significant(v / 10.0.pow(exponent)) + "·10" + superscript(exponent)
        } else {
            significant(v)
        }
        return text.replace("-", "−")
    }

    private fun significant(v: Double) = BigDecimal(v).round(MathContext(4)).stripTrailingZeros().toPlainString()

    private fun superscript(k: Int): String =
        (if (k < 0) "⁻" else "") + abs(k).toString().map { SUPERSCRIPTS[it - '0'] }.joinToString("")
}
