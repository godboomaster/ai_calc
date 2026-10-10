package com.smartcalc.app.ui.calculator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Клавиши ИИ на месте цифр — калькулятор переключается на них значком в углу. [heightPx] — высота
 * цифровой клавиатуры: клавиши ИИ занимают ровно её место, и дисплей не прыгает.
 */
@Composable
internal fun AiKeypad(
    heightPx: Int,
    formatSummary: String?,
    onWrite: () -> Unit,
    onPhoto: () -> Unit,
    onGallery: () -> Unit,
    onDocument: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onFormat: () -> Unit,
) {
    val fixed = heightPx > 0
    val height = with(LocalDensity.current) { heightPx.toDp() }
    val numberColor = MaterialTheme.colorScheme.surfaceVariant
    val operatorColor = MaterialTheme.colorScheme.primaryContainer
    val accentColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = if (fixed) Modifier.fillMaxWidth().height(height) else Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Ряды делят высоту поровну, только когда она известна, иначе первый же ряд занял бы весь экран.
        val row = if (fixed) Modifier.weight(1f) else Modifier
        val key = if (fixed) Modifier.fillMaxHeight() else Modifier
        Row(row, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MenuKey(key, Icons.Outlined.Edit, "Написать", operatorColor, onClick = onWrite)
            MenuKey(
                key,
                Icons.Outlined.PhotoCamera,
                "Сфотографировать",
                accentColor,
                MaterialTheme.colorScheme.onPrimary,
                onClick = onPhoto,
            )
        }
        Row(row, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MenuKey(key, Icons.Outlined.PhotoLibrary, "Галерея", operatorColor, onClick = onGallery)
            MenuKey(key, Icons.Outlined.Description, "Документ", operatorColor, onClick = onDocument)
        }
        Row(row, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MenuKey(key, Icons.Outlined.History, "История", numberColor, onClick = onHistory)
            MenuKey(key, Icons.Outlined.Settings, "Настройки", numberColor, onClick = onSettings)
        }
        Row(row, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MenuKey(
                key,
                Icons.Outlined.Tune,
                formatSummary?.let { "Формат: $it" } ?: "Формат решения",
                if (formatSummary != null) operatorColor else numberColor,
                onClick = onFormat,
            )
        }
    }
}

@Composable
private fun RowScope.MenuKey(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    container: Color,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    CalcKey(container, modifier = modifier, onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(26.dp))
            Text(
                label,
                // 15sp: «Сфотографировать» помещается в половину ширины и на узких (360dp) экранах.
                fontSize = 15.sp,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, start = 6.dp, end = 6.dp),
            )
        }
    }
}
