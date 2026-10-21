package com.smartcalc.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import com.smartcalc.app.ui.components.FormActions
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.smartcalc.app.SolutionViewModel
import com.smartcalc.app.data.api.ApiAdapter
import com.smartcalc.app.data.api.apiAdapter
import com.smartcalc.app.data.api.displayName
import com.smartcalc.app.data.api.providerName
import com.smartcalc.app.data.settings.ApiConnection
import com.smartcalc.app.data.settings.ApiFormat
import com.smartcalc.app.data.settings.ApiSettings
import com.smartcalc.app.data.settings.withConnection
import com.smartcalc.app.ui.components.QuickHideButton
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/** Одно подключение: провайдер, ключ и модель, а также что модель умеет — фото и рассуждение. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    vm: SolutionViewModel,
    editor: ConnectionEditorViewModel,
    onBack: () -> Unit,
    onOpenConnection: (String) -> Unit,
) {
    val connections by vm.connections.collectAsState()
    val settings by vm.settings.collectAsState()
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }

    val id = editor.id
    val loaded = editor.loaded
    var name by editor::name
    var baseUrl by editor::baseUrl
    var apiKey by editor::apiKey
    var model by editor::model
    var apiFormat by editor::apiFormat
    var supportsImages by editor::supportsImages
    var thinkingEnabled by editor::thinkingEnabled
    var formatExpanded by rememberSaveable { mutableStateOf(false) }
    var formatMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(connections) {
        connections?.let(editor::loadOnce)
    }

    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val list = connections
    val isNew = list?.items?.none { it.id == id } ?: true
    val isActive = list?.active?.id == id

    fun draft() = editor.toConnection()

    // Новое подключение создают, чтобы им пользоваться: после сохранения оно становится активным.
    suspend fun save() = vm.settingsRepo.saveConnection(draft(), activate = isNew)

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(if (isNew) "Новое подключение" else "Подключение") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            },
            actions = { QuickHideButton() },
        )
        if (!loaded) return@Column
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isActive) {
                SettingsHint("Активное подключение: через него идут решения, дополнения и распознавание материалов библиотеки.")
            } else if (!isNew) {
                OutlinedButton(onClick = { vm.selectConnection(id) }) { Text("Сделать активным") }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название") },
                supportingText = { Text("Необязательно — иначе используем название провайдера") },
                placeholder = { Text(providerName(baseUrl.ifBlank { ApiSettings.DEFAULT_BASE_URL }) ?: "Например: DeepSeek") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                supportingText = { Text("Адрес API провайдера. Пустое поле — ${ApiSettings.DEFAULT_BASE_URL}") },
                placeholder = { Text(ApiSettings.DEFAULT_BASE_URL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API-ключ") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Модель") },
                placeholder = { Text(ApiSettings.DEFAULT_MODEL) },
                singleLine = true,
                supportingText = { Text("ID модели у провайдера, например: deepseek-flash, gpt-5-mini, qwen-plus") },
                modifier = Modifier.fillMaxWidth(),
            )

            val adapter = runCatching { apiAdapter(settings.withConnection(draft())) }.getOrNull()
            SettingsGroup {
                Text("Формат API", style = MaterialTheme.typography.titleMedium)
                SettingsHint(if (baseUrl.isBlank() && apiFormat == ApiFormat.AUTO) "Определится по адресу" else formatLine(apiFormat, adapter))
                val suggested = adapter?.suggestedFormat
                if (apiFormat == ApiFormat.AUTO && suggested != null) {
                    SettingsHint("Модель не знакома приложению. Если она умеет рассуждать, выберите формат «${suggested.label()}».")
                }
                Box {
                    OutlinedButton(onClick = { formatMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("${apiFormat.label()} ▾")
                    }
                    DropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false }) {
                        ApiFormat.entries.forEach { format ->
                            DropdownMenuItem(text = { Text(format.label()) }, onClick = {
                                apiFormat = format
                                formatMenu = false
                            })
                        }
                    }
                }
                DisclosureButton("Подробнее о формате", formatExpanded) { formatExpanded = !formatExpanded }
                if (formatExpanded) {
                    SettingsHint("Автоматически формат выбирается по адресу. Для шлюза или прокси выберите формат провайдера, к модели которого идёт запрос.")
                    if (adapter?.controlsReasoning == true) {
                        SettingsHint("Передаётся при включённом рассуждении: ${adapter.reasoningOn}\nПри выключенном: ${adapter.reasoningOff}")
                    }
                }
            }
            SettingsGroup {
                if (adapter?.controlsReasoning == true) {
                    SettingsSwitch(
                        title = if (adapter.canDisableReasoning) "Рассуждение модели" else "Подробное рассуждение",
                        checked = thinkingEnabled,
                        onCheckedChange = { thinkingEnabled = it },
                    )
                    SettingsHint("Модель обдумывает ответ. Это может увеличить время и расход токенов. Текст рассуждений не показывается.")
                    if (!adapter.canDisableReasoning) {
                        SettingsHint("Модель рассуждает всегда. Переключатель выбирает минимальную или высокую интенсивность.")
                    }
                } else {
                    Text("Рассуждение модели", style = MaterialTheme.typography.titleMedium)
                    SettingsHint("Приложение не передаёт параметр рассуждения: используется режим, заданный на сервере.")
                }
            }
            SettingsGroup {
                SettingsSwitch("Фото", supportsImages) { supportsImages = it }
                SettingsHint("Разрешить отправку изображений. Включайте только для модели с поддержкой фото.")
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            status = null
                            status = try {
                                statusIsError = false
                                vm.testConnection(settings.withConnection(draft()))
                            } catch (e: Exception) {
                                statusIsError = true
                                e.message ?: "Ошибка соединения"
                            }
                            busy = false
                        }
                    },
                    enabled = !busy,
                ) { Text("Проверить соединение") }
                if (busy) CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp))
            }

            SettingsStatus(status, statusIsError)

            if (!isNew) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Копия удобна для той же учётной записи с другой моделью.
                    OutlinedButton(onClick = {
                        scope.launch {
                            val source = draft()
                            val copy = source.copy(id = UUID.randomUUID().toString(), name = "${source.displayName} — копия")
                            vm.settingsRepo.saveConnection(copy)
                            onOpenConnection(copy.id)
                        }
                    }) { Text("Копировать") }
                    if ((list?.items?.size ?: 0) > 1) {
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Удалить") }
                    }
                }
            }
        }
        FormActions {
            if (status?.startsWith("Сохранено") == true) SettingsStatus(status, false)
                Button(
                    onClick = {
                        scope.launch {
                            val created = isNew
                            save()
                            status = if (created) "Сохранено и выбрано для запросов" else "Сохранено"
                            statusIsError = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Сохранить") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить подключение «${draft().displayName}»?") },
            text = {
                Text(if (isActive) "Ключ API этого подключения будет удалён. Запросы пойдут через другое сохранённое подключение."
                    else "Ключ API этого подключения будет удалён.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        vm.settingsRepo.deleteConnection(id)
                        onBack()
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

internal fun ApiFormat.label(): String = when (this) {
    ApiFormat.AUTO -> "Автоматически"
    ApiFormat.THINKING_TYPE -> "DeepSeek, Kimi, GLM, MiMo"
    ApiFormat.ENABLE_THINKING -> "Qwen"
    ApiFormat.REASONING_EFFORT -> "OpenAI"
    ApiFormat.SERVER_DEFAULT -> "Другой OpenAI-совместимый"
}

private fun formatLine(format: ApiFormat, adapter: ApiAdapter?): String = when {
    format != ApiFormat.AUTO -> "Выбран вручную: ${format.label()}"
    adapter?.provider != null -> "Автоматически: ${adapter.provider}"
    else -> "Автоматически: формат не определён"
}

/** Модель и адрес — чтобы различать подключения с одинаковым названием. */
internal fun ApiConnection.summary(): String =
    listOfNotNull(model.takeIf { it.isNotBlank() }, baseUrl.trim().toHttpUrlOrNull()?.host).joinToString(" · ")
