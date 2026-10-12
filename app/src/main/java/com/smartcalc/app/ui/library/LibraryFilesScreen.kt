package com.smartcalc.app.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import com.smartcalc.app.data.library.source.*
import com.smartcalc.app.data.library.importer.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

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
    TopAppBar(title = { Text(title) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
    })
}
@Composable internal fun LibraryError(message: String?, clear: (() -> Unit)? = null) {
    if (message != null) {
        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        if (clear != null) TextButton(onClick = clear) { Text("Понятно") }
    }
}
@Composable fun LibraryFilesScreen(vm: LibraryImportViewModel, onBack: () -> Unit,
    onSelection: () -> Unit, onReview: (String) -> Unit, onSource: (String, Int) -> Unit, onCamera: () -> Unit) {
    val catalog by vm.sources.collectAsState(); val jobs by vm.jobs.collectAsState()
    val error by vm.error.collectAsState(); val copying by vm.copying.collectAsState()
    val selected by vm.pendingSourceIds.collectAsState()
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<LibrarySource?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            try {
                uris.forEach { uri -> runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
                vm.copyUris(uris); onSelection()
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
        }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            try { vm.copyUris(uris); onSelection() }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
        }
    }
    Column(Modifier.fillMaxSize()) {
        LibraryHeader("База задач", onBack)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = onBack) { Text("Задачи") }
            TextButton(onClick = {}) { Text("Файлы") }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/*", "image/*")) }, enabled = !copying) { Text("Файл") }
            OutlinedButton(onClick = { photos.launch(ActivityResultContracts.PickVisualMedia.ImageOnly.let { androidx.activity.result.PickVisualMediaRequest(it) }) }, enabled = !copying) { Text("Фото") }
            OutlinedButton(onClick = onCamera, enabled = !copying) { Text("Камера") }
        }
        if (copying) LinearProgressIndicator(Modifier.fillMaxWidth().padding(12.dp))
        LibraryError(catalog.error ?: jobs.error ?: error, vm::clearError)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (catalog.sources.isEmpty()) item { Text("Загрузите PDF, DOCX, текст или фотографии. Оригиналы сохраняются отдельно от задач.") }
            items(catalog.sources.reversed(), key = { it.id }) { source ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = source.id in selected, enabled = source.status != SourceStatus.DELETED,
                                onCheckedChange = { chosen -> vm.selectSources(if (chosen) selected + source.id else selected - source.id) })
                            Text(source.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        }
                        Text("${source.format.name} · ${source.segmentCount?.let { "$it фрагментов" } ?: "ещё не разобран"}", style = MaterialTheme.typography.bodySmall)
                        if (source.status == SourceStatus.DELETED) Text("Оригинал удалён; связанные задачи исключены из нового поиска")
                        else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Использовать в поиске", Modifier.weight(1f))
                                Switch(checked = source.status == SourceStatus.ACTIVE, onCheckedChange = { vm.enableSource(source.id, it) })
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onSource(source.id, 1) }) { Text("Оригинал") }
                                TextButton(onClick = { vm.selectSources(listOf(source.id)); onSelection() }) { Text("Подготовить задачи") }
                            }
                            TextButton(onClick = { deleting = source }) { Text("Удалить оригинал") }
                        }
                    }
                }
            }
            if (jobs.jobs.isNotEmpty()) item { Text("Сохранённые разборы", style = MaterialTheme.typography.titleMedium) }
            items(jobs.jobs.reversed(), key = { it.id }) { job ->
                Card(onClick = { onReview(job.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(job.sourceIds.mapNotNull { vm.source(it)?.name }.joinToString(", "), style = MaterialTheme.typography.titleSmall)
                        Text(job.status.label)
                        Text("Черновиков: ${job.drafts.size} · Запросов в партии: ${job.postsUsed}/10", style = MaterialTheme.typography.bodySmall)
                        job.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (job.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (selected.isNotEmpty()) Button(onClick = onSelection, enabled = !copying, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text("Подготовить выбранные (${selected.size})")
        }
    }
    deleting?.let { source -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Удалить оригинал «${source.name}»?") },
        text = { Text("Связанные задачи перестанут участвовать в новом поиске. Их текст, собственные фото и сохранённые решения останутся.") },
        confirmButton = { TextButton(onClick = { vm.deleteSource(source.id); deleting = null }) { Text("Удалить оригинал") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }) }
}
