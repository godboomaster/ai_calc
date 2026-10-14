package com.smartcalc.app.data.format

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

/** Как пользователь хочет видеть решение: текстовые требования и/или фото образца оформления. */
@Serializable
data class SolutionFormat(
    val text: String = "",
    val samplePath: String? = null,
) {
    val isSet: Boolean get() = text.isNotBlank() || samplePath != null

    /** Короткая подпись для плашек: первая строка требований или «по образцу». */
    val summary: String
        get() {
            val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            return when {
                line == null -> "по образцу"
                line.length > 40 -> line.take(40).trimEnd() + "…"
                else -> line
            }
        }
}

class FormatStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(context.filesDir, "format.json")
    private val mutex = Mutex()

    private val _format = MutableStateFlow(load())
    val format: StateFlow<SolutionFormat> = _format

    private fun load(): SolutionFormat = try {
        if (file.exists()) json.decodeFromString<SolutionFormat>(file.readText()) else SolutionFormat()
    } catch (e: Exception) {
        SolutionFormat()
    }

    /**
     * [newSample] — новое фото образца (заменяет старое); если его нет,
     * старый образец остаётся при [keepSample] и удаляется иначе.
     */
    suspend fun save(text: String, newSample: ByteArray?, keepSample: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = _format.value.samplePath
            val samplePath = when {
                newSample != null -> File(context.filesDir, "format_sample_${UUID.randomUUID()}.jpg")
                    .apply { writeBytes(newSample) }
                    .absolutePath

                keepSample -> old
                else -> null
            }
            if (old != null && old != samplePath) File(old).delete()
            val updated = SolutionFormat(text.trim(), samplePath)
            file.writeText(json.encodeToString(updated))
            _format.value = updated
        }
    }

    suspend fun clear() = save("", newSample = null, keepSample = false)

    suspend fun readSample(format: SolutionFormat = _format.value): ByteArray? = withContext(Dispatchers.IO) {
        format.samplePath?.let { File(it) }?.takeIf { it.exists() }?.readBytes()
    }
}
