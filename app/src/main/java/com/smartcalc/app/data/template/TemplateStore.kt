package com.smartcalc.app.data.template

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Решённая задача-образец: условие и решение — текстом и/или картинками (пути к файлам). */
@Serializable
data class TemplateExample(
    val condition: String = "",
    val conditionImages: List<String> = emptyList(),
    val solution: String = "",
    val solutionImages: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = condition.isBlank() && solution.isBlank() && conditionImages.isEmpty() && solutionImages.isEmpty()
}

/**
 * Шаблон заданий: требования к задачам одного типа и примеры их решения. ИИ получает его вместе
 * с новой задачей и решает её так же.
 */
@Serializable
data class TaskTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val requirements: String = "",
    val examples: List<TemplateExample> = emptyList(),
    val updated: Long = System.currentTimeMillis(),
)

/** Картинка в редакторе шаблона: уже сохранённая ([path]) или только что выбранная ([bytes]). */
class ImageDraft(val path: String? = null, val bytes: ByteArray? = null)

class ExampleDraft(
    val condition: String,
    val conditionImages: List<ImageDraft>,
    val solution: String,
    val solutionImages: List<ImageDraft>,
)

class TemplateStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(context.filesDir, "templates.json")
    private val imagesDir get() = File(context.filesDir, "templates").apply { mkdirs() }
    private val mutex = Mutex()

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<TaskTemplate>> = _items

    private fun load(): List<TaskTemplate> = try {
        if (file.exists()) json.decodeFromString<List<TaskTemplate>>(file.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Сохраняет шаблон из редактора: новые картинки пишутся в файлы, картинки, убранные из шаблона,
     * удаляются. [id] — null для нового шаблона.
     */
    suspend fun save(id: String?, name: String, requirements: String, examples: List<ExampleDraft>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                fun store(images: List<ImageDraft>) = images.mapNotNull { img ->
                    img.path ?: img.bytes?.let { bytes ->
                        File(imagesDir, "${UUID.randomUUID()}.jpg").apply { writeBytes(bytes) }.absolutePath
                    }
                }
                val template = TaskTemplate(
                    id = id ?: UUID.randomUUID().toString(),
                    name = name.trim(),
                    requirements = requirements.trim(),
                    examples = examples.map {
                        TemplateExample(it.condition.trim(), store(it.conditionImages), it.solution.trim(), store(it.solutionImages))
                    }.filterNot { it.isEmpty },
                )
                val old = _items.value.firstOrNull { it.id == template.id }
                old?.let { deleteImages(it, keep = imagesOf(template)) }
                val updated = listOf(template) + _items.value.filterNot { it.id == template.id }
                file.writeText(json.encodeToString(updated))
                _items.value = updated
            }
        }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _items.value.firstOrNull { it.id == id }?.let { deleteImages(it, keep = emptySet()) }
            val updated = _items.value.filterNot { it.id == id }
            file.writeText(json.encodeToString(updated))
            _items.value = updated
        }
    }

    suspend fun readImage(path: String): ByteArray? = withContext(Dispatchers.IO) {
        File(path).takeIf { it.exists() }?.readBytes()
    }

    private fun imagesOf(t: TaskTemplate) = t.examples.flatMap { it.conditionImages + it.solutionImages }.toSet()

    private fun deleteImages(t: TaskTemplate, keep: Set<String>) {
        (imagesOf(t) - keep).forEach { File(it).delete() }
    }
}
