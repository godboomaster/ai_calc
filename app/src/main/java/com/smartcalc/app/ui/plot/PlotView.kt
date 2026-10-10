package com.smartcalc.app.ui.plot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartcalc.app.data.plot.Plot
import com.smartcalc.app.data.plot.PlotBuilder
import com.smartcalc.app.data.plot.PlotFormat
import com.smartcalc.app.data.plot.PlotModel
import com.smartcalc.app.data.plot.PlotPoint
import com.smartcalc.app.ui.math.MathMarkup
import com.smartcalc.app.ui.math.MathText
import kotlin.math.hypot
import kotlin.math.max

/** Цвета кривых: первая — цвет темы, остальные — средней яркости, читаются и на светлом, и на тёмном фоне. */
private val extraSeriesColors = listOf(Color(0xFFE5484D), Color(0xFF30A46C), Color(0xFFF59E0B), Color(0xFF8E4EC6))

/**
 * График из ответа модели. Точки считает [PlotBuilder]; касание или движение пальца вбок
 * показывает координаты ближайшей точки.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlotView(plot: Plot, container: Color, modifier: Modifier = Modifier) {
    val model = remember(plot) { PlotBuilder.build(plot) }
    Column(modifier = modifier.fillMaxWidth()) {
        if (model == null) {
            Text(
                "Не удалось построить график",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }
        model.title?.let {
            MathText(it, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
        }
        val colors = listOf(MaterialTheme.colorScheme.primary) + extraSeriesColors
        PlotCanvas(
            model,
            colors,
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.3f)
                .clip(RoundedCornerShape(16.dp))
                .background(container),
        )
        val labeled = model.curves.withIndex().filter { !it.value.label.isNullOrBlank() }
        if (labeled.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                labeled.forEach { (i, curve) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(width = 14.dp, height = 3.dp)
                                .background(colors[i % colors.size], RoundedCornerShape(2.dp)),
                        )
                        // Обычный текст, а не MathText: тот занимает всю ширину, и подписи не встали бы в ряд.
                        Text(
                            MathMarkup.plain(curve.label.orEmpty()),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
        if (model.failed > 0) {
            Text(
                "Часть кривых не удалось построить",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Подписи осей измерены заранее: от их ширины зависят поля вокруг области графика. */
private class PlotLabels(
    val xTicks: List<Pair<Double, TextLayoutResult>>,
    val yTicks: List<Pair<Double, TextLayoutResult>>,
    val xTitle: TextLayoutResult?,
    val yTitle: TextLayoutResult?,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Область графика внутри холста и перевод координат данных в пиксели. */
private class PlotFrame(val model: PlotModel, labels: PlotLabels, size: IntSize) {
    val left = labels.left
    val top = labels.top
    val right = size.width - labels.right
    val bottom = size.height - labels.bottom
    private val limit = 10f * max(size.width, size.height)

    fun x(v: Double) = (left + model.x.fraction(v) * (right - left)).toFloat().coerceIn(-limit, limit)
    fun y(v: Double) = (bottom - model.y.fraction(v) * (bottom - top)).toFloat().coerceIn(-limit, limit)
    fun contains(o: Offset) = o.x in left..right && o.y in top..bottom

    /** Ближайшая к пальцу видимая точка: номер кривой и точка. */
    fun nearest(touch: Offset): Pair<Int, PlotPoint>? {
        var best: Pair<Int, PlotPoint>? = null
        var bestDistance = Float.MAX_VALUE
        model.curves.forEachIndexed { i, curve ->
            for (p in curve.segments.flatten()) {
                val o = Offset(x(p.x), y(p.y))
                if (!contains(o)) continue
                val d = hypot(o.x - touch.x, o.y - touch.y)
                if (d < bestDistance) {
                    bestDistance = d
                    best = i to p
                }
            }
        }
        return best
    }
}

@Composable
private fun PlotCanvas(model: PlotModel, colors: List<Color>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = labelColor.copy(alpha = 0.15f)
    val axisColor = labelColor.copy(alpha = 0.6f)
    val calloutColor = MaterialTheme.colorScheme.inverseSurface
    val calloutText = MaterialTheme.colorScheme.inverseOnSurface

    val labels = remember(model, labelColor, density) {
        val style = TextStyle(fontSize = 11.sp, color = labelColor)
        val titleStyle = style.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium)
        val xTicks = model.x.ticks().map { it to measurer.measure(PlotFormat.tick(it, model.x), style) }
        val yTicks = model.y.ticks().map { it to measurer.measure(PlotFormat.tick(it, model.y), style) }
        val xTitle = model.xLabel?.let { measurer.measure(MathMarkup.plain(it), titleStyle) }
        val yTitle = model.yLabel?.let { measurer.measure(MathMarkup.plain(it), titleStyle) }
        with(density) {
            val tickHeight = xTicks.maxOfOrNull { it.second.size.height } ?: 0
            PlotLabels(
                xTicks, yTicks, xTitle, yTitle,
                left = max(24.dp.toPx(), (yTicks.maxOfOrNull { it.second.size.width } ?: 0) + 10.dp.toPx()),
                top = (yTitle?.size?.height?.toFloat()?.plus(6.dp.toPx())) ?: 12.dp.toPx(),
                right = max(14.dp.toPx(), (xTicks.lastOrNull()?.second?.size?.width ?: 0) / 2f + 4.dp.toPx()),
                bottom = tickHeight + 8.dp.toPx() + (xTitle?.size?.height?.plus(2.dp.toPx()) ?: 0f),
            )
        }
    }
    var selected by remember(model) { mutableStateOf<Pair<Int, PlotPoint>?>(null) }

    Canvas(
        modifier
            .pointerInput(model, labels) {
                detectTapGestures { pos -> selected = PlotFrame(model, labels, size).nearest(pos) }
            }
            // Только движение вбок: вертикальное остаётся прокрутке экрана.
            .pointerInput(model, labels) {
                detectHorizontalDragGestures(
                    onDragStart = { pos -> selected = PlotFrame(model, labels, size).nearest(pos) },
                ) { change, _ ->
                    change.consume()
                    selected = PlotFrame(model, labels, size).nearest(change.position)
                }
            },
    ) {
        val frame = PlotFrame(model, labels, IntSize(size.width.toInt(), size.height.toInt()))
        val hair = 1.dp.toPx()

        // Сетка и подписи делений.
        for ((v, text) in labels.xTicks) {
            val x = frame.x(v)
            drawLine(gridColor, Offset(x, frame.top), Offset(x, frame.bottom), hair)
            drawText(text, topLeft = Offset(x - text.size.width / 2f, frame.bottom + 4.dp.toPx()))
        }
        for ((v, text) in labels.yTicks) {
            val y = frame.y(v)
            drawLine(gridColor, Offset(frame.left, y), Offset(frame.right, y), hair)
            drawText(
                text,
                topLeft = Offset(frame.left - text.size.width - 6.dp.toPx(), y - text.size.height / 2f),
            )
        }

        // Оси проходят через ноль, если он виден, иначе — по краю области.
        val zeroX = if (!model.x.log && 0.0 in model.x.min..model.x.max) frame.x(0.0) else frame.left
        val zeroY = if (!model.y.log && 0.0 in model.y.min..model.y.max) frame.y(0.0) else frame.bottom
        drawLine(axisColor, Offset(zeroX, frame.top), Offset(zeroX, frame.bottom), 1.5f * hair)
        drawLine(axisColor, Offset(frame.left, zeroY), Offset(frame.right, zeroY), 1.5f * hair)
        labels.yTitle?.let { drawText(it, topLeft = Offset(frame.left, 0f)) }
        labels.xTitle?.let { drawText(it, topLeft = Offset(size.width - it.size.width - 4.dp.toPx(), size.height - it.size.height)) }

        clipRect(frame.left, frame.top, frame.right, frame.bottom) {
            model.curves.forEachIndexed { i, curve ->
                val color = colors[i % colors.size]
                for (segment in curve.segments) {
                    if (segment.size == 1 || curve.markers) {
                        segment.forEach { p -> drawCircle(color, 3.5.dp.toPx(), Offset(frame.x(p.x), frame.y(p.y))) }
                    }
                    if (segment.size < 2) continue
                    val path = Path()
                    segment.forEachIndexed { k, p ->
                        if (k == 0) path.moveTo(frame.x(p.x), frame.y(p.y)) else path.lineTo(frame.x(p.x), frame.y(p.y))
                    }
                    drawPath(
                        path,
                        color,
                        style = Stroke(
                            width = if (curve.markers) 1.5.dp.toPx() else 2.5.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
            }
        }

        // Точка под пальцем: пунктир до осей и плашка с координатами.
        selected?.let { (i, p) ->
            val o = Offset(frame.x(p.x), frame.y(p.y))
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            drawLine(axisColor, o, Offset(o.x, zeroY), hair, pathEffect = dash)
            drawLine(axisColor, o, Offset(zeroX, o.y), hair, pathEffect = dash)
            drawCircle(colors[i % colors.size], 5.dp.toPx(), o)
            drawCircle(calloutText, 5.dp.toPx(), o, style = Stroke(1.5f * hair))
            val text = measurer.measure(
                "${PlotFormat.value(p.x)}; ${PlotFormat.value(p.y)}",
                TextStyle(fontSize = 12.sp, color = calloutText, fontWeight = FontWeight.Medium),
            )
            val pad = 6.dp.toPx()
            val w = text.size.width + 2 * pad
            val h = text.size.height + pad
            // Плашка над точкой, а у верхнего края — под ней; не вылезает за края.
            val bx = (o.x - w / 2).coerceIn(frame.left, max(frame.left, frame.right - w))
            val by = if (o.y - h - 10.dp.toPx() >= frame.top) o.y - h - 10.dp.toPx() else o.y + 10.dp.toPx()
            drawRoundRect(calloutColor, Offset(bx, by), Size(w, h), CornerRadius(8.dp.toPx()))
            drawText(text, topLeft = Offset(bx + pad, by + pad / 2))
        }
    }
}
