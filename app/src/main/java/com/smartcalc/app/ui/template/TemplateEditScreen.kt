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
import androidx.compose.runtime.collectAsState
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
import com.smartcalc.app.data.template.ImageDraft
import com.smartcalc.app.data.template.TemplateKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

/** Картинок в одной части примера больше не нужно: каждая уходит ИИ с каждой задачей. */
private const val MAX_IMAGES = 4

/** Надписи редактора: шаблон заданий и раздел базы задач устроены одинаково, но называются по-разному. */
private class EditorWords(
    val title: String,
    val newTitle: String,
    val nameLabel: String,
    val namePlaceholder: String,
    val examplesTitle: String,
    val examplesHint: String,
    val example: String,
    val solutionPlaceholder: String,
    val addExample: String,
    val removeExample: String,
    val exportTitle: String,
    val delete: String,
    val lostChanges: String,
    val deleteDetails: String,
)

private fun wordsFor(kind: TemplateKind) = when (kind) {
    TemplateKind.TEMPLATES -> EditorWords(
        title = "Шаблон заданий",
        newTitle = "Новый шаблон",
        nameLabel = "Название",
        namePlaceholder = "Например: устойчивость по Гурвицу",
        examplesTitle = "Примеры решённых задач",
        examplesHint = "Условие и решение — текстом, фото или документом. Всё это уходит ИИ вместе с каждой задачей " +
            "по шаблону, поэтому одного-двух хороших примеров обычно хватает.",
        example = "Пример",
        solutionPlaceholder = "Как такую задачу нужно решить",
        addExample = "Добавить пример",
        removeExample = "Удалить пример",
        exportTitle = "Экспорт шаблона",
        delete = "Удалить шаблон",
        lostChanges = "Изменения в шаблоне пропадут.",
        deleteDetails = "Его примеры и фото удалятся с телефона.",
    )
    TemplateKind.LIBRARY -> EditorWords(
        title = "Раздел базы задач",
        newTitle = "Новый раздел",
        nameLabel = "Тип задач",
        namePlaceholder = "Например: кинематика, 9 класс",
        examplesTitle = "Решённые задачи",
        examplesHint = "Условие и решение — текстом, фото или документом. Поиск (клавиша 9 в режиме ИИ) находит " +
            "их по числам и словам условия. Строка «Ответ: …» в решении попадёт на дисплей, пункты «1)», «2)» " +
            "или абзацы — на цифры.",
        example = "Задача",
        solutionPlaceholder = "Решение, в конце — «Ответ: …»",
        addExample = "Добавить задачу",
        removeExample = "Удалить задачу",
        exportTitle = "Экспорт раздела",
        delete = "Удалить раздел",
        lostChanges = "Изменения в разделе пропадут.",
        deleteDetails = "Его задачи и фото удалятся с телефона.",
    )
}

/**
 * Редактор шаблона заданий: название, требования к задачам этого типа и примеры решения —
 * условие и решение текстом, фото из галереи или документом. С [kind] = LIBRARY — редактор раздела
 * базы задач: тип задач и сами задачи, без требований для ИИ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateEditScreen(
    vm: SolutionViewModel,
    templateId: String?,
    onBack: () -> Unit,
    kind: TemplateKind = TemplateKind.TEMPLATES,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val words = remember(kind) { wordsFor(kind) }
    val existing = remember { templateId?.let { id -> vm.items(kind).value.firstOrNull { it.id == id } } }
    val transfer = rememberTemplateTransfer(vm, kind)
    val storageError by vm.storageError(kind).collectAsState()

    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var requirements by remember { mutableStateOf(existing?.requirements.orEmpty()) }
    val examples = remember {
        mutableStateListOf<ExampleEditState>().apply {
            addAll(existing?.examples?.map { ExampleEditState(it) }?.ifEmpty { null } ?: listOf(ExampleEditState()))
        }
    }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Куда добавить выбранное фото или документ — в условие или решение какого примера.
    var target by remember { mutableStateOf<ExampleSectionState?>(null) }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES),
    ) { uris ->
        val section = target ?: return@rememberLauncherForActivityResult
        if (saving || uris.isEmpty()) return@rememberLauncherForActivityResult
        reading = true
        scope.launch {
            try {
                uris.take(MAX_IMAGES - section.images.size).forEach { uri ->
                    section.addImage(ImageDraft(bytes = ImageUtils.compressUri(context, uri)))
                    dirty = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Toast.makeText(context, "Не удалось открыть изображение", Toast.LENGTH_SHORT).show()
            } finally {
                reading = false
            }
        }
    }
    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val section = target ?: return@rememberLauncherForActivityResult
        if (uri == null || saving) return@rememberLauncherForActivityResult
        reading = true
        scope.launch {
            try {
                // Текст документа дописывается в поле, страницы PDF и картинки — к фото.
                val doc = DocumentReader.read(context, uri)
                doc.text?.let { section.updateText(if (section.text.isBlank()) it else section.text.trimEnd() + "\n" + it) }
                doc.images.take(MAX_IMAGES - section.images.size).forEach { section.addImage(ImageDraft(bytes = it)) }
                dirty = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: DocumentException) {
                Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Не удалось открыть документ", Toast.LENGTH_SHORT).show()
            } finally {
                reading = false
            }
        }
    }

    fun leave() {
        if (saving || reading) return
        if (dirty) confirmExit = true else onBack()
    }
    BackHandler(onBack = ::leave)

    @Composable
    fun Section(label: String, placeholder: String, state: ExampleSectionState) {
        SectionEditor(
            label = label,
            placeholder = placeholder,
            state = state,
            enabled = !saving && !reading,
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
            title = { Text(if (existing != null) words.title else words.newTitle) },
            navigationIcon = {
                IconButton(onClick = ::leave) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            },
        )
        if (storageError != null) {
            Text(storageError.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            return@Column
        }
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
                label = { Text(words.nameLabel) },
                placeholder = { Text(words.namePlaceholder) },
                singleLine = true,
                enabled = !saving && !reading,
                modifier = Modifier.fillMaxWidth(),
            )
            // У базы требований нет: её задачи ищут, а не отправляют ИИ.
            if (kind == TemplateKind.TEMPLATES) {
                OutlinedTextField(
                    value = requirements,
                    onValueChange = {
                        requirements = it
                        dirty = true
                    },
                    label = { Text("Требования к таким задачам") },
                    placeholder = { Text("Например: решать методом Гурвица, в конце — вывод об устойчивости") },
                    minLines = 3,
                    enabled = !saving && !reading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                words.examplesTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                words.examplesHint,
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
                                "${words.example} ${index + 1}",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(enabled = !saving && !reading, onClick = {
                                examples.removeAt(index)
                                dirty = true
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = words.removeExample)
                            }
                        }
                        Column(modifier = Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Section("Условие", "Текст условия", example.condition)
                            Section("Решение", words.solutionPlaceholder, example.solution)
                            if (kind == TemplateKind.LIBRARY) {
                                LibraryExampleControls(example, enabled = !saving && !reading) { dirty = true }
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    examples += ExampleEditState()
                    dirty = true
                },
                enabled = !saving && !reading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(words.addExample) }
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            vm.saveTemplate(existing?.id, name, requirements, examples.map { it.toDraft() }, kind, expectedSection = existing)
                            onBack()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            Toast.makeText(context, "Не удалось сохранить. Черновик оставлен на экране. Проверьте свободное место; если запись изменена в другом окне, откройте её заново.", Toast.LENGTH_LONG).show()
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = name.isNotBlank() && !saving && !reading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить") }
            if (existing != null) {
                // Экспортируется сохранённый шаблон — поэтому с несохранёнными правками кнопки ждут.
                Text(
                    if (dirty) "Экспорт — после сохранения изменений" else words.exportTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { transfer.save(listOf(existing), false) },
                        enabled = !dirty && !saving && !reading,
                        modifier = Modifier.weight(1f),
                    ) { Text("Сохранить в файл") }
                    OutlinedButton(
                        onClick = { transfer.share(listOf(existing), false) },
                        enabled = !dirty && !saving && !reading,
                        modifier = Modifier.weight(1f),
                    ) { Text("Отправить") }
                }
                OutlinedButton(enabled = !saving && !reading, onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(words.delete)
                }
            }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Выйти без сохранения?") },
            text = { Text(words.lostChanges) },
            confirmButton = { TextButton(onClick = onBack) { Text("Выйти") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Остаться") } },
        )
    }
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("${words.delete} «${existing.name}»?") },
            text = { Text(words.deleteDetails) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    saving = true
                    scope.launch {
                        try {
                            vm.deleteTemplate(existing.id, kind, expectedSection = existing)
                            onBack()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            Toast.makeText(context, "Не удалось удалить. Данные сохранены.", Toast.LENGTH_LONG).show()
                        } finally {
                            saving = false
                        }
                    }
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
    state: ExampleSectionState,
    enabled: Boolean,
    onChange: () -> Unit,
    onPickImages: () -> Unit,
    onPickDocument: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
            value = state.text,
            onValueChange = {
                state.updateText(it)
                onChange()
            },
            placeholder = { Text(placeholder) },
            minLines = 2,
            enabled = enabled,
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
                                .clickable(enabled = enabled) {
                                    state.removeImage(i)
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
            TextButton(onClick = onPickImages, enabled = enabled && state.images.size < MAX_IMAGES) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Фото", modifier = Modifier.padding(start = 6.dp))
            }
            TextButton(onClick = onPickDocument, enabled = enabled) {
                Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Документ", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}
