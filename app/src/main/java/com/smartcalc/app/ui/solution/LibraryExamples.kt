package com.smartcalc.app.ui.solution

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smartcalc.app.data.library.LibraryVerification
import com.smartcalc.app.data.library.agent.LibraryAgentReport
import com.smartcalc.app.data.library.agent.LibraryExampleSnapshot
import com.smartcalc.app.data.library.source.SegmentKind
import com.smartcalc.app.ui.math.MathText

/** Stable, read-only snapshots. Opening them never replaces the active solution. */
@Composable internal fun LibraryExamples(report: LibraryAgentReport?) {
    if (report == null || report.events.isEmpty() && report.examples.isEmpty()) return
    var selected by remember(report) { mutableStateOf<LibraryExampleSnapshot?>(null) }
    val unavailable = report.events.any { it.status == "unavailable" }
    if (unavailable) Text("Библиотека была недоступна при поиске.", style = MaterialTheme.typography.bodySmall)
    if (report.examples.isEmpty()) {
        val text = when {
            unavailable -> null
            report.events.any { it.status == "needs_image" } -> "Для найденного примера нужно прочитать фотографии; они не передавались."
            report.events.any { it.status in setOf("invalid_arguments", "invalid_tool", "stale", "not_allowed", "too_large", "context_limit", "read_limit") } -> "Часть обращений к библиотеке не выполнена."
            report.events.any { it.status == "matches" } -> "Агент нашёл примеры, но не прочитал их."
            else -> "Подходящих примеров в библиотеке не найдено."
        }
        text?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    } else {
        Text("Прочитанные примеры", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        report.examples.forEachIndexed { index, example ->
            TextButton(onClick = { selected = example }) { Text("${index + 1}. ${example.section} · версия ${example.version}") }
        }
    }
    selected?.let { example ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(example.section) },
            text = {
                Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Версия ${example.version} · " + when (example.verification) {
                        LibraryVerification.VERIFIED_BY_USER -> "Проверено пользователем"
                        LibraryVerification.UNVERIFIED -> "Не проверено"
                        LibraryVerification.REJECTED -> "Отклонено"
                    })
                    Text("Условие", style = MaterialTheme.typography.labelLarge); MathText(example.condition)
                    Text("Авторское решение", style = MaterialTheme.typography.labelLarge); MathText(example.solution)
                    if (example.answer.isNotBlank()) { Text("Ответ", style = MaterialTheme.typography.labelLarge); MathText(example.answer) }
                    Text("Источник", style = MaterialTheme.typography.labelLarge)
                    if (example.sources.isEmpty()) Text(example.originName?.takeIf { it.isNotBlank() }
                        ?: if (example.origin == "MANUAL") "Ручной ввод в библиотеку" else "Раздел библиотеки")
                    example.sources.forEach { source -> Text(source.name + " · " + when (source.kind) {
                        SegmentKind.PDF_PAGE -> "страница ${source.ordinal}"
                        SegmentKind.DOCUMENT_BLOCK -> "фрагмент ${source.ordinal}"
                        SegmentKind.IMAGE -> "изображение ${source.ordinal}"
                    }) }
                    if (example.imagesNotSent) Text("В карточке есть фотографии. Из этого примера агенту передан только текст.", style = MaterialTheme.typography.bodySmall)
                    Text("Сохранённый снимок прочитанного примера. Запросов к API в этом ответе: ${report.posts}.", style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Закрыть") } })
    }
}
