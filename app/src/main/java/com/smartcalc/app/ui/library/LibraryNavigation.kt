package com.smartcalc.app.ui.library

import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun LibraryTabs(selected: Int, onSearch: () -> Unit, onTasks: () -> Unit, onFiles: () -> Unit) {
    TabRow(selectedTabIndex = selected) {
        Tab(selected = selected == 0, onClick = onSearch, text = { Text("Поиск") })
        Tab(selected = selected == 1, onClick = onTasks, text = { Text("Задачи") })
        Tab(selected = selected == 2, onClick = onFiles, text = { Text("Файлы") })
    }
}
