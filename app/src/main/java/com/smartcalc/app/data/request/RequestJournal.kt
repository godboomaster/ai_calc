package com.smartcalc.app.data.request

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class RequestStatus {
    PREPARING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    INTERRUPTED,
    CANCELLED,
}

@Serializable
data class RequestRecord(
    val id: String,
    val status: RequestStatus,
    val caption: String,
    val retryText: String? = null,
    val followUp: Boolean = false,
    val baseSolutionId: String? = null,
    val resultSolutionId: String? = null,
    val error: String? = null,
    val unread: Boolean = false,
    val startedAt: Long = System.currentTimeMillis(),
)

val RequestRecord.active: Boolean
    get() = status == RequestStatus.PREPARING || status == RequestStatus.RUNNING

class RequestJournal(
    private val file: File,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(load())
    val state: StateFlow<RequestRecord?> = mutableState.asStateFlow()

    suspend fun replace(record: RequestRecord?) {
        mutex.withLock {
            persistAndPublish(record)
        }
    }

    suspend fun update(
        id: String,
        transform: (RequestRecord) -> RequestRecord,
    ): Boolean = mutex.withLock {
        val current = mutableState.value
        if (current?.id != id) return@withLock false

        val updated = transform(current)
        persistAndPublish(updated)
        true
    }

    suspend fun recoverInterrupted(expected: RequestRecord? = null) {
        mutex.withLock {
            val current = mutableState.value ?: return@withLock
            if (!current.active) return@withLock
            if (expected != null && (current.id != expected.id || current.startedAt != expected.startedAt)) return@withLock

            val interrupted = current.copy(
                status = RequestStatus.INTERRUPTED,
                error = INTERRUPTED_ERROR,
                unread = true,
            )
            persistAndPublish(interrupted)
        }
    }

    private fun load(): RequestRecord? = try {
        if (!file.isFile) {
            null
        } else {
            json.decodeFromString<RequestRecord?>(file.readText(StandardCharsets.UTF_8))
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun persistAndPublish(record: RequestRecord?) {
        val content = json.encodeToString(record)
        withContext(NonCancellable + Dispatchers.IO) {
            writeAtomically(content)
            mutableState.value = record
        }
    }

    private fun writeAtomically(content: String) {
        val target = file.toPath().toAbsolutePath()
        val directory = checkNotNull(target.parent) { "Request journal must have a parent directory" }
        Files.createDirectories(directory)
        val temporary = directory.resolve(".${target.fileName}.${UUID.randomUUID()}.tmp")

        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(content.toByteArray(StandardCharsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            Files.move(
                temporary,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        const val INTERRUPTED_ERROR = "Запрос прерван. Отправьте его повторно."
    }
}
