package com.smartcalc.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.smartcalc.app.SolutionViewModel
import com.smartcalc.app.data.api.displayName
import com.smartcalc.app.data.settings.ApiConnection
import com.smartcalc.app.data.settings.ApiSettings
import com.smartcalc.app.ui.calculator.ASSISTANT_CODE_LENGTH
import com.smartcalc.app.ui.calculator.isValidAssistantCode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SolutionViewModel, onBack: () -> Unit, onOpenConnection: (String?) -> Unit, openConnections: Boolean = false) {
    val settings by vm.settings.collectAsState()
    val connections by vm.connections.collectAsState()
    val swipeToRestore by vm.swipeToRestore.collectAsState()
    val assistantLock by vm.assistantLock.collectAsState()
    val assistantCode = assistantLock?.code
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var connectionOffset by remember { mutableStateOf<Int?>(null) }
    var connectionShortcutHandled by rememberSaveable { mutableStateOf(false) }
    var promptExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openConnections, connectionOffset) {
        if (openConnections && !connectionShortcutHandled) connectionOffset?.let {
            scroll.scrollTo(it)
            connectionShortcutHandled = true
        }
    }
    var codeInput by remember { mutableStateOf("") }
    var codeStatus by remember { mutableStateOf<String?>(null) }

    var loaded by remember { mutableStateOf(false) }
    var systemPrompt by remember { mutableStateOf("") }
    var libraryToolsEnabled by remember { mutableStateOf(false) }
    var libraryDetails by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(settings) {
        if (!loaded) {
            systemPrompt = settings.systemPrompt
            libraryToolsEnabled = settings.libraryToolsEnabled
            loaded = true
        }
    }

    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
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
                .weight(1f)
                .verticalScroll(scroll)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection("Управление")
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Column(Modifier.onGloballyPositioned { connectionOffset = it.positionInParent().y.roundToInt() }) { SettingsSection("Подключения") }
            connections?.let { list ->
                SettingsGroup {
                    list.items.forEach { connection ->
                        ConnectionRow(
                            connection,
                            active = connection.id == list.active.id,
                            onSelect = { vm.selectConnection(connection.id) },
                            onEdit = { onOpenConnection(connection.id) },
                        )
                    }
                    SettingsHint("Через выбранное подключение идут решения, дополнения и распознавание материалов библиотеки. " +
                        "Переключать его можно и в режиме помощника.")
                }
            }
            OutlinedButton(onClick = { onOpenConnection(null) }) { Text("Добавить подключение") }

            SettingsSection("Решение")
            DisclosureButton("Системный промпт", promptExpanded) { promptExpanded = !promptExpanded }
            if (promptExpanded) OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("Системный промпт") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            SettingsGroup {
                SettingsSwitch("Поиск агента в библиотеке", libraryToolsEnabled) { libraryToolsEnabled = it }
                SettingsHint("Агент сам решает, когда искать примеры. Найденные фрагменты и прочитанные тексты передаются выбранному API.")
                DisclosureButton("Как работает поиск", libraryDetails) { libraryDetails = !libraryDetails }
                if (libraryDetails) {
                    SettingsHint("До 4 запросов к модели на один ответ. Поиск может увеличить время и расход токенов.")
                    SettingsHint("Фотографии из библиотеки этим поиском не передаются.")
                }
            }
            if (promptExpanded) OutlinedButton(onClick = { systemPrompt = ApiSettings.DEFAULT_SYSTEM_PROMPT }) { Text("Сбросить промпт") }
            SettingsStatus(status, statusIsError)
                Button(onClick = {
                    scope.launch {
                        vm.settingsRepo.saveSolving(systemPrompt.trim().ifBlank { ApiSettings.DEFAULT_SYSTEM_PROMPT }, libraryToolsEnabled)
                        status = "Сохранено"
                        statusIsError = false
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить настройки решения") }
        }
    }
}

@Composable
private fun ConnectionRow(connection: ApiConnection, active: Boolean, onSelect: () -> Unit, onEdit: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = active, onClick = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(connection.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(connection.summary(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = "Изменить подключение «${connection.displayName}»")
        }
    }
}
