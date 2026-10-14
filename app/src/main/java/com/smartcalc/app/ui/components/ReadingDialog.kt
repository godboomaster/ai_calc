package com.smartcalc.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration

internal val LocalReadingHeight = compositionLocalOf { Dp.Unspecified }

/** A full-screen reader over the current page, preserving its scroll and without transitions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadingDialog(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // Older Compose Dialog measures against the display rather than the usable window.
    // Use the host's already-inset content height, also correct in split-screen mode.
    val height = LocalReadingHeight.current.takeIf { it != Dp.Unspecified }
        ?: LocalConfiguration.current.screenHeightDp.dp
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setWindowAnimations(0)
            }
        }
        Surface(Modifier.fillMaxWidth().height(height)) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0), title = { Text(title, maxLines = 2) }, navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                })
                content()
            }
        }
    }
}
