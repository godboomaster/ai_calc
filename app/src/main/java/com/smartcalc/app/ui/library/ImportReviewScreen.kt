package com.smartcalc.app.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smartcalc.app.data.library.importer.*
import com.smartcalc.app.data.library.source.SourceStatus
import com.smartcalc.app.data.library.source.SegmentKind
import com.smartcalc.app.data.library.source.SourceSegmentRef
import com.smartcalc.app.data.library.extract.ExtractionWarning

@Composable fun ImportReviewScreen(vm: LibraryImportViewModel, jobId: String, onBack: () -> Unit,
    onSource: (String, Int) -> Unit) {
    val catalog by vm.jobs.collectAsState(); val edits by vm.edits.collectAsState()
    val library by vm.library.collectAsState(); val sources by vm.sources.collectAsState()
    val error by vm.error.collectAsState()
    val actionBusy by vm.actionBusy.collectAsState()
    val job = catalog.jobs.firstOrNull { it.id == jobId }
    var selected by rememberSaveable(jobId) { mutableStateOf(emptyList<String>()) }
    var sectionId by rememberSaveable(jobId) { mutableStateOf<String?>(null) }
    var name by rememberSaveable(jobId) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var selectedOnce by rememberSaveable(jobId) { mutableStateOf(false) }
    var repeatConfirmed by rememberSaveable(jobId) { mutableStateOf(false) }
    LaunchedEffect(selected, library) { repeatConfirmed = false }
    fun back() { vm.flushInBackground(jobId); onBack() }
    BackHandler(onBack = ::back)
    DisposableEffect(jobId) { onDispose { vm.flushInBackground(jobId) } }
    LaunchedEffect(job?.drafts?.size) {
        if (!selectedOnce && !job?.drafts.isNullOrEmpty()) {
            selected = job!!.drafts.filterNot { it.excluded || it.id in job.publishedDraftIds }.map { it.id }; selectedOnce = true
        }
        if (name.isEmpty()) name = job?.sourceIds?.firstOrNull()?.let { vm.source(it)?.name?.substringBeforeLast('.') }.orEmpty()
    }
    Column(Modifier.fillMaxSize()) {
        LibraryHeader("Проверка разбора", ::back)
        LibraryError(catalog.error ?: error, vm::clearError)
        if (job == null) { Text("Разбор недоступен", modifier = Modifier.padding(16.dp)); return@Column }
        val busy = job.busy || actionBusy
        val drafts = job.drafts.map { edits[it.id] ?: it }
        val comparisons = drafts.associate { it.id to comparableExamples(it, library, jobId) }
        val repeatRequired = drafts.any { it.id in selected && it.id !in job.publishedDraftIds && comparisons[it.id].orEmpty().isNotEmpty() }
        val review = ImportReviewState(drafts, selected.toSet(), job.publishedDraftIds, busy, repeatRequired, repeatConfirmed)
        val sourceAvailable = sources.error == null && drafts.filter { it.id in selected }.all { draft ->
            draft.refs.all { ref -> sources.sources.firstOrNull { it.id == ref.sourceId }?.status == SourceStatus.ACTIVE }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(job.status.label, style = MaterialTheme.typography.titleMedium)
                Text("Запросов в партии: ${job.postsUsed}/10 · Черновиков: ${job.drafts.size}")
                job.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Проверьте условие, авторское решение, ответ и границы. Подтверждение разбора не означает математической проверки.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (busy) TextButton(onClick = { vm.cancel(jobId) }) { Text("Остановить") }
                    else TextButton(onClick = { vm.process(jobId) }) { Text(if (job.status == ImportStatus.FAILED || job.status == ImportStatus.INTERRUPTED) "Повторить обработку" else "Продолжить") }
                    if (!busy) TextButton(onClick = { vm.addDraft(jobId) }) { Text("Новый черновик") }
                }
                if (edits.keys.any { id -> job.drafts.any { it.id == id } }) Text("Сохраняю правки…", style = MaterialTheme.typography.bodySmall)
            }
            items(drafts, key = { it.id }) { draft ->
                var showComparison by remember { mutableStateOf(false) }
                val published = draft.id in job.publishedDraftIds
                val enabled = !busy && !published
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = draft.id in selected, enabled = enabled && !draft.excluded,
                                onCheckedChange = { chosen -> selected = if (chosen) selected + draft.id else selected - draft.id })
                            Text(if (published) "Добавлено в библиотеку" else "Задача ${drafts.indexOf(draft) + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        }
                        OutlinedTextField(value = draft.condition, onValueChange = { vm.edit(jobId, draft.copy(condition = it)) },
                            label = { Text("Условие") }, enabled = enabled, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 7)
                        com.smartcalc.app.ui.solution.StepImages(draft.conditionImages)
                        OutlinedTextField(value = draft.solution, onValueChange = { vm.edit(jobId, draft.copy(solution = it)) },
                            label = { Text("Авторское решение") }, enabled = enabled, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 7)
                        com.smartcalc.app.ui.solution.StepImages(draft.solutionImages)
                        OutlinedTextField(value = draft.answer, onValueChange = { vm.edit(jobId, draft.copy(answer = it)) },
                            label = { Text("Ответ") }, enabled = enabled, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                        val previous = comparisons[draft.id].orEmpty()
                        if (!published && previous.isNotEmpty()) {
                            TextButton(onClick = { showComparison = !showComparison }) { Text("Ранее из этих фрагментов: ${previous.size} · сравнить") }
                            Text("Сопоставление по ссылкам на источник. Сверьте границы и локальные правки; прежние задачи не заменяются.", style = MaterialTheme.typography.bodySmall)
                            if (showComparison) previous.forEach { comparison ->
                                val old = comparison.example
                                HorizontalDivider()
                                Text("${comparison.sectionName} · версия ${old.library?.version ?: 1}", style = MaterialTheme.typography.titleSmall)
                                Text("Прежнее условие", style = MaterialTheme.typography.labelLarge)
                                com.smartcalc.app.ui.math.MathText(old.condition)
                                com.smartcalc.app.ui.solution.StepImages(old.conditionImages)
                                Text("Прежнее решение", style = MaterialTheme.typography.labelLarge)
                                com.smartcalc.app.ui.math.MathText(old.solution)
                                com.smartcalc.app.ui.solution.StepImages(old.solutionImages)
                                if (old.answer.isNotBlank()) { Text("Прежний ответ"); com.smartcalc.app.ui.math.MathText(old.answer) }
                            }
                        }
                        draft.warnings.forEach { warning -> Text(warning.detail,
                            color = if (warning.blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall) }
                        if (draft.warnings.any { it.blocking } && enabled) TextButton(onClick = {
                            vm.edit(jobId, draft.copy(warnings = draft.warnings.map { it.copy(blocking = false) }))
                        }) { Text("Замечания исправлены, границы проверены") }
                        Text("Ссылки на оригинал", style = MaterialTheme.typography.labelLarge)
                        val permitted = (job.segments.map { it.ref } + draft.refs).distinct()
                        permitted.forEach { ref ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = ref in draft.refs, enabled = enabled,
                                    onCheckedChange = { checked -> vm.edit(jobId, draft.copy(
                                        refs = if (checked) draft.refs + ref else draft.refs - ref,
                                        warnings = draft.warnings.filterNot { it.code == "source_assignment" } + ExtractionWarning("source_assignment", "Проверьте распределение источников после изменения ссылок."))) })
                                TextButton(onClick = { onSource(ref.sourceId, ref.ordinal) }, modifier = Modifier.weight(1f)) {
                                    Text(sourceLabel(vm.source(ref.sourceId)?.name.orEmpty(), ref))
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { vm.split(jobId, draft.id) }, enabled = enabled) { Text("Разделить на две") }
                            TextButton(onClick = { vm.edit(jobId, draft.copy(excluded = !draft.excluded)); selected = selected - draft.id }, enabled = enabled) {
                                Text(if (draft.excluded) "Вернуть" else "Исключить")
                            }
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { vm.merge(jobId, selected) }, enabled = !busy && selected.size >= 2) { Text("Объединить выбранные") }
                Box {
                    TextButton(onClick = { menu = true }) { Text(sectionId?.let { id -> library.firstOrNull { it.id == id }?.name } ?: "Новый раздел ▾") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Новый раздел") }, onClick = { sectionId = null; menu = false })
                        library.forEach { section -> DropdownMenuItem(text = { Text(section.name) }, onClick = { sectionId = section.id; name = section.name; menu = false }) }
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название раздела") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                if (!sourceAvailable) Text("Включите связанные источники во вкладке «Файлы». Удалённый оригинал нужно загрузить снова.", color = MaterialTheme.colorScheme.error)
                if (repeatRequired) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = repeatConfirmed, onCheckedChange = { repeatConfirmed = it }, enabled = !busy)
                    Text("Сравнил прежние задачи. Добавить выбранные как новые, сохранив прежние правки.", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { vm.publish(jobId, selected, sectionId, name) },
                    enabled = review.canPublish && sourceAvailable && name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Добавить выбранные (${selected.size})") }
            }
        }
    }
}
internal fun sourceLabel(name: String, ref: SourceSegmentRef): String = "$name · ${when (ref.kind) {
    SegmentKind.PDF_PAGE -> "страница"
    SegmentKind.DOCUMENT_BLOCK -> "фрагмент"
    SegmentKind.IMAGE -> "изображение"
}} ${ref.ordinal}"
