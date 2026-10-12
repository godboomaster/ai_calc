package com.smartcalc.app.ui.template

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smartcalc.app.data.library.LibraryOriginKind
import com.smartcalc.app.data.library.LibraryVerification
import com.smartcalc.app.ui.math.MathText
import com.smartcalc.app.ui.solution.StepImages

internal val LibraryVerification.label: String
    get() = when (this) {
        LibraryVerification.UNVERIFIED -> "Не проверено"
        LibraryVerification.VERIFIED_BY_USER -> "Проверено мной"
        LibraryVerification.REJECTED -> "Отклонено"
    }

@Composable
internal fun LibraryExampleControls(state: ExampleEditState, enabled: Boolean, onChange: () -> Unit) {
    val metadata = state.library
    val source = when (metadata?.origin?.kind) {
        LibraryOriginKind.LEGACY -> "Ранее сохранено в базе"
        LibraryOriginKind.FILE_IMPORT -> metadata.origin.name?.let { "Файл: $it" } ?: "Импорт файла"
        else -> "Введено вручную"
    }
    var statusMenu by remember { mutableStateOf(false) }
    var showVersions by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "$source · Версия ${metadata?.version ?: 1}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column {
            TextButton(onClick = { statusMenu = true }, enabled = enabled) { Text("${state.verification.label} ▾") }
            DropdownMenu(expanded = statusMenu && enabled, onDismissRequest = { statusMenu = false }) {
                LibraryVerification.entries.forEach { verification ->
                    DropdownMenuItem(
                        text = { Text(verification.label) },
                        onClick = { state.verify(verification); statusMenu = false; onChange() },
                    )
                }
            }
        }
        Text(
            when {
                state.verification == LibraryVerification.REJECTED -> "Пример отклонён и исключён из поиска."
                state.hasContentEdits && state.verification == LibraryVerification.UNVERIFIED ->
                    "Содержимое изменено. Проверьте условие и решение перед подтверждением."
                else -> "«Проверено мной» — ваша отметка после проверки условия и решения."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Использовать в поиске", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = state.enabled && state.verification != LibraryVerification.REJECTED,
                enabled = enabled && state.verification != LibraryVerification.REJECTED,
                onCheckedChange = { state.enabled = it; onChange() },
            )
        }
        if (!metadata?.previousVersions.isNullOrEmpty()) {
            TextButton(onClick = { showVersions = !showVersions }) {
                Text("Предыдущие версии (${metadata!!.previousVersions.size}) ${if (showVersions) "▴" else "▾"}")
            }
            if (showVersions) metadata!!.previousVersions.asReversed().forEach { revision ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    HorizontalDivider()
                    Text("Версия ${revision.version} · ${revision.verification.label}", style = MaterialTheme.typography.titleSmall)
                    Text("Условие", style = MaterialTheme.typography.labelLarge)
                    MathText(revision.condition)
                    StepImages(revision.conditionImages)
                    Text("Решение", style = MaterialTheme.typography.labelLarge)
                    MathText(revision.solution)
                    StepImages(revision.solutionImages)
                }
            }
        }
    }
}
