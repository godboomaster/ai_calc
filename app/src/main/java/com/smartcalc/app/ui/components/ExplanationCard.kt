package com.smartcalc.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Короткое пояснение с необязательными подробностями без анимации раскрытия. */
@Composable
fun ExplanationCard(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    details: String? = null,
    detailsLabel: String = "Подробнее",
) {
    var expanded by rememberSaveable(title, text, details) { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!details.isNullOrBlank()) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.semantics {
                        selected = expanded
                        stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто"
                    },
                ) {
                    Text(if (expanded) "Скрыть подробности" else detailsLabel)
                }
                if (expanded) {
                    details.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }.forEach { paragraph ->
                        Text(
                            paragraph.trim(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
