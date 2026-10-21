package com.smartcalc.app.ui.search

import androidx.activity.compose.BackHandler
import com.smartcalc.app.ui.components.FormActions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smartcalc.app.SolutionViewModel
import com.smartcalc.app.data.search.FoundSolution
import com.smartcalc.app.data.search.SearchEntry
import com.smartcalc.app.data.library.source.SourceSegmentRef
import com.smartcalc.app.ui.components.QuickHideButton
import com.smartcalc.app.ui.library.sourceLabel
import com.smartcalc.app.ui.math.MathText
import com.smartcalc.app.ui.solution.StepImages
import com.smartcalc.app.ui.solution.LibraryExamples
import com.smartcalc.app.ui.template.label
import com.smartcalc.app.ui.plot.PlotView
import com.smartcalc.app.ui.scheme.SchemeView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Browsing does not replace the calculator session or create a history entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskPreviewScreen(
    vm: SolutionViewModel,
    entry: SearchEntry,
    onBack: () -> Unit,
    onUse: () -> Unit,
    onSource: (SourceSegmentRef) -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val solution = remember(entry) { FoundSolution.of(entry) }
    BackHandler { if (!busy) onBack() }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Просмотр задачи") }, navigationIcon = {
            IconButton(onClick = onBack, enabled = !busy) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад к списку")
            }
        }, actions = {
            onEdit?.let { TextButton(onClick = it, enabled = !busy) { Text("Изменить") } }
            QuickHideButton()
        })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(listOfNotNull(entry.source.label, entry.type).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge)
            entry.library?.let { metadata ->
                Text("${metadata.verification.label} · версия ${metadata.version}", style = MaterialTheme.typography.bodySmall)
                if (!metadata.enabled) Text("Исключено из поиска", style = MaterialTheme.typography.bodySmall)
                metadata.sourceRefs.forEach { ref ->
                    TextButton(onClick = { onSource(ref) }) {
                        Text(sourceLabel(metadata.origin.name ?: "Оригинал", ref))
                    }
                }
            }
            Text("Условие", style = MaterialTheme.typography.titleMedium)
            MathText(solution.problem)
            StepImages(entry.conditionImages.ifEmpty { listOfNotNull(solution.imagePath) })
            if (entry.history == null) {
                Text("Решение", style = MaterialTheme.typography.titleMedium)
                if (entry.solution.isNotBlank()) MathText(entry.solution)
                StepImages(entry.solutionImages)
                if (entry.solution.isBlank() && entry.solutionImages.isEmpty()) Text("Решение пока не добавлено")
            } else {
                solution.steps.forEach { step ->
                    Text(step.title, style = MaterialTheme.typography.titleMedium)
                    MathText(step.content)
                    StepImages(step.images)
                    step.plot?.let { PlotView(it, container = MaterialTheme.colorScheme.surfaceVariant) }
                    step.svg?.let { SchemeView(it) }
                }
            }
            Text("Ответ", style = MaterialTheme.typography.titleMedium)
            MathText(solution.answer)
            LibraryExamples(solution.agentReport)
            solution.followUps.forEachIndexed { index, followUp ->
                HorizontalDivider()
                Text("Дополнение ${index + 1}", style = MaterialTheme.typography.titleMedium)
                MathText(followUp.question)
                followUp.steps.forEach { step ->
                    Text(step.title, style = MaterialTheme.typography.titleSmall)
                    MathText(step.content)
                    StepImages(step.images)
                    step.plot?.let { PlotView(it, container = MaterialTheme.colorScheme.surfaceVariant) }
                    step.svg?.let { SchemeView(it) }
                }
                MathText(followUp.answer)
                LibraryExamples(followUp.agentReport)
            }
        }
        FormActions {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Для просмотра шагов на клавишах и дополнения с ИИ.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try { vm.openFound(entry); onUse() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "Не удалось открыть на калькуляторе. Попробуйте ещё раз." }
                        finally { busy = false }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Открываем…" else "На калькулятор")
                }
        }
    }
}
