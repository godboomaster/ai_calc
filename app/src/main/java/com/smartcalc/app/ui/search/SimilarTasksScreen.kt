package com.smartcalc.app.ui.search

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartcalc.app.SolutionViewModel
import com.smartcalc.app.data.request.SimilarChoice
import com.smartcalc.app.data.request.TaskInput
import com.smartcalc.app.data.search.LibraryCandidate
import com.smartcalc.app.ui.components.FormActions
import com.smartcalc.app.ui.components.QuickHideButton
import com.smartcalc.app.ui.components.ReadableImage
import com.smartcalc.app.ui.components.ReadingDialog
import com.smartcalc.app.ui.math.MathText
import com.smartcalc.app.ui.template.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Перед отправкой: похожие задачи библиотеки, отмеченные уйдут модели примерами вместе с задачей.
 * Экран закрывается сам, как только задачу забрали — отправили, отменили или скрыли помощника.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimilarTasksScreen(vm: SolutionViewModel, onClose: () -> Unit) {
    val choice by vm.similarChoice.collectAsState()
    LaunchedEffect(choice == null) { if (choice == null) onClose() }
    BackHandler(onBack = vm::cancelSimilar)
    val current = choice ?: return
    var opened by remember(current.id) { mutableStateOf<LibraryCandidate?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Похожие задачи") },
            navigationIcon = {
                IconButton(onClick = vm::cancelSimilar) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад, не отправляя")
                }
            },
            actions = { QuickHideButton() },
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "task") { TaskSummary(current) }
            when (current.status) {
                SimilarChoice.Status.SEARCHING -> item(key = "searching") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text("Модель читает задачу, приложение ищет похожие в библиотеке…",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                SimilarChoice.Status.FAILED -> item(key = "failed") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(current.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = vm::retrySimilar) { Text("Повторить подбор") }
                    }
                }
                SimilarChoice.Status.READY -> {
                    item(key = "hint") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Отметьте задачи, которые решаются так же. Они уйдут модели примерами вместе с вашей задачей.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (current.approximate) Text("Модель не ответила, поэтому задачи подобраны только по словам условия.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(current.candidates, key = { it.id }) { candidate ->
                        CandidateCard(candidate, candidate.id in current.selected,
                            onToggle = { vm.toggleSimilar(candidate.id) }, onOpen = { opened = candidate })
                    }
                }
            }
        }
        FormActions {
            val count = current.selected.size
            Button(onClick = { vm.sendSimilar(withExamples = true) }, enabled = count > 0, modifier = Modifier.fillMaxWidth()) {
                Text(if (count == 0) "Отправить с отмеченными" else "Отправить с отмеченными ($count)")
            }
            OutlinedButton(onClick = { vm.sendSimilar(withExamples = false) }, modifier = Modifier.fillMaxWidth()) {
                Text("Отправить без примеров")
            }
        }
    }
    opened?.let { CandidateDialog(it, onClose = { opened = null }) }
}

/** Что уходит: текст задачи или фото; у фото — ещё и то, что прочитала модель. */
@Composable
private fun TaskSummary(choice: SimilarChoice) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ваша задача", style = MaterialTheme.typography.labelLarge)
            when (val input = choice.input) {
                is TaskInput.Text -> MathText(input.problem, maxLines = 6)
                else -> {
                    if (input is TaskInput.Document) Text("Задача из документа")
                    TaskPhoto(choice)
                    if (choice.recognized.isNotBlank()) {
                        Text("Модель прочитала:", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MathText(choice.recognized, maxLines = 4, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Уменьшенное фото задачи: исходный снимок с камеры или уже прочитанная картинка из галереи и документа. */
@Composable
private fun TaskPhoto(choice: SimilarChoice) {
    var photo by remember(choice.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(choice.id, choice.prepared != null) {
        val bytes = (choice.input as? TaskInput.Image)?.jpeg
            ?: choice.prepared?.images?.firstOrNull()?.let { Base64.decode(it, Base64.DEFAULT) }
        photo = bytes?.let { thumbnail(it) }
    }
    photo?.let {
        Image(it, contentDescription = "Фото задачи", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp))
    }
}

private suspend fun thumbnail(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 900) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?.asImageBitmap()
}

@Composable
private fun CandidateCard(candidate: LibraryCandidate, checked: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val example = candidate.example
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
                .padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
            Column(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(candidate.section, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                MathText(example.condition.ifBlank { "Условие на фото" }, maxLines = 4)
                if (example.answer.isNotBlank()) {
                    MathText("Ответ: ${example.answer}", maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                example.library?.let {
                    Text(it.verification.label, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onOpen) { Text("Посмотреть решение") }
            }
        }
    }
}

@Composable
private fun CandidateDialog(candidate: LibraryCandidate, onClose: () -> Unit) {
    val example = candidate.example
    ReadingDialog("Похожая задача", onBack = onClose) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(candidate.section, style = MaterialTheme.typography.titleMedium)
            Text("Условие", style = MaterialTheme.typography.labelLarge)
            if (example.condition.isNotBlank()) MathText(example.condition)
            example.conditionImages.forEach { ReadableImage(it, "Фото условия", Modifier.fillMaxWidth()) }
            if (example.solution.isNotBlank() || example.solutionImages.isNotEmpty()) {
                Text("Решение", style = MaterialTheme.typography.labelLarge)
                if (example.solution.isNotBlank()) MathText(example.solution)
                example.solutionImages.forEach { ReadableImage(it, "Фото решения", Modifier.fillMaxWidth()) }
            }
            if (example.answer.isNotBlank()) {
                Text("Ответ", style = MaterialTheme.typography.labelLarge)
                MathText(example.answer)
            }
        }
    }
}
