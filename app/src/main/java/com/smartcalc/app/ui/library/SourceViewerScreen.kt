package com.smartcalc.app.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.Alignment
import com.smartcalc.app.ui.components.FormActions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.smartcalc.app.data.library.extract.ExtractedSegment
import com.smartcalc.app.data.library.source.SourceStatus
import com.smartcalc.app.data.library.source.SourceFormat
import com.smartcalc.app.ui.math.MathText
import kotlinx.coroutines.launch

@Composable fun SourceViewerScreen(vm: LibraryImportViewModel, sourceId: String, initialOrdinal: Int, onBack: () -> Unit) {
    val catalog by vm.sources.collectAsState(); val source = catalog.sources.firstOrNull { it.id == sourceId }
    var ordinal by remember(sourceId, initialOrdinal) { mutableIntStateOf(initialOrdinal) }
    var preview by remember { mutableStateOf<ExtractedSegment?>(null) }; var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    LaunchedEffect(sourceId, ordinal, source?.status) {
        preview = null; error = null
        try { preview = vm.preview(sourceId, ordinal) }
        catch (_: Exception) { error = if (source?.status == SourceStatus.DELETED) "Оригинал удалён. Текст задачи и сохранённые решения доступны в библиотеке и истории." else "Не удалось открыть фрагмент оригинала." }
    }
    Column(Modifier.fillMaxSize()) {
        LibraryHeader(source?.name ?: "Оригинал", onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { ordinal-- }, enabled = ordinal > 1) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Предыдущий фрагмент")
            }
            Text("${if (source?.format == SourceFormat.PDF) "Страница" else "Фрагмент"} $ordinal",
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = { ordinal++ }, enabled = source?.segmentCount?.let { ordinal < it } == true) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Следующий фрагмент")
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LibraryError(error)
            if (preview == null && error == null) CircularProgressIndicator()
            preview?.let { segment ->
                if (segment.text.isNotBlank()) MathText(segment.text)
                segment.imagePaths.forEach { path -> com.smartcalc.app.ui.components.ReadableImage(path, "Изображение оригинала", Modifier.fillMaxWidth()) }
                segment.warnings.forEach { Text(it.detail, color = MaterialTheme.colorScheme.error) }
            }
        }
        FormActions {
        OutlinedButton(onClick = { scope.launch { try { openLibraryOriginal(context, vm, sourceId) } catch (_: Exception) { error = "Не удалось открыть файл в другом приложении." } } },
            enabled = source != null && source.status != SourceStatus.DELETED, modifier = Modifier.fillMaxWidth()) { Text("Открыть исходный файл") }
        }
    }
}
