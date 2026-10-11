package com.smartcalc.app.data.history

import android.content.Context
import com.smartcalc.app.data.plot.Plot
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
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
data class SolutionStep(
    val title: String,
    val content: String,
    /** График к шагу, если задача просит его построить. */
    val plot: Plot? = null,
    /** Схема или чертёж к шагу — SVG-код от модели. */
    val svg: String? = null,
)

/** Дополнительный вопрос к решённой задаче и ответ на него. */
@Serializable
data class FollowUp(
    val question: String,
    val answer: String,
    val steps: List<SolutionStep> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    /** Название шаблона, с которым ушло дополнение. */
    val template: String? = null,
)

@Serializable
data class Solution(
    val id: String = UUID.randomUUID().toString(),
    val problem: String,
    val answer: String,
    val steps: List<SolutionStep> = emptyList(),
    val imagePath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    /** Подпись формата решения, если задача решалась в формате пользователя. */
    val format: String? = null,
    /** Название шаблона заданий, если задача решалась по нему. */
    val template: String? = null,
    /** Шаблон цепочки: с ним уходят и дополнения к ответу. */
    val templateId: String? = null,
    /** Дополнения по порядку: каждое — вопрос после ответа и ответ на него. */
    val followUps: List<FollowUp> = emptyList(),
) {
    /** Последний вопрос в цепочке — исходная задача или последнее дополнение. */
    val latestQuestion: String get() = followUps.lastOrNull()?.question ?: problem

    /** Ответ, который показывает дисплей: на последний вопрос. */
    val latestAnswer: String get() = followUps.lastOrNull()?.answer ?: answer

    /** Шаги последнего ответа — их открывают цифры калькулятора. */
    val latestSteps: List<SolutionStep> get() = followUps.lastOrNull()?.steps ?: steps
}

class HistoryStore internal constructor(private val filesDir: File) {

    constructor(context: Context) : this(context.filesDir)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = File(filesDir, "history.json")
    private val imagesDir get() = File(filesDir, "images").apply { mkdirs() }
    private val mutex = Mutex()

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Solution>> = _items

    private fun load(): List<Solution> = try {
        if (file.exists()) json.decodeFromString<List<Solution>>(file.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    suspend fun add(solution: Solution) = withContext(Dispatchers.IO) {
        mutex.withLock {
            upsert(solution)
        }
    }

    /** Решение с новым дополнением: заменяет старую запись и поднимается наверх истории. */
    suspend fun update(solution: Solution, requireExisting: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (requireExisting && _items.value.none { it.id == solution.id }) return@withLock false
            upsert(solution)
            true
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val imagePath = _items.value.firstOrNull { it.id == id }?.imagePath
            val updated = _items.value.filterNot { it.id == id }
            writeAtomically(updated)
            _items.value = updated
            imagePath?.let(::ownedImage)?.delete()
        }
    }

    suspend fun saveImage(jpeg: ByteArray, stableId: String? = null): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val id = stableId?.let(::canonicalUuid) ?: UUID.randomUUID().toString()
            val image = File(imagesDir, "$id.jpg")
            writeAtomically(image, File(imagesDir, "$id.jpg.tmp"), jpeg)
            image.absolutePath
        }
    }

    suspend fun removeImageIfUnreferenced(path: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val image = ownedImage(path) ?: return@withLock
            val referenced = _items.value.any { solution ->
                solution.imagePath?.let { runCatching { File(it).canonicalFile == image }.getOrDefault(false) } == true
            }
            if (!referenced) image.delete()
        }
    }

    private fun upsert(solution: Solution) {
        val updated = listOf(solution) + _items.value.filterNot { it.id == solution.id }
        writeAtomically(updated)
        _items.value = updated
    }

    private fun writeAtomically(updated: List<Solution>) {
        writeAtomically(
            target = file,
            temporary = File(filesDir, "history.json.tmp"),
            bytes = json.encodeToString(updated).toByteArray(Charsets.UTF_8),
        )
    }

    private fun writeAtomically(target: File, temporary: File, bytes: ByteArray) {
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun canonicalUuid(value: String): String {
        val parsed = UUID.fromString(value)
        require(parsed.toString().equals(value, ignoreCase = true)) { "stableId must be a canonical UUID" }
        return parsed.toString()
    }

    private fun ownedImage(path: String): File? = runCatching {
        val directory = imagesDir.canonicalFile
        File(path).canonicalFile.takeIf { it.path.startsWith(directory.path + File.separator) }
    }.getOrNull()
}
