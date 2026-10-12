package com.smartcalc.app.ui.search

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartcalc.app.SolutionViewModel
import com.smartcalc.app.data.template.TaskTemplate
import com.smartcalc.app.data.template.TemplateExample
import com.smartcalc.app.data.template.TemplateKind
import com.smartcalc.app.ui.template.rememberTemplateTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Что прикрепить к шаблону, пока открыто окно выбора: загружаемый файл или раздел базы. */
private sealed interface AttachChoice {
    data object Import : AttachChoice
    data class Section(val section: TaskTemplate) : AttachChoice
}

/**
 * База решённых задач: разделы по типам задач. Задачи загружаются файлом (те же форматы, что у
 * шаблонов) или вписываются вручную; по ним ищет поиск. Задачи можно ещё и прикрепить к шаблону —
 * тогда они станут его примерами и уйдут ИИ вместе с задачей по шаблону.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: SolutionViewModel,
    onBack: () -> Unit,
    onEdit: (id: String?) -> Unit,
    onFiles: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sections by vm.library.collectAsState()
    val storageError by vm.storageError(TemplateKind.LIBRARY).collectAsState()
    val templates by vm.templates.collectAsState()
    // Шаблон, к которому прикрепить загружаемый файл; переживает системный выбор файла.
    var importInto by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf<AttachChoice?>(null) }
    var exportMenu by remember { mutableStateOf(false) }

    suspend fun attach(templateId: String, examples: List<TemplateExample>) {
        val name = vm.templates.value.firstOrNull { it.id == templateId }?.name ?: return
        try {
            val added = vm.attachToTemplate(templateId, examples)
            val text = if (added == 0) "Эти задачи уже есть в шаблоне «$name»"
                else "Прикреплено к шаблону «$name»: $added ${tasksWord(added)}"
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Toast.makeText(
                context,
                "Не удалось прикрепить задачи к шаблону. Задачи остались в базе. " +
                    "Проверьте, что фотографии доступны, и попробуйте ещё раз.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val transfer = rememberTemplateTransfer(vm, TemplateKind.LIBRARY) { result ->
        val target = importInto ?: return@rememberTemplateTransfer
        importInto = null
        // Attach in this screen's scope so cancellation is not consumed by the transfer import catch.
        scope.launch { attach(target, result.items.flatMap { it.examples }) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("База задач") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            },
            actions = {
                if (sections.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { exportMenu = true }) {
                            Icon(Icons.Outlined.Share, contentDescription = "Экспорт всей базы")
                        }
                        DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Сохранить всё в файл") },
                                onClick = {
                                    exportMenu = false
                                    transfer.save(sections, true)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Отправить всё") },
                                onClick = {
                                    exportMenu = false
                                    transfer.share(sections, true)
                                },
                            )
                        }
                    }
                }
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = {}) { Text("Задачи") }
            TextButton(onClick = onFiles) { Text("Файлы") }
        }
        if (storageError != null) {
            Text(storageError.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Решённые задачи для поиска (клавиша 9 в режиме ИИ): он находит похожие по числам, словам " +
                        "условия и типу без интернета. Найденный ответ — на дисплее, шаги — на цифрах.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (templates.isEmpty()) {
                                importInto = null
                                transfer.import()
                            } else choosing = AttachChoice.Import
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.FileOpen, contentDescription = null)
                        Text("Загрузить из файла", modifier = Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(onClick = { onEdit(null) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("Вписать задачи вручную", modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(
                        "Файлы — как у шаблонов: ZIP и JSON из экспорта SmartCalc или Markdown: «# Тип задач», " +
                            "дальше «## Задача 1», «### Условие», «### Решение». Строка «Ответ: …» в решении " +
                            "попадёт на дисплей. Файл шаблонов тоже подходит.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (sections.isEmpty()) {
                item {
                    Text(
                        "В базе пока нет задач",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(sections, key = { it.id }) { section ->
                SectionCard(
                    section = section,
                    canAttach = templates.isNotEmpty(),
                    onClick = { onEdit(section.id) },
                    onAttach = { choosing = AttachChoice.Section(section) },
                )
            }
        }
    }

    choosing?.let { choice ->
        AttachDialog(
            templates = templates,
            // Загрузить можно и просто в базу; раздел, который уже в базе, — только к шаблону.
            baseOnly = choice == AttachChoice.Import,
            title = when (choice) {
                AttachChoice.Import -> "Куда загрузить задачи"
                is AttachChoice.Section -> "Прикрепить «${choice.section.name}» к шаблону"
            },
            onDismiss = { choosing = null },
            onChoose = { templateId ->
                choosing = null
                when (choice) {
                    AttachChoice.Import -> {
                        importInto = templateId
                        transfer.import()
                    }
                    is AttachChoice.Section -> if (templateId != null) {
                        scope.launch { attach(templateId, choice.section.examples) }
                    }
                }
            },
        )
    }
}

@Composable
private fun SectionCard(section: TaskTemplate, canAttach: Boolean, onClick: () -> Unit, onAttach: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(section.name, style = MaterialTheme.typography.titleMedium)
                val count = section.examples.size
                val searchable = section.examples.count { ex ->
                    ex.library?.let { it.enabled && it.verification != com.smartcalc.app.data.library.LibraryVerification.REJECTED } ?: true
                }
                Text(
                    "$count ${tasksWord(count)} · в поиске: $searchable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (canAttach) {
                IconButton(onClick = onAttach) {
                    Icon(Icons.Outlined.Link, contentDescription = "Прикрепить к шаблону")
                }
            }
        }
    }
}

/**
 * Выбор шаблона, к которому прикрепить задачи. С [baseOnly] первый вариант — «Только в базу»:
 * задачи загрузятся без шаблона.
 */
@Composable
private fun AttachDialog(
    templates: List<TaskTemplate>,
    baseOnly: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onChoose: (templateId: String?) -> Unit,
) {
    var selected by remember { mutableStateOf(if (baseOnly) null else templates.firstOrNull()?.id) }

    @Composable
    fun Option(id: String?, label: String) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .selectable(selected = selected == id, role = Role.RadioButton) { selected = id },
        ) {
            RadioButton(selected = selected == id, onClick = null)
            Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Прикреплённые задачи становятся примерами шаблона: ИИ получает их вместе с задачей по " +
                        "этому шаблону. В поиске они остаются в базе.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (baseOnly) Option(null, "Только в базу")
                templates.forEach { t -> Option(t.id, if (baseOnly) "В базу и к шаблону «${t.name}»" else "«${t.name}»") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoose(selected) }, enabled = baseOnly || selected != null) {
                Text(if (baseOnly) "Выбрать файл" else "Прикрепить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** «1 задача», «2 задачи», «5 задач». */
private fun tasksWord(n: Int): String = when {
    n % 100 in 11..14 -> "задач"
    n % 10 == 1 -> "задача"
    n % 10 in 2..4 -> "задачи"
    else -> "задач"
}
