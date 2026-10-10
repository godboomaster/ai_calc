package com.smartcalc.app.ui.math

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.math.max
import kotlin.math.roundToInt

/** Формулы TeX визуально мельче Roboto того же кегля. */
private const val FORMULA_SCALE = 1.1f

private val DECIMAL_POINT = Regex("""(\d)\.(\d)""")

/** Самая мелкая формула отдельной строкой; шире неё — прокрутка вбок. */
private const val MIN_BLOCK_SCALE = 0.7f

/**
 * Нарисованная формула. Для формулы в строке [height] и [top] подобраны так, чтобы при выравнивании
 * по центру текста базовая линия формулы совпала с базовой линией строки.
 */
private class Formula(
    val drawable: JLatexMathDrawable,
    val width: Int,
    val height: Int,
    val left: Float,
    val top: Float,
) {
    companion object {
        fun build(
            latex: String,
            sizePx: Float,
            color: Int,
            maxWidthPx: Int,
            minScale: Float = 0f,
            inline: Boolean = true,
        ): Formula? = try {
            // JLaTeXMath ставит после точки пробел, как после знака препинания: «0. 1». В фигурных
            // скобках точка — обычный символ, и десятичная дробь пишется слитно.
            val source = DECIMAL_POINT.replace(latex, "$1{.}$2")
            fun draw(size: Float) = JLatexMathDrawable.builder(source).textSize(size).color(color).build()
            fun tightWidth(d: JLatexMathDrawable) = d.intrinsicWidth - d.icon().insets.left - d.icon().insets.right
            var d = draw(sizePx)
            if (tightWidth(d) > maxWidthPx) {
                d = draw(sizePx * max(minScale, maxWidthPx.toFloat() / tightWidth(d)))
            }
            // JLaTeXMath обводит формулу полями в 0.18 em: по бокам они дают лишние пробелы в тексте,
            // сверху и снизу оставляем половину, чтобы строки с формулами не слипались.
            val insets = d.icon().insets
            val trimTop = insets.top / 2f
            val trimBottom = insets.bottom / 2f
            val depth = d.icon().iconDepth - trimBottom
            val above = d.intrinsicHeight - d.icon().iconDepth - trimTop
            if (inline) {
                // Центр строки (PlaceholderVerticalAlign.TextCenter) лежит выше базовой линии на textCenter.
                val metrics = Paint().apply { textSize = sizePx / FORMULA_SCALE }.fontMetrics
                val textCenter = -(metrics.ascent + metrics.descent) / 2
                val height = 2 * max(above - textCenter, textCenter + depth)
                val top = height / 2 + textCenter - above
                Formula(d, tightWidth(d), height.roundToInt(), -insets.left.toFloat(), top - trimTop)
            } else {
                Formula(d, tightWidth(d), (above + depth).roundToInt(), -insets.left.toFloat(), -trimTop)
            }
        } catch (e: Exception) {
            null // неизвестная команда или ошибка в LaTeX — покажем формулу текстом
        }

        /** Первый разбор грузит шрифты и таблицы — делаем его заранее, не на главном потоке. */
        fun warmUp() {
            build("\\frac{x^2}{\\sqrt{y}}\\text{ м}", 16f, 0, Int.MAX_VALUE)
        }
    }
}

object MathRenderer {
    fun warmUp() = Formula.warmUp()
}

private sealed interface Paragraph {
    data class Line(val parts: List<MathPart>) : Paragraph
    data class Block(val latex: String) : Paragraph
    data class Table(val table: MathTable) : Paragraph
}

/** Одна строка без абзацев: для ячеек таблицы и подписей с ограничением строк. */
internal fun singleLine(text: String): List<MathPart> =
    (paragraphs(MathMarkup.parse(text), flatten = true).firstOrNull() as? Paragraph.Line)?.parts.orEmpty()

private fun paragraphs(parts: List<MathPart>, flatten: Boolean): List<Paragraph> {
    val result = mutableListOf<Paragraph>()
    var line = mutableListOf<MathPart>()
    fun flush() {
        // Переводы строк и пробелы у края абзаца остались от разметки «текст\n$$…$$ текст».
        val trimmed = line.mapIndexed { i, p ->
            if (p !is MathPart.Text) p
            else MathPart.Text(
                p.text.let { if (i == 0) it.trimStart() else it }
                    .let { if (i == line.lastIndex) it.trimEnd() else it },
            )
        }.filterNot { it is MathPart.Text && it.text.isEmpty() }
        if (trimmed.isNotEmpty()) result += Paragraph.Line(trimmed)
        line = mutableListOf()
    }
    for (p in parts) {
        when {
            p is MathPart.Block && flatten -> line += MathPart.Inline(p.latex)
            p is MathPart.Block -> {
                flush()
                result += Paragraph.Block(p.latex)
            }

            else -> line += p
        }
    }
    flush()
    return result
}

/**
 * Текст с формулами LaTeX (`$…$`, `$$…$$`): формулы рисуются как в учебнике — с дробями,
 * корнями и степенями. Понимает и немного markdown: **жирный**, заголовки «#», списки «-»,
 * таблицы «| a | b |». При ограниченном [maxLines] формулы отдельной строкой встают в строку
 * текста, а таблицы остаются текстом.
 */
@Composable
fun MathText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
) {
    val style = LocalTextStyle.current
    val size = if (fontSize.isSpecified) fontSize else style.fontSize.takeIf { it.isSpecified } ?: 16.sp
    val textColor = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val flatten = maxLines != Int.MAX_VALUE
    val paragraphs = remember(text, flatten) {
        if (flatten) {
            paragraphs(MathMarkup.parse(text), flatten = true)
        } else {
            MathMarkup.blocks(text).flatMap { block ->
                when (block) {
                    is TextBlock.Prose -> paragraphs(MathMarkup.parse(block.text), flatten = false)
                    is TextBlock.Table -> listOf(Paragraph.Table(block.table))
                }
            }
        }
    }
    val horizontal = when (textAlign) {
        TextAlign.End, TextAlign.Right -> Alignment.End
        TextAlign.Center -> Alignment.CenterHorizontally
        else -> Alignment.Start
    }

    BoxWithConstraints(modifier) {
        val maxWidthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = horizontal,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            paragraphs.forEach { p ->
                when (p) {
                    is Paragraph.Line -> MathLine(p.parts, size, textColor, fontWeight, textAlign, maxLines, maxWidthPx)
                    is Paragraph.Block -> MathBlock(p.latex, size, textColor, horizontal, maxWidthPx)
                    is Paragraph.Table -> MathTableView(p.table, size, textColor, maxWidthPx)
                }
            }
        }
    }
}

@Composable
internal fun MathLine(
    parts: List<MathPart>,
    fontSize: TextUnit,
    color: Color,
    fontWeight: FontWeight?,
    textAlign: TextAlign,
    maxLines: Int,
    maxWidthPx: Int,
) {
    val density = LocalDensity.current
    val sizePx = with(density) { fontSize.toPx() } * FORMULA_SCALE
    val argb = color.toArgb()
    val formulas = remember(parts, sizePx, argb, maxWidthPx) {
        parts.filterIsInstance<MathPart.Inline>().map { Formula.build(it.latex, sizePx, argb, maxWidthPx) }
    }
    val annotated = buildAnnotatedString {
        var k = 0
        for (part in parts) {
            when (part) {
                is MathPart.Text -> appendMarkdown(part.text)
                is MathPart.Inline -> {
                    if (formulas[k] != null) appendInlineContent("f$k", part.latex)
                    else append(MathMarkup.toPlain(part.latex))
                    k++
                }

                is MathPart.Block -> Unit
            }
        }
    }
    val inlineContent = formulas.withIndex().mapNotNull { (k, f) ->
        f ?: return@mapNotNull null
        val placeholder = with(density) {
            Placeholder(f.width.toSp(), f.height.toSp(), PlaceholderVerticalAlign.TextCenter)
        }
        "f$k" to InlineTextContent(placeholder) { FormulaCanvas(f, Modifier.fillMaxSize()) }
    }.toMap()

    Text(
        annotated,
        fontSize = fontSize,
        color = color,
        fontWeight = fontWeight,
        textAlign = textAlign,
        // Строка с формулой подстраивает высоту под неё; фиксированный интервал темы её бы не пустил.
        style = LocalTextStyle.current.copy(
            lineHeight = if (formulas.isEmpty()) fontSize * 1.35f else TextUnit.Unspecified,
        ),
        maxLines = maxLines,
        // Compose не рисует формулы в строке с «…» (сравнивает смещение многоточия внутри строки
        // с абсолютным смещением формулы), поэтому текст с формулами обрезается без многоточия.
        overflow = if (formulas.isEmpty()) TextOverflow.Ellipsis else TextOverflow.Clip,
        inlineContent = inlineContent,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MathBlock(latex: String, fontSize: TextUnit, color: Color, alignment: Alignment.Horizontal, maxWidthPx: Int) {
    val density = LocalDensity.current
    val sizePx = with(density) { fontSize.toPx() } * FORMULA_SCALE * 1.1f
    val argb = color.toArgb()
    val formula = remember(latex, sizePx, argb, maxWidthPx) {
        Formula.build(latex, sizePx, argb, maxWidthPx, MIN_BLOCK_SCALE, inline = false)
    }
    if (formula == null) {
        Text(MathMarkup.toPlain(latex), fontSize = fontSize, color = color, modifier = Modifier.fillMaxWidth())
        return
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = when (alignment) {
            // Формула отдельной строкой — по центру, как в учебнике; на дисплее калькулятора — справа.
            Alignment.End -> Alignment.CenterEnd
            else -> Alignment.Center
        },
    ) {
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            with(density) {
                FormulaCanvas(formula, Modifier.size(formula.width.toDp(), formula.height.toDp()))
            }
        }
    }
}

@Composable
private fun FormulaCanvas(formula: Formula, modifier: Modifier) {
    Canvas(modifier) {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(formula.left, formula.top)
            formula.drawable.draw(native)
            native.restore()
        }
    }
}

private val BOLD = Regex("""\*\*(.+?)\*\*""")

/** Немного markdown, который модели пишут вопреки просьбе: **жирный**, «# Заголовок», «- пункт». */
private fun AnnotatedString.Builder.appendMarkdown(text: String) {
    val lines = text.split('\n')
    lines.forEachIndexed { i, rawLine ->
        var line = rawLine
        var heading = false
        if (line.trimStart().startsWith("#")) {
            line = line.trimStart().trimStart('#').trimStart()
            heading = true
        } else if (line.startsWith("- ") || line.startsWith("* ")) {
            line = "• " + line.drop(2)
        }
        if (heading) pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
        var last = 0
        BOLD.findAll(line).forEach { m ->
            append(line.substring(last, m.range.first))
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.groupValues[1]) }
            last = m.range.last + 1
        }
        append(line.substring(last))
        if (heading) pop()
        if (i < lines.lastIndex) append('\n')
    }
}
