package com.smartcalc.app.ui.template

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.smartcalc.app.SolutionViewModel
import com.smartcalc.app.data.DocumentException
import com.smartcalc.app.data.DocumentReader
import com.smartcalc.app.data.ImageUtils
import com.smartcalc.app.data.template.ExampleDraft
import com.smartcalc.app.data.template.ImageDraft
import com.smartcalc.app.data.template.TemplateExample
import kotlinx.coroutines.launch
import java.io.File

/** Картинок в одной части примера больше не нужно: каждая уходит ИИ с каждой задачей. */
private const val MAX_IMAGES = 4

/** Условие или решение примера: текст и картинки, которые редактор меняет на месте. */
private class SectionState(text: String, images: List<ImageDraft>) {
    var text by mutableStateOf(text)
    val images = mutableStateListOf<ImageDraft>().apply { addAll(images) }
}

private class ExampleState(val condition: SectionState, val solution: SectionState) {
    fun toDraft() = ExampleDraft(condition.text, condition.images.toList(), solution.text, solution.images.toList())
}

private fun TemplateExample.toState() = ExampleState(
    SectionState(condition, conditionImages.map { ImageDraft(path = it) }),
    SectionState(solution, solutionImages.map { ImageDraft(path = it) }),
)

private fun emptyExample() = ExampleState(SectionState("", emptyList()), SectionState("", emptyList()))

/**
 * Редактор шаблона заданий: название, требования к задачам этого типа и примеры решения —
 * условие и решение текстом, фото из галереи или документом.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateEditScreen(vm: SolutionViewModel, templateId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = remember { templateId?.let { id -> vm.templates.value.firstOrNull { it.id == id } } }
    val transfer = rememberTemplateTransfer(vm)

    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var requirements by remember { mutableStateOf(existing?.requirements.orEmpty()) }
    val examples = remember {
        mutableStateListOf<ExampleState>().apply {
            addAll(existing?.examples?.map { it.toState() }?.ifEmpty { null } ?: listOf(emptyExample()))
        }
    }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Куда добавить выбранное фото или документ — в условие или решение какого примера.
    var target by remember { mutableStateOf<SectionState?>(null) }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES),
    ) { uris ->
        val section = target ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                uris.take(MAX_IMAGES - section.images.size).forEach { uri ->
                    section.images += ImageDraft(bytes = ImageUtils.compressUri(context, uri))
                }
                if (uris.isNotEmpty()) dirty = true
            } catch (e: Exception) {
                Toast.makeText(context, "Не удалось открыть изображение", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val section = target ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                // Текст документа дописывается в поле, страницы PDF и картинки — к фото.
                val doc = DocumentReader.read(context, uri)
                doc.text?.let { section.text = if (section.text.isBlank()) it else section.text.trimEnd() + "\n" + it }
                doc.images.take(MAX_IMAGES - section.images.size).forEach { section.images += ImageDraft(bytes = it) }
                dirty = true
            } catch (e: DocumentException) {
                Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Не удалось открыть документ", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun leave() {
        if (dirty) confirmExit = true else onBack()
    }
    BackHandler(onBack = ::leave)

    @Composable
    fun Section(label: String, placeholder: String, state: SectionState) {
        SectionEditor(
            label = label,
            placeholder = placeholder,
            state = state,
            onChange = { dirty = true },
            onPickImages = {
                target = state
                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onPickDocument = {
                target = state
                documentLauncher.launch(DocumentReader.mimeTypes)
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (existing != null) "Шаблон заданий" else "Новый шаблон") },
            navigationIcon = {
                IconButton(onClick = ::leave) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    dirty = true
                },
                label = { Text("Название") },
                placeholder = { Text("Например: устойчивость по Гурвицу") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = requirements,
                onValueChange = {
                    requirements = it
                    dirty = true
                },
                label = { Text("Требования к таким задачам") },
                placeholder = { Text("Например: решать методом Гурвица, в конце — вывод об устойчивости") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Примеры решённых задач",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Условие и решение — текстом, фото или документом. Всё это уходит ИИ вместе с каждой задачей " +
                    "по шаблону, поэтому одного-двух хороших примеров обычно хватает.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            examples.forEachIndexed { index, example ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Пример ${index + 1}",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                examples.removeAt(index)
                                dirty = true
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Удалить пример")
                            }
                        }
                        Column(modifier = Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Section("Условие", "Текст условия", example.condition)
                            Section("Решение", "Как такую задачу нужно решить", example.solution)
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    examples += emptyExample()
                    dirty = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Добавить пример") }
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        vm.saveTemplate(existing?.id, name, requirements, examples.map { it.toDraft() })
                        onBack()
                    }
                },
                enabled = name.isNotBlank() && !saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить") }
            if (existing != null) {
                // Экспортируется сохранённый шаблон — поэтому с несохранёнными правками кнопки ждут.
                Text(
                    if (dirty) "Экспорт — после сохранения изменений" else "Экспорт шаблона",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { transfer.save(listOf(existing), false) },
                        enabled = !dirty,
                        modifier = Modifier.weight(1f),
                    ) { Text("Сохранить в файл") }
                    OutlinedButton(
                        onClick = { transfer.share(listOf(existing), false) },
                        enabled = !dirty,
                        modifier = Modifier.weight(1f),
                    ) { Text("Отправить") }
                }
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Удалить шаблон")
                }
            }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Выйти без сохранения?") },
            text = { Text("Изменения в шаблоне пропадут.") },
            confirmButton = { TextButton(onClick = onBack) { Text("Выйти") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Остаться") } },
        )
    }
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить шаблон «${existing.name}»?") },
            text = { Text("Его примеры и фото удалятся с телефона.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTemplate(existing.id)
                    onBack()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun SectionEditor(
    label: String,
    placeholder: String,
    state: SectionState,
    onChange: () -> Unit,
    onPickImages: () -> Unit,
    onPickDocument: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
            value = state.text,
            onValueChange = {
                state.text = it
                onChange()
            },
            placeholder = { Text(placeholder) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.images.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 4.dp),
            ) {
                state.images.forEachIndexed { i, img ->
                    Box {
                        AsyncImage(
                            model = img.bytes ?: img.path?.let { File(it) },
                            contentDescription = "Фото ${i + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(84.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Убрать фото",
                            tint = Color.White,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .clickable {
                                    state.images.removeAt(i)
                                    onChange()
                                }
                                .padding(3.dp)
                                .size(16.dp),
                        )
                    }
                }
            }
        }
        Row {
            TextButton(onClick = onPickImages, enabled = state.images.size < MAX_IMAGES) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Фото", modifier = Modifier.padding(start = 6.dp))
            }
            TextButton(onClick = onPickDocument) {
                Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Документ", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}
