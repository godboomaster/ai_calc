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

class HistoryStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file get() = File(context.filesDir, "history.json")
    private val imagesDir get() = File(context.filesDir, "images").apply { mkdirs() }
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
            val updated = listOf(solution) + _items.value
            file.writeText(json.encodeToString(updated))
            _items.value = updated
        }
    }

    /** Решение с новым дополнением: заменяет старую запись и поднимается наверх истории. */
    suspend fun update(solution: Solution) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = listOf(solution) + _items.value.filterNot { it.id == solution.id }
            file.writeText(json.encodeToString(updated))
            _items.value = updated
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _items.value.firstOrNull { it.id == id }?.imagePath?.let { File(it).delete() }
            val updated = _items.value.filterNot { it.id == id }
            file.writeText(json.encodeToString(updated))
            _items.value = updated
        }
    }

    suspend fun saveImage(jpeg: ByteArray): String = withContext(Dispatchers.IO) {
        val f = File(imagesDir, "${UUID.randomUUID()}.jpg")
        f.writeBytes(jpeg)
        f.absolutePath
    }
}
