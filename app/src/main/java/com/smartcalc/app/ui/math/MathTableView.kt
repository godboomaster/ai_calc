package com.smartcalc.app.ui.math

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.max

/** Самый широкий столбец — не больше этой доли экрана: длинный текст в ячейке переносится. */
private const val MAX_COLUMN_SHARE = 0.7f

/** Где прошли границы столбцов и строк: считается при раскладке, нужно при рисовании сетки. */
private class GridLines {
    var xs = IntArray(0)
    var ys = IntArray(0)
    var headerHeight = 0
}

/**
 * Таблица markdown с формулами в ячейках. Ширина столбца — по самой широкой ячейке; узкая
 * таблица растягивается на всю ширину, широкая прокручивается вбок.
 */
@Composable
internal fun MathTableView(table: MathTable, fontSize: TextUnit, color: Color, availablePx: Int) {
    val density = LocalDensity.current
    val columns = table.columns
    val rows = listOfNotNull(table.header) + table.rows
    val hasHeader = table.header != null
    val bounded = availablePx != Int.MAX_VALUE
    val capPx = if (bounded) (availablePx * MAX_COLUMN_SHARE).toInt() else with(density) { 280.dp.roundToPx() }
    val minPx = with(density) { 40.dp.roundToPx() }
    val lineColor = color.copy(alpha = 0.25f)
    val headerColor = color.copy(alpha = 0.07f)
    val shape = RoundedCornerShape(10.dp)
    val grid = remember { GridLines() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        Layout(
            content = {
                rows.forEachIndexed { r, row ->
                    for (c in 0 until columns) {
                        val align = when (table.align.getOrNull(c)) {
                            CellAlign.Center -> TextAlign.Center
                            CellAlign.End -> TextAlign.End
                            else -> TextAlign.Start
                        }
                        Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            MathLine(
                                parts = singleLine(row.getOrElse(c) { "" }.replace("<br>", "\n")),
                                fontSize = fontSize,
                                color = color,
                                fontWeight = if (hasHeader && r == 0) FontWeight.SemiBold else null,
                                textAlign = align,
                                maxLines = Int.MAX_VALUE,
                                maxWidthPx = capPx,
                            )
                        }
                    }
                }
            },
            modifier = Modifier
                .wrapContentWidth()
                .clip(shape)
                .drawBehind {
                    val w = size.width
                    val h = size.height
                    if (hasHeader) drawRect(headerColor, Offset.Zero, Size(w, grid.headerHeight.toFloat()))
                    val stroke = 1.dp.toPx()
                    grid.xs.drop(1).dropLast(1).forEach { x ->
                        drawLine(lineColor, Offset(x.toFloat(), 0f), Offset(x.toFloat(), h), stroke)
                    }
                    grid.ys.drop(1).dropLast(1).forEach { y ->
                        drawLine(lineColor, Offset(0f, y.toFloat()), Offset(w, y.toFloat()), stroke)
                    }
                }
                .border(1.dp, lineColor, shape),
        ) { measurables, _ ->
            val widths = IntArray(columns) { c ->
                rows.indices.maxOf { r -> measurables[r * columns + c].maxIntrinsicWidth(Constraints.Infinity) }
                    .coerceIn(minPx, max(minPx, capPx))
            }
            // Узкая таблица растягивается на всю ширину — запас делится между столбцами поровну.
            val spare = if (bounded) availablePx - widths.sum() else 0
            if (spare > 0) {
                for (c in 0 until columns) widths[c] += spare / columns + if (c < spare % columns) 1 else 0
            }
            val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixedWidth(widths[i % columns])) }
            val heights = IntArray(rows.size) { r -> (0 until columns).maxOf { c -> placeables[r * columns + c].height } }
            grid.xs = widths.runningFold(0) { acc, w -> acc + w }.toIntArray()
            grid.ys = heights.runningFold(0) { acc, h -> acc + h }.toIntArray()
            grid.headerHeight = if (hasHeader) heights[0] else 0
            layout(grid.xs.last(), grid.ys.last()) {
                placeables.forEachIndexed { i, p ->
                    val r = i / columns
                    val c = i % columns
                    // Ячейки ниже соседних по строке выравниваются по центру строки.
                    p.place(grid.xs[c], grid.ys[r] + (heights[r] - p.height) / 2)
                }
            }
        }
    }
}
