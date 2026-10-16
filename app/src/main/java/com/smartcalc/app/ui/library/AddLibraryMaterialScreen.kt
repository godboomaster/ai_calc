package com.smartcalc.app.ui.library

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smartcalc.app.SolutionViewModel
import com.smartcalc.app.data.template.TemplateKind
import com.smartcalc.app.ui.components.ExplanationCard
import com.smartcalc.app.ui.template.rememberTemplateTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One entry point; the labels distinguish documents from already prepared collections. */
@Composable
fun AddLibraryMaterialScreen(
    vm: SolutionViewModel,
    libraryVm: LibraryImportViewModel,
    onBack: () -> Unit,
    onSelection: () -> Unit,
    onCamera: () -> Unit,
    onManual: () -> Unit,
    onCollectionImported: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copying by libraryVm.copying.collectAsState()
    val error by libraryVm.error.collectAsState()
    val transfer = rememberTemplateTransfer(vm, TemplateKind.LIBRARY) { onCollectionImported() }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            try {
                uris.forEach { uri -> runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } }
                libraryVm.copyUris(uris)
                onSelection()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The view model retains the readable error. */ }
        }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            try { libraryVm.copyUris(uris); onSelection() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The view model retains the readable error. */ }
        }
    }
    Column(Modifier.fillMaxSize()) {
        LibraryHeader("Добавить материал", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExplanationCard("Материалы для библиотеки", "Добавьте решённые задачи. Документы и фотографии сначала пройдут разбор и вашу проверку.")
            if (copying) LinearProgressIndicator(Modifier.fillMaxWidth())
            LibraryError(error, clear = libraryVm::clearError)
            MaterialChoice("Документ", "PDF, Word, текст или Markdown", !copying) {
                documents.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/*", "image/*"))
            }
            MaterialChoice("Фото из галереи", "Одно или несколько изображений", !copying) {
                photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            MaterialChoice("Снять камерой", "Сфотографировать условие и решение", !copying, onCamera)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            MaterialChoice("Готовая подборка задач", "Экспорт приложения: ZIP, JSON или структурированный Markdown", !copying, transfer.import)
            MaterialChoice("Ввести вручную", "Записать условие, решение и ответ", !copying, onManual)
        }
    }
}

@Composable
private fun MaterialChoice(title: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}
