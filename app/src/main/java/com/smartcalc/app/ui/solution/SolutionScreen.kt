package com.smartcalc.app.ui.solution

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.smartcalc.app.SolutionViewModel
import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.history.SolutionStep
import com.smartcalc.app.ui.calculator.CalcKey
import com.smartcalc.app.ui.math.MathMarkup
import com.smartcalc.app.ui.math.MathText
import com.smartcalc.app.ui.plot.PlotView
import com.smartcalc.app.ui.scheme.SchemeView
import java.io.File

/**
 * Всё решение целиком — открывается удержанием «=» на калькуляторе. Условие, шаги и ответ —
 * отдельные блоки, все развёрнуты.
 */
@Composable
fun SolutionScreen(
    vm: SolutionViewModel,
    onBack: () -> Unit,
    onOpenCalculator: () -> Unit,
    onHideToCalculator: () -> Unit,
) {
    val solution by vm.current.collectAsState()
    var copied by remember(solution?.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Решение",
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val s = solution
            if (s != null) {
                IconButton(onClick = onHideToCalculator) {
                    Icon(Icons.Filled.Calculate, contentDescription = "К калькулятору")
                }
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(copyText(s)))
                    copied = true
                }) {
                    Icon(
                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = if (copied) "Скопировано" else "Скопировать решение",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val s = solution
        if (s == null) {
            Text(
                "Нет решения",
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }
        // В формате пользователя шаги — это его разделы («Дано», «Решение»…): без номеров.
        val numbered = s.format == null
        // После дополнения экран открывается на нём: исходное решение — выше, прокруткой.
        val latestStart = remember(s.id, s.followUps.size) {
            if (s.followUps.isEmpty()) 0
            else 1 + (if (s.template != null || s.format != null) 1 else 0) + s.steps.size + 1 +
                s.followUps.dropLast(1).sumOf { it.steps.size + 2 }
        }
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = latestStart)

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SolutionBlock(
                    label = "Условие",
                    action = {
                        IconButton(onClick = {
                            vm.editRequest = SolutionViewModel.EditRequest(MathMarkup.plain(s.problem), followUp = false)
                            onOpenCalculator()
                        }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Изменить условие и решить заново",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                ) {
                    if (s.imagePath != null) {
                        AsyncImage(
                            model = File(s.imagePath),
                            contentDescription = "Фото задачи",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .height(120.dp)
                                .widthIn(max = 280.dp)
                                .clip(RoundedCornerShape(16.dp)),
                        )
                    }
                    MathText(s.problem, fontSize = 17.sp)
                }
            }
            if (s.template != null || s.format != null) {
                item {
                    Text(
                        listOfNotNull(
                            s.template?.let { "По шаблону «$it»" },
                            s.format?.let { "В вашем формате · $it" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            stepsAndAnswer(s.steps, s.answer, numbered)
            s.followUps.forEach { f ->
                item {
                    SolutionBlock(label = "Дополнение" + (f.template?.let { " · по шаблону «$it»" } ?: "")) {
                        MathText(f.question, fontSize = 17.sp)
                    }
                }
                stepsAndAnswer(f.steps, f.answer, numbered)
            }
            item {
                // Вопрос к этому решению — в строке калькулятора, ответ — на его дисплее.
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    CalcKey(
                        MaterialTheme.colorScheme.primary,
                        onClick = {
                            vm.editRequest = SolutionViewModel.EditRequest("", followUp = true)
                            onOpenCalculator()
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.AddComment,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                            Text(
                                "Дополнить",
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Шаги одного ответа (исходного или на дополнение) и сам ответ отдельным блоком. */
private fun LazyListScope.stepsAndAnswer(steps: List<SolutionStep>, answer: String, numbered: Boolean) {
    itemsIndexed(steps) { index, step ->
        SolutionBlock(number = if (numbered) index + 1 else null, title = step.title) {
            if (step.content.isNotBlank()) MathText(step.content, fontSize = 16.sp)
            step.plot?.let {
                PlotView(it, container = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(top = 12.dp))
            }
            step.svg?.let { SchemeView(it, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
    item {
        SolutionBlock(
            label = "Ответ",
            container = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            MathText(answer, fontSize = 24.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** Номер шага в кружке — та же цифра, удержанием которой шаг открывается на калькуляторе. */
@Composable
internal fun StepNumber(number: Int) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun SolutionBlock(
    label: String? = null,
    number: Int? = null,
    title: String? = null,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(container)
            .padding(start = 18.dp, end = if (action != null) 6.dp else 18.dp, top = 14.dp, bottom = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            if (number != null) {
                StepNumber(number)
                Box(Modifier.size(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                if (label != null) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = contentColor)
                }
                if (title != null) {
                    MathText(
                        title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            action?.invoke()
        }
        Column(modifier = Modifier.padding(end = if (action != null) 12.dp else 0.dp), content = content)
    }
}

private fun copyText(solution: Solution): String {
    fun plain(s: String) = MathMarkup.plain(s)
    val followUps = buildString {
        solution.followUps.forEach { f ->
            appendLine()
            appendLine("Дополнение: ${plain(f.question)}")
            appendLine("Ответ: ${plain(f.answer)}")
            f.steps.forEachIndexed { i, s ->
                appendLine()
                appendLine("${i + 1}. ${plain(s.title)}")
                appendLine(plain(s.content))
            }
        }
    }
    return mainText(solution, ::plain) + followUps.trimEnd()
}

private fun mainText(solution: Solution, plain: (String) -> String): String {
    return if (solution.format != null) {
        buildString {
            solution.steps.forEach { s ->
                appendLine(plain(s.title))
                appendLine(plain(s.content))
                appendLine()
            }
            if (solution.steps.none { it.title.contains("Ответ", ignoreCase = true) }) {
                appendLine("Ответ: ${plain(solution.answer)}")
            }
        }.trimEnd()
    } else {
        buildString {
            appendLine("Задача: ${plain(solution.problem)}")
            appendLine("Ответ: ${plain(solution.answer)}")
            solution.steps.forEachIndexed { i, s ->
                appendLine()
                appendLine("${i + 1}. ${plain(s.title)}")
                appendLine(plain(s.content))
            }
        }
    }
}
