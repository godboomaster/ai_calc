package com.smartcalc.app.ui.template

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Share
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartcalc.app.SolutionViewModel
import com.smartcalc.app.data.template.TaskTemplate
import com.smartcalc.app.ui.components.ExplanationCard

/**
 * Шаблоны заданий. Нажатие на шаблон выбирает его для следующей задачи и возвращает на
 * калькулятор — там задачу прикрепляют как обычно: написать, сфотографировать, галерея, документ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(
    vm: SolutionViewModel,
    onBack: () -> Unit,
    onUse: () -> Unit,
    onEdit: (id: String?) -> Unit,
) {
    val templates by vm.templates.collectAsState()
    val armed by vm.armedTemplate.collectAsState()
    val transfer = rememberTemplateTransfer(vm)
    var exportMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("По образцу") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            },
            actions = {
                if (templates.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { exportMenu = true }) {
                            Icon(Icons.Outlined.Share, contentDescription = "Экспорт всех образцов")
                        }
                        DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Сохранить все в файл") },
                                onClick = {
                                    exportMenu = false
                                    transfer.save(templates, true)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Отправить все") },
                                onClick = {
                                    exportMenu = false
                                    transfer.share(templates, true)
                                },
                            )
                        }
                    }
                }
            },
        )
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ExplanationCard(
                    title = "Для одной новой задачи",
                    text = "Образец задаёт метод решения через требования и примеры. " +
                        "Выберите его, затем прикрепите новую задачу на калькуляторе.",
                    details = "ИИ получит требования и примеры вместе с новой задачей. " +
                        "Выбранный образец действует на одну задачу.\n\n" +
                        "Общий вид и подробность ответов задаются в «Оформлении» и сохраняются до сброса.",
                )
            }
            armed?.let { t ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Выбран «${t.name}» — прикрепите задачу на калькуляторе",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = vm::disarmTemplate, modifier = Modifier.align(Alignment.End)) {
                                Text("Не использовать")
                            }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onEdit(null) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("Создать образец", modifier = Modifier.padding(start = 8.dp))
                    }
                    // Файл шаблонов — из экспорта на другом телефоне или присланный кем-то.
                    OutlinedButton(onClick = transfer.import, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.FileOpen, contentDescription = null)
                        Text("Импортировать из файла", modifier = Modifier.padding(start = 8.dp))
                    }
                    ExplanationCard(
                        title = "Образцы из файла",
                        text = "Добавьте образцы, сохранённые в SmartCalc или подготовленные в текстовом файле.",
                        details = "Подходят ZIP и JSON из экспорта SmartCalc.\n\n" +
                            "В Markdown (.md) используйте заголовки: «# Название», «## Требования», " +
                            "«## Пример 1», «### Условие», «### Решение».",
                        detailsLabel = "Форматы файлов",
                    )
                }
            }
            if (templates.isEmpty()) {
                item {
                    Text(
                        "Образцов пока нет. Создайте образец с требованиями и примером решения.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(templates, key = { it.id }) { t ->
                TemplateCard(
                    template = t,
                    selected = t.id == armed?.id,
                    onClick = {
                        vm.armTemplate(t)
                        onUse()
                    },
                    onEdit = { onEdit(t.id) },
                )
            }
        }
    }
}

@Composable
private fun TemplateCard(template: TaskTemplate, selected: Boolean, onClick: () -> Unit, onEdit: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(template.name, style = MaterialTheme.typography.titleMedium)
                val count = template.examples.size
                val details = listOfNotNull(
                    "${count} ${examplesWord(count)}",
                    template.requirements.lineSequence().firstOrNull { it.isNotBlank() },
                ).joinToString(" · ")
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Изменить образец")
            }
        }
    }
}

/** «1 пример», «2 примера», «5 примеров». */
private fun examplesWord(n: Int): String = when {
    n % 100 in 11..14 -> "примеров"
    n % 10 == 1 -> "пример"
    n % 10 in 2..4 -> "примера"
    else -> "примеров"
}
