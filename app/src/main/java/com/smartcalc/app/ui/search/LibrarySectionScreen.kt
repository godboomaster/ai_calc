package com.smartcalc.app.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smartcalc.app.SolutionViewModel
import com.smartcalc.app.data.library.source.SourceSegmentRef
import com.smartcalc.app.data.search.FoundSource
import com.smartcalc.app.data.search.SearchEntry
import com.smartcalc.app.data.template.ExampleFingerprint
import com.smartcalc.app.data.template.TaskTemplate
import com.smartcalc.app.ui.components.QuickHideButton
import com.smartcalc.app.ui.math.MathText
import com.smartcalc.app.ui.template.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibrarySectionScreen(
    vm: SolutionViewModel,
    section: TaskTemplate,
    onBack: () -> Unit,
    onEdit: (Int?) -> Unit,
    onUse: () -> Unit,
    onSource: (SourceSegmentRef) -> Unit,
) {
    var selectedIndex by rememberSaveable(section.id) { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()
    val example = selectedIndex?.let { section.examples.getOrNull(it) }
    if (example != null) {
        val entry = remember(section, example) {
            SearchEntry(ExampleFingerprint.key(example), FoundSource.BASE, section.name,
                example.condition, example.solution, example.conditionImages, example.solutionImages,
                library = example.library, answer = example.answer)
        }
        TaskPreviewScreen(vm, entry, onBack = { selectedIndex = null }, onUse = onUse,
            onSource = onSource, onEdit = { onEdit(selectedIndex) })
        return
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(section.name, maxLines = 2) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад к разделам") }
        }, actions = {
            TextButton(onClick = { onEdit(null) }) { Text("Изменить") }
            QuickHideButton()
        })
        LazyColumn(state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (section.examples.isEmpty()) item { Text("В разделе пока нет задач. Добавьте их через «Изменить».") }
            itemsIndexed(section.examples) { index, task ->
                Card(onClick = { selectedIndex = index }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Задача ${index + 1}", style = MaterialTheme.typography.labelLarge)
                        MathText(task.condition.ifBlank { "Условие на фото" }, maxLines = 3)
                        if (task.answer.isNotBlank()) MathText("Ответ: ${task.answer}", maxLines = 2)
                        task.library?.let { metadata ->
                            Text(metadata.verification.label + if (!metadata.enabled) " · вне поиска" else "",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
