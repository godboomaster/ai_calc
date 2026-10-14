package com.smartcalc.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.smartcalc.app.SolutionViewModel
import com.smartcalc.app.data.api.reasoningCapability
import com.smartcalc.app.data.settings.ApiSettings
import com.smartcalc.app.ui.calculator.ASSISTANT_CODE_LENGTH
import com.smartcalc.app.ui.calculator.isValidAssistantCode
import com.smartcalc.app.data.settings.ReasoningFormat
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SolutionViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val swipeToRestore by vm.swipeToRestore.collectAsState()
    val assistantLock by vm.assistantLock.collectAsState()
    val assistantCode = assistantLock?.code
    val scope = rememberCoroutineScope()
    var codeInput by remember { mutableStateOf("") }
    var codeStatus by remember { mutableStateOf<String?>(null) }

    var loaded by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var supportsImages by remember { mutableStateOf(false) }
    var libraryToolsEnabled by remember { mutableStateOf(false) }
    var thinkingEnabled by remember { mutableStateOf(true) }
    var reasoningFormat by remember { mutableStateOf(ReasoningFormat.AUTO) }
    var reasoningMenu by remember { mutableStateOf(false) }
    var reasoningExpanded by rememberSaveable { mutableStateOf(false) }
    var libraryDetails by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(settings) {
        if (!loaded) {
            baseUrl = settings.baseUrl
            apiKey = settings.apiKey
            model = settings.model
            systemPrompt = settings.systemPrompt
            supportsImages = settings.supportsImages
            libraryToolsEnabled = settings.libraryToolsEnabled
            thinkingEnabled = settings.thinkingEnabled
            reasoningFormat = settings.reasoningFormat
            loaded = true
        }
    }

    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun currentSettings() = ApiSettings(
        baseUrl = baseUrl.trim().ifBlank { ApiSettings.DEFAULT_BASE_URL },
        apiKey = apiKey.trim(),
        model = model.trim().ifBlank { ApiSettings.DEFAULT_MODEL },
        systemPrompt = systemPrompt.trim().ifBlank { ApiSettings.DEFAULT_SYSTEM_PROMPT },
        supportsImages = supportsImages,
        libraryToolsEnabled = libraryToolsEnabled,
        thinkingEnabled = thinkingEnabled,
        reasoningFormat = reasoningFormat,
    )

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Настройки") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Управление",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth().toggleable(
                    value = swipeToRestore,
                    role = Role.Switch,
                    onValueChange = { enabled ->
                        scope.launch { vm.settingsRepo.setSwipeToRestore(enabled) }
                    },
                ).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Возврат к решению свайпом", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Свайп вверх по дисплею калькулятора — как удержание ✦",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Switch(checked = swipeToRestore, onCheckedChange = null)
            }
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Код кнопки ✦", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (assistantCode.isNullOrEmpty()) {
                        "Без кода ✦ видна всегда. С кодом её можно спрятать: наберите код на калькуляторе " +
                            "и нажмите «=». Тот же код и «=» показывают её снова."
                    } else {
                        "Код задан: код и «=» на калькуляторе показывают или прячут ✦. " +
                            "После перезапуска приложения кнопка остаётся такой, какой была."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it.filter(Char::isDigit).take(ASSISTANT_CODE_LENGTH.last) },
                    label = { Text(if (assistantCode.isNullOrEmpty()) "Код из цифр" else "Новый код") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    supportingText = { Text("От ${ASSISTANT_CODE_LENGTH.first} до ${ASSISTANT_CODE_LENGTH.last} цифр") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = isValidAssistantCode(codeInput),
                        onClick = {
                            scope.launch {
                                vm.setAssistantCode(codeInput)
                                codeInput = ""
                                codeStatus = "Код сохранён. Чтобы спрятать ✦, наберите его на калькуляторе и нажмите «=»"
                            }
                        },
                    ) { Text("Сохранить код") }
                    if (!assistantCode.isNullOrEmpty()) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    vm.setAssistantCode("")
                                    codeInput = ""
                                    codeStatus = "Код убран: ✦ видна всегда"
                                }
                            },
                        ) { Text("Убрать код") }
                    }
                }
                codeStatus?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                "OpenAI-совместимый API",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
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
                supportingText = { Text("Например: gpt-4o-mini, kimi-latest, qwen-vl-max, deepseek-flash") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("Системный промпт") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            val reasoning = runCatching { reasoningCapability(currentSettings()) }.getOrNull()
            SettingsGroup {
                if (reasoning?.controlled == true) {
                    SettingsSwitch(
                        title = if (reasoning.canDisable) "Рассуждение модели" else "Подробное рассуждение",
                        checked = thinkingEnabled,
                        onCheckedChange = { thinkingEnabled = it },
                    )
                    SettingsHint("Модель обдумывает ответ. Это может увеличить время и расход токенов. Текст рассуждений не показывается.")
                    if (!reasoning.canDisable) {
                        SettingsHint("Модель рассуждает всегда. Переключатель выбирает минимальную или высокую интенсивность.")
                    }
                } else {
                    Text("Рассуждение модели", style = MaterialTheme.typography.titleMedium)
                    SettingsHint("Используется режим сервера. Приложение не передаёт параметр рассуждения.")
                }
                DisclosureButton("Дополнительные параметры", reasoningExpanded) {
                    reasoningExpanded = !reasoningExpanded
                    reasoningMenu = false
                }
                if (reasoningExpanded) {
                    Text("Формат рассуждения", style = MaterialTheme.typography.titleSmall)
                    Box {
                        OutlinedButton(onClick = { reasoningMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(reasoningFormat.label())
                        }
                        DropdownMenu(expanded = reasoningMenu, onDismissRequest = { reasoningMenu = false }) {
                            ReasoningFormat.entries.forEach { format ->
                                DropdownMenuItem(text = { Text(format.label()) }, onClick = {
                                    reasoningFormat = format
                                    reasoningMenu = false
                                })
                            }
                        }
                    }
                    SettingsHint("Авто определяет формат по модели и адресу API. Меняйте его, только если этого требует документация провайдера.")
                    if (reasoningFormat != ReasoningFormat.AUTO && reasoningFormat != ReasoningFormat.SERVER_DEFAULT) {
                        SettingsHint("Формат должен поддерживаться моделью. Для Qwen используется ответ без потоковой выдачи; reasoning_effort доступен не у всех моделей OpenAI.")
                    }
                } else if (reasoningFormat != ReasoningFormat.AUTO) {
                    SettingsHint("Формат: ${reasoningFormat.label()}")
                }
            }
            SettingsGroup {
                SettingsSwitch("Модель поддерживает изображения", supportsImages) { supportsImages = it }
                SettingsHint("Включайте для модели, которая умеет читать фотографии. Проверьте это в документации провайдера.")
            }
            SettingsGroup {
                SettingsSwitch("Поиск агента в библиотеке", libraryToolsEnabled) { libraryToolsEnabled = it }
                SettingsHint("Агент сам решает, когда искать примеры. Найденные фрагменты и прочитанные тексты передаются выбранному API.")
                DisclosureButton("Как работает поиск", libraryDetails) { libraryDetails = !libraryDetails }
                if (libraryDetails) {
                    SettingsHint("До 4 запросов к модели на один ответ. Поиск может увеличить время и расход токенов.")
                    SettingsHint("Фотографии из библиотеки этим поиском не передаются.")
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            vm.settingsRepo.save(currentSettings())
                            status = "Сохранено"
                            statusIsError = false
                        }
                    },
                    enabled = !busy,
                ) { Text("Сохранить") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            status = null
                            val s = currentSettings()
                            vm.settingsRepo.save(s)
                            status = try {
                                statusIsError = false
                                vm.testConnection(s)
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

            status?.let {
                Text(
                    it,
                    color = if (statusIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            TextButtonReset(onClick = {
                baseUrl = ApiSettings.DEFAULT_BASE_URL
                model = ApiSettings.DEFAULT_MODEL
                systemPrompt = ApiSettings.DEFAULT_SYSTEM_PROMPT
                thinkingEnabled = true
                reasoningFormat = ReasoningFormat.AUTO
            })
        }
    }
}

private fun ReasoningFormat.label(): String = when (this) {
    ReasoningFormat.AUTO -> "Авто"
    ReasoningFormat.THINKING_TYPE -> "DeepSeek / Kimi / GLM / MiMo (thinking)"
    ReasoningFormat.ENABLE_THINKING -> "Qwen (enable_thinking)"
    ReasoningFormat.REASONING_EFFORT -> "OpenAI (reasoning_effort)"
    ReasoningFormat.SERVER_DEFAULT -> "Как задано на сервере"
}

@Composable
private fun TextButtonReset(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Text("Сбросить настройки API")
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun SettingsSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(
            value = checked, role = Role.Switch, onValueChange = onCheckedChange,
        ).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SettingsHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DisclosureButton(title: String, expanded: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.semantics {
        stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто"
    }) {
        Text(title, modifier = Modifier.weight(1f))
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
    }
}
