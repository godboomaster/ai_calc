package com.smartcalc.app.data.request

import com.smartcalc.app.data.api.TemplatePrompt
import com.smartcalc.app.data.history.Solution
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PreparedRequest(
    val text: String? = null,
    val images: List<String> = emptyList(),
    val base: Solution? = null,
    val template: TemplatePrompt? = null,
    val templateId: String? = null,
    val formatText: String? = null,
    val formatSample: String? = null,
    val formatSummary: String? = null,
)

class RequestFiles(
    directory: File,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val mutex = Mutex()
    private val inputFile = File(directory, INPUT_FILE_NAME)
    private val resultFile = File(directory, RESULT_FILE_NAME)

    suspend fun saveInput(
        id: String,
        input: PreparedRequest,
    ) {
        mutex.withLock {
            write(inputFile) { json.encodeToString(InputEnvelope(id, input)) }
        }
    }

    suspend fun readInput(id: String): PreparedRequest? = mutex.withLock {
        read<InputEnvelope>(inputFile)?.takeIf { it.requestId == id }?.input
    }

    suspend fun saveResult(
        id: String,
        solution: Solution,
    ) {
        mutex.withLock {
            write(resultFile) { json.encodeToString(ResultEnvelope(id, solution)) }
        }
    }

    suspend fun readResult(id: String): Solution? = mutex.withLock {
        read<ResultEnvelope>(resultFile)?.takeIf { it.requestId == id }?.solution
    }

    suspend fun clear() {
        mutex.withLock {
            withContext(NonCancellable + Dispatchers.IO) {
                Files.deleteIfExists(inputFile.toPath())
                Files.deleteIfExists(resultFile.toPath())
            }
        }
    }

    private suspend inline fun <reified T> read(file: File): T? = withContext(Dispatchers.IO) {
        try {
            if (file.isFile) {
                json.decodeFromString<T>(file.readText(StandardCharsets.UTF_8))
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun write(file: File, content: () -> String) {
        withContext(NonCancellable + Dispatchers.IO) {
            writeAtomically(file, content())
        }
    }

    private fun writeAtomically(file: File, content: String) {
        val target = file.toPath().toAbsolutePath()
        val directory = checkNotNull(target.parent) { "Request checkpoint must have a parent directory" }
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

    @Serializable
    private data class InputEnvelope(
        val requestId: String,
        val input: PreparedRequest,
    )

    @Serializable
    private data class ResultEnvelope(
        val requestId: String,
        val solution: Solution,
    )

    private companion object {
        const val INPUT_FILE_NAME = "prepared-request.json"
        const val RESULT_FILE_NAME = "request-result.json"
    }
}
