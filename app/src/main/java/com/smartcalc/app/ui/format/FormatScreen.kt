package com.smartcalc.app.ui.format

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import com.smartcalc.app.ui.components.FormActions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.smartcalc.app.SolutionViewModel
import com.smartcalc.app.data.ImageUtils
import com.smartcalc.app.ui.components.ExplanationCard
import kotlinx.coroutines.launch
import java.io.File

/** Готовые варианты, которые дописываются в требования одним нажатием. */
private val templates = listOf(
    "Дано / Найти / Решение / Ответ" to "Оформи как в школе: Дано, Найти, Решение, Ответ.",
    "По действиям" to "Решай по действиям, с коротким пояснением к каждому действию.",
    "Кратко" to "Кратко: только вычисления и ответ, без лишних объяснений.",
    "Простыми словами" to "Объясняй простыми словами, как ученику 5 класса.",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FormatScreen(vm: SolutionViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { vm.format.value }

    var text by remember { mutableStateOf(saved.text) }
    // Старый образец показываем по пути, только что выбранный — из памяти до сохранения.
    var savedSamplePath by remember { mutableStateOf(saved.samplePath) }
    var newSample by remember { mutableStateOf<ByteArray?>(null) }
    val hasSample = newSample != null || savedSamplePath != null

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    newSample = ImageUtils.compressUri(context, uri)
                } catch (e: Exception) {
                    Toast.makeText(context, "Не удалось открыть изображение", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun pickSample() = galleryLauncher.launch(
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
    )

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text("Оформление") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExplanationCard(
                title = "Для всех новых ответов",
                text = "Задайте структуру, стиль записи и подробность ответа. " +
                    "После сохранения эти настройки действуют до сброса.",
                details = "Например, добавьте разделы «Дано / Найти / Решение / Ответ» " +
                    "или попросите короткие пояснения к каждому шагу.\n\n" +
                    "Метод для конкретной задачи можно задать через «По образцу»: " +
                    "там сохраняются требования и примеры решения.",
            )

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Требования к оформлению") },
                placeholder = {
                    Text("Например: Дано / Найти / Решение / Ответ. К каждому шагу — короткое пояснение.")
                },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                templates.forEach { (label, value) ->
                    SuggestionChip(
                        onClick = { text = if (text.isBlank()) value else text.trimEnd() + "\n" + value },
                        label = { Text(label) },
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Образец оформления",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Фото или скриншот решения, оформленного так, как нужно. " +
                            "ИИ повторит его структуру и стиль записи.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (hasSample) {
                        AsyncImage(
                            model = newSample ?: savedSamplePath?.let { File(it) },
                            contentDescription = "Образец оформления",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .padding(top = 12.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { pickSample() }) { Text("Заменить") }
                            TextButton(onClick = {
                                newSample = null
                                savedSamplePath = null
                            }) { Text("Убрать") }
                        }
                    } else {
                        OutlinedButton(
                            onClick = { pickSample() },
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Icon(
                                Icons.Filled.PhotoLibrary,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text("Выбрать из галереи", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }

            if (saved.isSet) {
                OutlinedButton(
                    onClick = {
                        vm.clearFormat()
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Сбросить оформление") }
            }
        }
        FormActions {
            Button(
                onClick = {
                    scope.launch {
                        vm.saveFormat(text, newSample, keepSample = savedSamplePath != null)
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить") }

        }
    }
}
