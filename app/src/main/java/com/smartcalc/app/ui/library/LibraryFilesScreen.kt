@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.smartcalc.app.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.smartcalc.app.ui.components.FormActions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.smartcalc.app.data.library.source.*
import com.smartcalc.app.data.library.importer.*
import com.smartcalc.app.ui.components.QuickHideButton

internal val ImportStatus.label: String get() = when (this) {
    ImportStatus.COPIED -> "Оригинал сохранён"
    ImportStatus.EXTRACTING -> "Подготовка фрагментов"
    ImportStatus.RECOGNIZING -> "Распознавание задач"
    ImportStatus.REVIEW_REQUIRED -> "Нужна проверка разбора"
    ImportStatus.PUBLISHING -> "Добавление задач"
    ImportStatus.READY -> "Задачи добавлены"
    ImportStatus.PAUSED -> "На паузе"
    ImportStatus.FAILED -> "Обработка не завершена"
    ImportStatus.INTERRUPTED -> "Прервано"
    ImportStatus.CANCELLED -> "Отменено"
}
internal val ImportJob.busy: Boolean get() = waitingForSolution || status in setOf(ImportStatus.EXTRACTING, ImportStatus.RECOGNIZING, ImportStatus.PUBLISHING)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LibraryHeader(title: String, onBack: () -> Unit) {
    TopAppBar(title = { Text(title, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
    }, actions = { QuickHideButton() })
}
/** The screen owns the outer inset; this block also works inside already padded content. */
@Composable internal fun LibraryError(message: String?, modifier: Modifier = Modifier, clear: (() -> Unit)? = null) {
    if (message != null) {
        Column(modifier) {
            Text(message, color = MaterialTheme.colorScheme.error)
            if (clear != null) TextButton(onClick = clear) { Text("Понятно") }
        }
    }
}
@Composable fun LibraryFilesScreen(vm: LibraryImportViewModel, onBack: () -> Unit,
    onSelection: () -> Unit, onReview: (String) -> Unit, onSource: (String, Int) -> Unit,
    onSearch: () -> Unit, onTasks: () -> Unit, onAddMaterial: () -> Unit) {
    val catalog by vm.sources.collectAsState(); val jobs by vm.jobs.collectAsState()
    val error by vm.error.collectAsState(); val copying by vm.copying.collectAsState()
    val selected by vm.pendingSourceIds.collectAsState()
    var deleting by remember { mutableStateOf<LibrarySource?>(null) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    val pendingJobs = jobs.jobs.filter { it.status !in setOf(ImportStatus.READY, ImportStatus.CANCELLED) }.reversed()
    val completedJobs = jobs.jobs.filter { it.status in setOf(ImportStatus.READY, ImportStatus.CANCELLED) }.reversed()
    Column(Modifier.fillMaxSize()) {
        LibraryHeader("Библиотека", onBack)
        LibraryTabs(2, onSearch = onSearch, onTasks = onTasks, onFiles = {})
        Button(onClick = onAddMaterial, enabled = !copying,
            modifier = Modifier.fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)
                .heightIn(min = 48.dp)) {
            Text("Добавить материал")
        }
        if (copying) LinearProgressIndicator(Modifier.fillMaxWidth().padding(12.dp))
        LibraryError(catalog.error ?: jobs.error ?: error, modifier = Modifier.padding(16.dp), clear = vm::clearError)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (pendingJobs.isNotEmpty()) {
                item { Text("Требуют внимания", style = MaterialTheme.typography.titleMedium) }
                items(pendingJobs, key = { "job:${it.id}" }) { job -> ImportJobCard(job, vm, onReview) }
            }
            item {
                com.smartcalc.app.ui.components.ExplanationCard("Исходные файлы", "Оригиналы документов и фотографий. Готовые примеры находятся во вкладке «Задачи».")
            }
            items(catalog.sources.reversed(), key = { it.id }) { source ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = source.id in selected, enabled = source.status != SourceStatus.DELETED,
                                onCheckedChange = { chosen -> vm.selectSources(if (chosen) selected + source.id else selected - source.id) })
                            Text(source.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        }
                        Text("${source.format.name} · ${source.segmentCount?.let { "$it фрагментов" } ?: "ещё не разобран"}", style = MaterialTheme.typography.bodySmall)
                        val processed = jobs.jobs.flatMap { job -> job.segments.filter { it.ref.sourceId == source.id && it.ref.segmentId in job.processedSegmentIds }.map { it.ref.ordinal } }
                        if (processed.isNotEmpty()) Text("В сохранённых разборах обработано: ${formatOrdinalRanges(processed)}", style = MaterialTheme.typography.bodySmall)
                        if (source.status == SourceStatus.DELETED) Text("Оригинал удалён; связанные задачи исключены из нового поиска")
                        else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Использовать в поиске", Modifier.weight(1f))
                                Switch(checked = source.status == SourceStatus.ACTIVE, onCheckedChange = { vm.enableSource(source.id, it) })
                            }
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onSource(source.id, 1) }) { Text("Оригинал") }
                                TextButton(onClick = { vm.selectSources(listOf(source.id)); onSelection() }) { Text("Подготовить задачи") }
                            }
                            TextButton(onClick = { deleting = source }) { Text("Удалить оригинал") }
                        }
                    }
                }
            }
            if (completedJobs.isNotEmpty()) {
                item {
                    TextButton(onClick = { showCompleted = !showCompleted }) {
                        Text("${if (showCompleted) "Скрыть" else "Показать"} предыдущие разборы (${completedJobs.size})")
                    }
                }
                if (showCompleted) items(completedJobs, key = { "job:${it.id}" }) { job -> ImportJobCard(job, vm, onReview) }
            }
        }
        if (selected.isNotEmpty()) FormActions {
            Button(onClick = onSelection, enabled = !copying, modifier = Modifier.fillMaxWidth()) {
                Text("Подготовить выбранные (${selected.size})")
            }
        }
    }
    deleting?.let { source -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Удалить оригинал «${source.name}»?") },
        text = { Text("Связанные задачи перестанут участвовать в новом поиске. Их текст, собственные фото и сохранённые решения останутся.") },
        confirmButton = { TextButton(onClick = { vm.deleteSource(source.id); deleting = null }) { Text("Удалить оригинал") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }) }
}

@Composable
private fun ImportJobCard(job: ImportJob, vm: LibraryImportViewModel, onReview: (String) -> Unit) {
    Card(onClick = { onReview(job.id) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(job.sourceIds.mapNotNull { vm.source(it)?.name }.joinToString(", "), style = MaterialTheme.typography.titleSmall)
            Text(job.status.label, style = MaterialTheme.typography.bodyMedium)
            Text("Задач для проверки: ${job.drafts.count { it.id !in job.publishedDraftIds && !it.excluded }}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            job.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (job.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
