package com.smartcalc.app.ui.template

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartcalc.app.SolutionViewModel
import com.smartcalc.app.data.template.TaskTemplate

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

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Шаблоны заданий") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            },
        )
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Шаблон — примеры решённых задач одного типа и требования к ним. Выберите шаблон и " +
                        "прикрепите новую задачу: ИИ получит шаблон вместе с ней и решит так же. " +
                        "Шаблон действует на одну задачу.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            armed?.let { t ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Выбран «${t.name}» — прикрепите задачу на калькуляторе",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = vm::disarmTemplate) { Text("Не использовать") }
                        }
                    }
                }
            }
            item {
                Button(onClick = { onEdit(null) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Создать шаблон заданий", modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (templates.isEmpty()) {
                item {
                    Text(
                        "Шаблонов пока нет",
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
                Icon(Icons.Filled.Edit, contentDescription = "Изменить шаблон")
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
