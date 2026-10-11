package com.smartcalc.app.data.session

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SessionState(
    val version: Int = 1,
    val calculator: CalculatorSession = CalculatorSession(),
    val reading: ReadingSession = ReadingSession(),
    val unread: Boolean = false,
)

@Serializable
data class CalculatorSession(
    val expression: String = "",
    val evaluated: Boolean = false,
    val taskDraft: String = "",
    val followUpDraft: String = "",
    val followUpSolutionId: String? = null,
)

@Serializable
enum class ReadingTarget { BRIEF, STEP, SOLUTION }

@Serializable
data class ReadingSession(
    val solutionId: String? = null,
    val followUpCount: Int = 0,
    val target: ReadingTarget = ReadingTarget.BRIEF,
    val selectedStep: Int = 0,
    val stepScroll: Map<Int, Int> = emptyMap(),
    val solutionScroll: ListPosition? = null,
    val answerScroll: Int = 0,
)

@Serializable
data class ListPosition(
    val index: Int = 0,
    val offset: Int = 0,
)

class SessionStore(
    private val file: File,
    scope: CoroutineScope,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val stateLock = Any()
    private val writeMutex = Mutex()
    private val writeRequests = Channel<Unit>(Channel.CONFLATED)
    private var revision = 0L

    private val mutableState = MutableStateFlow(load())
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private val mutableSaveError = MutableStateFlow(false)
    val saveError: StateFlow<Boolean> = mutableSaveError.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            while (true) {
                writeRequests.receive()
                persistLatest()
            }
        }
    }

    fun update(transform: (SessionState) -> SessionState) {
        synchronized(stateLock) {
            val updated = normalizeSessionState(transform(mutableState.value))
            if (updated == mutableState.value) return@synchronized
            mutableState.value = updated
            revision++
            writeRequests.trySend(Unit)
        }
    }

    suspend fun flush() = withContext(Dispatchers.IO) {
        while (true) {
            val writtenRevision = persistLatest()
            val currentRevision = synchronized(stateLock) { revision }
            if (writtenRevision == currentRevision) return@withContext
        }
    }

    private fun load(): SessionState = try {
        if (!file.isFile) {
            SessionState()
        } else {
            normalizeSessionState(json.decodeFromString(file.readText(StandardCharsets.UTF_8)))
        }
    } catch (_: Exception) {
        SessionState()
    }

    private suspend fun persistLatest(): Long = writeMutex.withLock {
        val (snapshot, snapshotRevision) = synchronized(stateLock) {
            mutableState.value to revision
        }
        try {
            writeAtomically(json.encodeToString(snapshot))
            mutableSaveError.value = false
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            mutableSaveError.value = true
        }
        snapshotRevision
    }

    private fun writeAtomically(content: String) {
        val target = file.toPath().toAbsolutePath()
        val directory = checkNotNull(target.parent) { "Session file must have a parent directory" }
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
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

internal fun normalizeSessionState(state: SessionState): SessionState {
    if (state.version != 1) return SessionState()

    val reading = state.reading
    return state.copy(
        reading = reading.copy(
            followUpCount = reading.followUpCount.coerceAtLeast(0),
            selectedStep = reading.selectedStep.coerceAtLeast(0),
            stepScroll = reading.stepScroll
                .filterKeys { it >= 0 }
                .mapValues { (_, offset) -> offset.coerceAtLeast(0) },
            solutionScroll = reading.solutionScroll?.let { position ->
                ListPosition(
                    index = position.index.coerceAtLeast(0),
                    offset = position.offset.coerceAtLeast(0),
                )
            },
            answerScroll = reading.answerScroll.coerceAtLeast(0),
        ),
    )
}
