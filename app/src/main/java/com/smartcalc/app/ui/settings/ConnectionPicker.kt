package com.smartcalc.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.smartcalc.app.data.api.displayName
import com.smartcalc.app.data.settings.ConnectionList

/**
 * Смена подключения в режиме помощника. Следующий запрос и «Повторить запрос» уйдут через выбранное;
 * значок фото показывает, какие подключения принимают изображения.
 */
@Composable
internal fun ConnectionPicker(
    list: ConnectionList,
    onSelect: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(
            onClick = { expanded = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        ) {
            Text(list.active.displayName, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Выбрать подключение")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            list.items.forEach { connection ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(connection.displayName)
                            Text(connection.summary(), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { RadioButton(selected = connection.id == list.active.id, onClick = null) },
                    trailingIcon = if (connection.supportsImages) {
                        { Icon(Icons.Outlined.Image, contentDescription = "Принимает фото") }
                    } else null,
                    onClick = {
                        expanded = false
                        onSelect(connection.id)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Настроить подключения") }, onClick = {
                expanded = false
                onOpenSettings()
            })
        }
    }
}
