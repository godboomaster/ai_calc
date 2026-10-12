package com.smartcalc.app.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smartcalc.app.data.settings.ApiSettings
import com.smartcalc.app.data.library.extract.PageSelection
import com.smartcalc.app.data.library.source.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable fun ImportSelectionScreen(vm: LibraryImportViewModel, onBack: () -> Unit, onReview: (String) -> Unit) {
    val sourceIds by vm.pendingSourceIds.collectAsState(); val catalog by vm.sources.collectAsState()
    val jobs by vm.jobs.collectAsState()
    val settings by vm.settings.collectAsState(initial = ApiSettings())
    var order by rememberSaveable(sourceIds) { mutableStateOf(sourceIds) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var ranges by rememberSaveable(sourceIds) { mutableStateOf(hashMapOf<String, String>()) }
    var error by remember { mutableStateOf<String?>(null) }; var starting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(sourceIds) {
        try {
            counts = sourceIds.associateWith { id -> vm.source(id)?.segmentCount ?: vm.inspect(id) }
            ranges = HashMap(ranges).apply { counts.forEach { (id, count) -> if (id !in this) put(id, "1-${minOf(count, 100)}") } }
        } catch (_: Exception) { error = "Не удалось прочитать источник. Защищённый PDF и старый DOC нужно сохранить доступной копией." }
    }
    Column(Modifier.fillMaxSize()) {
        LibraryHeader("Подготовка задач", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Проверьте диапазон и порядок файлов. Необработанная часть оригинала остаётся доступной.")
            order.forEachIndexed { index, id ->
                val source = catalog.sources.firstOrNull { it.id == id }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(source?.name ?: "Источник недоступен", style = MaterialTheme.typography.titleSmall)
                        val count = counts[id]
                        if (count == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else {
                            Text("Всего: $count ${if (source?.format == SourceFormat.PDF) "страниц" else "фрагментов"}")
                            val processed = jobs.jobs.flatMap { job -> job.segments.filter { it.ref.sourceId == id && it.ref.segmentId in job.processedSegmentIds }.map { it.ref.ordinal } }
                            if (processed.isNotEmpty()) Text("Уже обработано: ${formatOrdinalRanges(processed)}. Повтор создаст новые черновики для сравнения, прежние задачи сохранятся.", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(value = ranges[id].orEmpty(), onValueChange = { ranges = HashMap(ranges).apply { put(id, it) } },
                                label = { Text(if (source?.format == SourceFormat.PDF) "Страницы, например 1-3,6" else "Фрагменты") }, modifier = Modifier.fillMaxWidth(), enabled = !starting)
                            val selected = runCatching { parsePageSelection(ranges[id].orEmpty(), count).ordinals.size }.getOrNull()
                            if (selected != null) Text("Выбрано: $selected · Останется: ${count - selected}", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = { order = order.toMutableList().apply { val old = this[index - 1]; this[index - 1] = this[index]; this[index] = old } }, enabled = index > 0 && !starting) { Text("Выше") }
                            TextButton(onClick = { order = order.toMutableList().apply { val old = this[index + 1]; this[index + 1] = this[index]; this[index] = old } }, enabled = index < order.lastIndex && !starting) { Text("Ниже") }
                        }
                    }
                }
            }
            Text("Для распознавания используется настроенный API: ${settings.model}. Изображения: ${if (settings.supportsImages) "поддержка подтверждена" else "не подтверждена"}.")
            Text("Структурированный текст разбирается локально. Неразобранные фрагменты передаются API только после запуска. Если модель не подходит, можно исправить черновики вручную.")
            Text("Предел партии: 100 фрагментов, до 10 запросов, до 2 изображений или 12 000 символов в пакете. Цена зависит от провайдера.", style = MaterialTheme.typography.bodySmall)
            LibraryError(error)
            Button(onClick = {
                starting = true; error = null
                scope.launch {
                    try {
                        val selections = order.associateWith { id -> parsePageSelection(ranges[id].orEmpty(), counts.getValue(id)) }
                        require(selections.values.sumOf { it.ordinals.size } <= 100) { "Выберите суммарно не больше 100 фрагментов" }
                        onReview(vm.createAndProcess(order, selections))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (e: Exception) { error = e.message?.take(250) ?: "Не удалось начать разбор" }
                    finally { starting = false }
                }
            }, enabled = !starting && order.isNotEmpty() && counts.size == order.size, modifier = Modifier.fillMaxWidth()) {
                Text(if (starting) "Сохраняю диапазон…" else "Обработать выбранное")
            }
            Text("Добавление разобранных задач потребует вашей проверки. Отметка «Проверено мной» автоматически не ставится.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
