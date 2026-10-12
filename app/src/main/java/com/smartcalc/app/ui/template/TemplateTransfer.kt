package com.smartcalc.app.ui.template

import android.content.Context
import android.content.Intent
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.smartcalc.app.SolutionViewModel
import com.smartcalc.app.data.template.ImportResult
import com.smartcalc.app.data.template.TaskTemplate
import com.smartcalc.app.data.template.TemplateFormat
import com.smartcalc.app.data.template.TemplateImportException
import com.smartcalc.app.data.template.TemplateKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Что можно импортировать: ZIP и JSON из экспорта SmartCalc и Markdown. Тип файла у разных
 * приложений разный, а то и «просто файл», поэтому формат потом определяется по содержимому.
 */
private val IMPORT_TYPES = arrayOf(
    "application/zip", "application/x-zip-compressed", "application/json",
    "text/markdown", "text/x-markdown", "text/plain", "application/octet-stream",
)

private val FORMAT_HINTS = listOf(
    TemplateFormat.ZIP to "ZIP — всё вместе с фото. Для другого телефона с SmartCalc.",
    TemplateFormat.JSON to "JSON — всё с фото одним текстовым файлом. Для программ и других ИИ.",
    TemplateFormat.MARKDOWN to "Markdown — только текст: читать и править в любом редакторе. Фото не сохраняются.",
)

/** Экспорт и импорт шаблонов: сохранить файлом, отправить через «Поделиться», прочитать из файла. */
internal class TemplateTransfer(
    /** Спрашивает формат и сохраняет [templates] файлом; [all] — значит все шаблоны. */
    val save: (templates: List<TaskTemplate>, all: Boolean) -> Unit,
    /** Спрашивает формат и отправляет через «Поделиться». */
    val share: (templates: List<TaskTemplate>, all: Boolean) -> Unit,
    val import: () -> Unit,
)

/** «Шаблон Гурвиц.md» для одного шаблона и «Шаблоны SmartCalc.zip» для нескольких; у базы — «База …». */
internal fun exportFileName(templates: List<TaskTemplate>, format: TemplateFormat, kind: TemplateKind = TemplateKind.TEMPLATES): String {
    val name = when (kind) {
        TemplateKind.TEMPLATES -> templates.singleOrNull()?.let { "Шаблон ${it.name}" } ?: "Шаблоны SmartCalc"
        TemplateKind.LIBRARY -> templates.singleOrNull()?.let { "База ${it.name}" } ?: "База задач SmartCalc"
    }
    return name.replace(Regex("""[\\/:*?"<>|\n]"""), "_").trim().take(60) + "." + format.extension
}

/** Что экспортировать, пока открыто окно выбора формата или системный выбор места. */
private class PendingExport(val ids: ArrayList<String>?, val names: List<TaskTemplate>, val share: Boolean)

/**
 * Экспорт и импорт для экрана шаблонов или базы задач ([kind]). Сам показывает окно выбора формата,
 * поэтому вызывается там, где у экрана могут появиться диалоги. [onImported] получает загруженное —
 * база по нему прикрепляет задачи к шаблону.
 */
@Composable
internal fun rememberTemplateTransfer(
    vm: SolutionViewModel,
    kind: TemplateKind = TemplateKind.TEMPLATES,
    onImported: (suspend (ImportResult) -> Unit)? = null,
): TemplateTransfer {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PendingExport?>(null) }
    // Что сохранять, пока открыт системный выбор места; переживает и пересоздание экрана.
    var saveIds by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }

    val createLaunchers = TemplateFormat.entries.associateWith { format ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mime)) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val ids = saveIds
            scope.launch {
                try {
                    val count = context.contentResolver.openOutputStream(uri)?.use { vm.exportTemplates(ids, it, format, kind) }
                        ?: throw IllegalStateException()
                    toast(context, if (kind == TemplateKind.LIBRARY) "Сохранено разделов: $count" else "Сохранено шаблонов: $count")
                } catch (e: Exception) {
                    toast(context, "Не удалось сохранить файл")
                }
            }
        }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val sourceName = runCatching {
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0)?.take(200) else null
                        }
                    }.getOrNull()
                    context.contentResolver.openInputStream(uri)?.use { vm.importTemplates(it, kind, sourceName) }
                        ?: throw TemplateImportException("Не удалось открыть файл")
                }
                toast(context, importMessage(result, kind))
                onImported?.invoke(result)
            } catch (e: TemplateImportException) {
                // Сообщение про формат Markdown длинное — короткий тост его обрежет.
                Toast.makeText(context, e.message ?: "Не удалось импортировать шаблоны", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                toast(context, "Не удалось открыть файл")
            }
        }
    }

    pending?.let { p ->
        FormatDialog(
            kind = kind,
            onDismiss = { pending = null },
            onChoose = { format ->
                pending = null
                if (p.share) {
                    scope.launch {
                        try {
                            shareFile(context, vm, p.ids, exportFileName(p.names, format, kind), format, kind)
                        } catch (e: Exception) {
                            toast(context, "Не удалось подготовить файл")
                        }
                    }
                } else {
                    saveIds = p.ids
                    createLaunchers.getValue(format).launch(exportFileName(p.names, format, kind))
                }
            },
        )
    }

    return remember(vm, kind) {
        TemplateTransfer(
            save = { templates, all ->
                pending = PendingExport(if (all) null else ArrayList(templates.map { it.id }), templates, share = false)
            },
            share = { templates, all ->
                pending = PendingExport(if (all) null else ArrayList(templates.map { it.id }), templates, share = true)
            },
            import = { openLauncher.launch(IMPORT_TYPES) },
        )
    }
}

@Composable
private fun FormatDialog(kind: TemplateKind, onDismiss: () -> Unit, onChoose: (TemplateFormat) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Формат файла") },
        text = {
            Column {
                if (kind == TemplateKind.LIBRARY) Text(
                    "Экспортируется текущее содержимое. Отметки проверки и предыдущие версии остаются на этом устройстве.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                FORMAT_HINTS.forEach { (format, hint) ->
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onChoose(format) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Файл пишется во временную папку, открытую наружу через FileProvider, и уходит в «Поделиться». */
private suspend fun shareFile(
    context: Context,
    vm: SolutionViewModel,
    ids: List<String>?,
    fileName: String,
    format: TemplateFormat,
    kind: TemplateKind,
) {
    val file = withContext(Dispatchers.IO) {
        // Прежние экспорты уже отправлены — копить их незачем.
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, fileName).also { f -> f.outputStream().use { vm.exportTemplates(ids, it, format, kind) } }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        // Markdown как text/plain: так его принимает больше приложений.
        .setType(if (format == TemplateFormat.MARKDOWN) "text/plain" else format.mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, if (kind == TemplateKind.LIBRARY) "Отправить задачи" else "Отправить шаблоны"))
}

private fun importMessage(r: ImportResult, kind: TemplateKind): String = when (kind) {
    TemplateKind.TEMPLATES -> listOfNotNull(
        "Добавлено шаблонов: ${r.added}".takeIf { r.added > 0 },
        "обновлено: ${r.updated}".takeIf { r.updated > 0 },
    ).joinToString(", ").replaceFirstChar { it.uppercase() }
    // В базе важнее число задач: раздел в файле обычно один.
    TemplateKind.LIBRARY -> listOfNotNull(
        "Добавлено разделов: ${r.added}".takeIf { r.added > 0 },
        "Без изменений: ${r.unchanged}".takeIf { r.unchanged > 0 },
    ).joinToString(". ")
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
