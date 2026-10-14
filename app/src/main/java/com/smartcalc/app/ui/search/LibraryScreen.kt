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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.smartcalc.app.ui.components.ExplanationCard
import com.smartcalc.app.ui.library.LibraryTabs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
    onEdit: (id: String?, exampleIndex: Int?) -> Unit,
    onFiles: () -> Unit = {},
    onSearch: () -> Unit,
    onAddMaterial: () -> Unit,
    onUse: () -> Unit,
    onSource: (com.smartcalc.app.data.library.source.SourceSegmentRef) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sections by vm.library.collectAsState()
    val storageError by vm.storageError(TemplateKind.LIBRARY).collectAsState()
    val templates by vm.templates.collectAsState()
    var choosing by remember { mutableStateOf<TaskTemplate?>(null) }
    var exportMenu by remember { mutableStateOf(false) }
    var selectedSection by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val selected = sections.firstOrNull { it.id == selectedSection }
    if (selected != null) {
        LibrarySectionScreen(vm, selected, onBack = { selectedSection = null },
            onEdit = { onEdit(selected.id, it) }, onUse = onUse, onSource = onSource)
        return
    }

    suspend fun attach(templateId: String, examples: List<TemplateExample>) {
        val name = vm.templates.value.firstOrNull { it.id == templateId }?.name ?: return
        try {
            val added = vm.attachToTemplate(templateId, examples)
            val text = if (added == 0) "Эти задачи уже есть в образце «$name»"
                else "Добавлено в образец «$name»: $added ${tasksWord(added)}"
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Toast.makeText(
                context,
                "Не удалось добавить копии в образец. Задачи остались в библиотеке. " +
                    "Проверьте, что фотографии доступны, и попробуйте ещё раз.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val transfer = rememberTemplateTransfer(vm, TemplateKind.LIBRARY)

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Библиотека") },
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
        LibraryTabs(1, onSearch = onSearch, onTasks = {}, onFiles = onFiles)
        if (storageError != null) {
            Text(storageError.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            return@Column
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onAddMaterial,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.FileOpen, contentDescription = null)
                        Text("Добавить материал", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            item {
                ExplanationCard(
                    title = "Ваши решённые задачи",
                    text = "Храните примеры по разделам и находите готовые решения во вкладке «Поиск».",
                    details = "Документы и фото сохраняются во вкладке «Файлы». После разбора вы проверяете задачи и добавляете их сюда.\n\nЧтобы задать способ решения новой задачи, добавьте нужные примеры в «По образцу».",
                )
            }
            if (sections.isEmpty()) {
                item {
                    Text(
                        "В библиотеке пока нет задач",
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
                    onClick = { selectedSection = section.id },
                    onAttach = { choosing = section },
                )
            }
        }
    }

    choosing?.let { choice ->
        AttachDialog(
            templates = templates,
            title = "Добавить «${choice.name}» в образец",
            onDismiss = { choosing = null },
            onChoose = { templateId ->
                choosing = null
                scope.launch { attach(templateId, choice.examples) }
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
                    Icon(Icons.Outlined.Link, contentDescription = "Добавить в образец")
                }
            }
        }
    }
}

/** Выбор образца, в который будут скопированы задачи. */
@Composable
private fun AttachDialog(
    templates: List<TaskTemplate>,
    title: String,
    onDismiss: () -> Unit,
    onChoose: (templateId: String) -> Unit,
) {
    var selected by remember { mutableStateOf(templates.firstOrNull()?.id) }

    @Composable
    fun Option(id: String, label: String) {
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
                    "Задачи будут скопированы в образец для решения новых задач. Изменения в библиотеке не обновляют эти копии.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                templates.forEach { t -> Option(t.id, "«${t.name}»") }
            }
        },
        confirmButton = {
            TextButton(onClick = { selected?.let(onChoose) }, enabled = selected != null) {
                Text("Добавить копии")
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
