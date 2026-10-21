package com.smartcalc.app.ui.solution

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.ui.calculator.CalcKey
import com.smartcalc.app.ui.components.QuickHideButton
import com.smartcalc.app.ui.math.MathText
import com.smartcalc.app.ui.plot.PlotView
import com.smartcalc.app.ui.scheme.SchemeView

/** Шаги текущего ответа и завершающая страница итога; вход — удержанием цифры. */
@Composable
fun StepScreen(
    solution: Solution,
    index: Int,
    onIndexChange: (Int) -> Unit,
    onBack: () -> Unit,
    onHideToCalculator: () -> Unit,
    initialScroll: Int,
    onScrollChange: (Int, Int) -> Unit,
) {
    BackHandler(onBack = onBack)
    val steps = solution.latestSteps
    val step = steps.getOrNull(index)
    val prefix = if (solution.followUps.isEmpty()) "" else "Дополнение · "
    var choosingStep by rememberSaveable(solution.id, solution.followUps.size) { mutableStateOf(false) }
    val stepState = rememberSaveableStateHolder()

    // Системные отступы уже учитываются общим Scaffold в AppRoot.
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
            ReadingPageMarker(if (step != null) index + 1 else null)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "Выбрать шаг") { choosingStep = !choosingStep }
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    if (step == null) "${prefix}Ответ"
                    else if (solution.format == null) "${prefix}Шаг ${index + 1} из ${steps.size}"
                    else "$prefix${index + 1} из ${steps.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            IconButton(onClick = onHideToCalculator) {
                Icon(Icons.Filled.Calculate, contentDescription = "К калькулятору")
            }
            QuickHideButton()
        }
        if (step != null) MathText(
            step.title,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        if (choosingStep) {
            LazyColumn(
                state = rememberLazyListState(initialFirstVisibleItemIndex = index),
                modifier = Modifier.weight(1f).padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(steps.size + 1) { itemIndex ->
                    val item = steps.getOrNull(itemIndex)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (itemIndex == index) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent,
                            )
                            .clickable {
                                onIndexChange(itemIndex)
                                choosingStep = false
                            }
                            .heightIn(min = 56.dp).padding(12.dp),
                    ) {
                        ReadingPageMarker(if (item != null) itemIndex + 1 else null)
                        MathText(
                            item?.title ?: "Ответ",
                            fontSize = 16.sp,
                            maxLines = 3,
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                        )
                    }
                }
            }
        } else stepState.SaveableStateProvider(index) {
            val scroll = rememberScrollState(initial = initialScroll)
            LaunchedEffect(scroll, index) {
                snapshotFlow { scroll.value }.collect { onScrollChange(index, it) }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 12.dp)
                    .verticalScroll(scroll),
            ) {
                if (step == null) {
                    MathText(
                        solution.latestAnswer,
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(18.dp),
                    )
                    LibraryExamples(solution.followUps.lastOrNull()?.agentReport ?: solution.agentReport.takeIf { solution.followUps.isEmpty() })
                } else {
                    if (step.content.isNotBlank()) MathText(step.content, fontSize = 17.sp)
                    StepImages(step.images)
                    step.plot?.let {
                        PlotView(it, container = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(top = 12.dp))
                    }
                    step.svg?.let { SchemeView(it, modifier = Modifier.padding(top = 12.dp)) }
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 16.dp),
        ) {
            CalcKey(
                MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.height(56.dp),
                enabled = index > 0,
                onClick = {
                    choosingStep = false
                    onIndexChange(index - 1)
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = if (step == null) "К последнему шагу" else "Предыдущий шаг")
            }
            CalcKey(
                MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.height(56.dp),
                enabled = index < steps.size,
                onClick = {
                    choosingStep = false
                    onIndexChange(index + 1)
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (index == steps.lastIndex) Text("Ответ", fontSize = 17.sp, modifier = Modifier.padding(end = 8.dp))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (index == steps.lastIndex) "Перейти к ответу" else "Следующий шаг")
                }
            }
        }
    }
}

@Composable
private fun ReadingPageMarker(number: Int?) {
    if (number != null) StepNumber(number)
    else Icon(Icons.Filled.Check, contentDescription = null,
        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
}
