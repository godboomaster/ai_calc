package com.smartcalc.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/** Экстренное скрытие помощника; AppRoot задаёт его, только пока код задан и набран. */
internal val LocalQuickHide = compositionLocalOf<(() -> Unit)?> { null }

/** Одно нажатие — обычный калькулятор без ✦. Без кода или со скрытым помощником кнопки нет. */
@Composable
fun QuickHideButton(tint: Color = LocalContentColor.current) {
    val hide = LocalQuickHide.current ?: return
    IconButton(onClick = hide) {
        Icon(Icons.Outlined.VisibilityOff, contentDescription = "Скрыть помощника", tint = tint)
    }
}
