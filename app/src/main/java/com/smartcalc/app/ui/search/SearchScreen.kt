package com.smartcalc.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.smartcalc.app.SolutionViewModel
import com.smartcalc.app.data.library.LibraryVerification
import com.smartcalc.app.data.search.FoundSource
import com.smartcalc.app.ui.template.label
import com.smartcalc.app.data.search.FoundSolution
import com.smartcalc.app.data.search.SearchHit
import com.smartcalc.app.data.search.TaskSearch
import com.smartcalc.app.ui.math.MathText
import com.smartcalc.app.ui.library.LibraryTabs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Не набирать ли ещё: поиск идёт после короткой паузы, а не на каждую букву. */
private const val TYPING_PAUSE_MS = 150L

/**
 * Поиск готовых решений по базе задач, примерам шаблонов и истории — без ИИ и без сети. Набирают числа
 * из условия, слова (что дано, что найти) и выбирают тип. Результат открывается для чтения;
 * перенос на калькулятор выполняется отдельным действием. [initialQuery] — числа, набранные на калькуляторе перед поиском.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    vm: SolutionViewModel,
    initialQuery: String,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenFiles: () -> Unit,
    onAddMaterial: () -> Unit,
    onOpenSolution: () -> Unit,
    onSource: (com.smartcalc.app.data.library.source.SourceSegmentRef) -> Unit,
) {
    // null — список ещё собирается: «искать не в чем» до этого не показываем.
    val entries = vm.searchEntries.collectAsState().value
    val library by vm.library.collectAsState()
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var hits by remember { mutableStateOf<List<SearchHit>?>(null) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val types = remember(entries) { entries.orEmpty().mapNotNull { it.type }.distinct() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(entries, query, type) {
        val all = entries ?: return@LaunchedEffect
        if (hits != null) delay(TYPING_PAUSE_MS)
        hits = withContext(Dispatchers.Default) { TaskSearch.search(all, query, type) }
    }
    val selected = entries?.firstOrNull { it.key == selectedKey }
    if (selected != null) {
        TaskPreviewScreen(vm, selected, onBack = { selectedKey = null }, onUse = onOpenSolution, onSource = onSource)
        return
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text("Библиотека") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            },
        )
        LibraryTabs(0, onSearch = {}, onTasks = onOpenLibrary, onFiles = onOpenFiles)
        OutlinedButton(onClick = onAddMaterial, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Добавить материал")
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Числа или слова из условия") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Очистить")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        if (types.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(selected = type == null, onClick = { type = null }, label = { Text("Все") })
                }
                items(types) { t ->
                    FilterChip(
                        selected = type == t,
                        onClick = { type = if (type == t) null else t },
                        label = { Text(t, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        val found = hits
        when {
            found == null -> Unit
            entries.isNullOrEmpty() -> EmptyBase(library.any { it.examples.isNotEmpty() }, onOpenLibrary)
            found.isEmpty() -> Text(
                "Ничего не найдено. Попробуйте меньше слов или только числа из условия.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (library.isEmpty()) {
                    item {
                        Text(
                            "Поиск включает задачи библиотеки, примеры из «По образцу» и историю решений.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(found, key = { it.entry.key }) { hit ->
                    HitCard(hit, enabled = true) {
                        keyboard?.hide()
                        selectedKey = hit.entry.key
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyBase(hasLibraryExamples: Boolean, onOpenLibrary: () -> Unit) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (hasLibraryExamples) "Нет доступных для поиска задач. Проверьте отметки «Отклонено» и переключатели «Использовать в поиске» в базе."
            else "Пока нет готовых решений. Добавьте материал в библиотеку или решите задачу с ИИ — история тоже участвует в поиске.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onOpenLibrary) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
            Text("Мои задачи", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Найденная задача: откуда и какого типа, условие, ответ и что из запроса совпало. */
@Composable
private fun HitCard(hit: SearchHit, enabled: Boolean, onClick: () -> Unit) {
    val e = hit.entry
    val answer = remember(e) { FoundSolution.of(e).latestAnswer }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            listOfNotNull(e.source.label, e.type).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (e.source == FoundSource.BASE) {
            Text(
                "Исходный пример: " + (e.library?.verification ?: LibraryVerification.UNVERIFIED).label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (e.history?.followUps?.isNotEmpty() == true) Text(
                "Дополнения ИИ не проверены",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 4.dp)) {
            e.conditionImages.firstOrNull()?.let { path ->
                AsyncImage(
                    model = File(path),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            }
            MathText(
                e.condition.ifBlank { "Задача с фото" },
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                modifier = Modifier.weight(1f),
            )
        }
        MathText(
            "= $answer",
            fontSize = 20.sp,
            maxLines = 2,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (hit.matched.isNotEmpty()) {
            Text(
                "Совпало: " + hit.matched.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
