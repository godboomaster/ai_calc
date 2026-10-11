package com.smartcalc.app.ui.solution

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.ui.calculator.CalcKey
import com.smartcalc.app.ui.math.MathText
import com.smartcalc.app.ui.plot.PlotView
import com.smartcalc.app.ui.scheme.SchemeView

/**
 * Один шаг решения поверх калькулятора — открывается удержанием цифры с номером шага. Шаги — того
 * ответа, что на дисплее: после дополнения это шаги ответа на него.
 */
@Composable
fun StepDialog(
    solution: Solution,
    index: Int,
    onIndexChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val steps = solution.latestSteps
    val step = steps[index]
    val prefix = if (solution.followUps.isEmpty()) "" else "Дополнение · "
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepNumber(index + 1)
                Column(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .weight(1f),
                ) {
                    Text(
                        // В формате пользователя шаги — его разделы («Дано», «Решение»), без слова «шаг».
                        if (solution.format == null) "${prefix}Шаг ${index + 1} из ${steps.size}"
                        else "$prefix${index + 1} из ${steps.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MathText(step.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.Top)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Закрыть",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (step.content.isNotBlank()) MathText(step.content, fontSize = 17.sp)
                step.plot?.let {
                    PlotView(it, container = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(top = 12.dp))
                }
                step.svg?.let { SchemeView(it, modifier = Modifier.padding(top = 12.dp)) }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 16.dp),
            ) {
                CalcKey(
                    MaterialTheme.colorScheme.surfaceVariant,
                    enabled = index > 0,
                    onClick = { onIndexChange(index - 1) },
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Предыдущий шаг")
                }
                CalcKey(
                    MaterialTheme.colorScheme.surfaceVariant,
                    enabled = index < steps.lastIndex,
                    onClick = { onIndexChange(index + 1) },
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Следующий шаг")
                }
            }
        }
    }
}
