package com.smartcalc.app.data.request

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Owns a request independently of any screen. */
class RequestRunner<T>(
    private val scope: CoroutineScope,
    private val journal: RequestJournal,
    private val commit: suspend (String, T) -> String,
    private val recoverResult: suspend (RequestRecord) -> String? = { null },
    private val describeError: (Throwable) -> String = { "Не удалось выполнить запрос" },
    private val cleanup: suspend () -> Unit = {},
) {
    private val lock = Any()
    private var generation = 0L
    private var job: Job? = null
    private val mutableState = MutableStateFlow(journal.state.value)
    val state = mutableState.asStateFlow()

    private val recovery = scope.launch {
        try {
            val old = journal.state.value
            if (old != null && old.status != RequestStatus.SUCCEEDED) {
                val result = recoverResult(old)
                if (result != null) journal.update(old.id) {
                    if (it.startedAt == old.startedAt) it.copy(status = RequestStatus.SUCCEEDED,
                        resultSolutionId = result, error = null, unread = true) else it
                } else if (old.active) journal.recoverInterrupted(old)
            }
            val restored = journal.state.value
            // Hide Retry before deleting its saved input/result; a newer attempt keeps those files.
            publish(0, restored)
            if (owns(0) && (restored == null || restored.status == RequestStatus.SUCCEEDED)) cleanUp()
        } catch (_: Exception) {
            publish(0, journal.state.value?.copy(status = RequestStatus.INTERRUPTED,
                error = "Не удалось восстановить запрос. Попробуйте повторить его.", unread = true))
        }
    }

    fun start(record: RequestRecord, startService: () -> Unit, work: suspend () -> T) = synchronized(lock) {
        val previous = job
        previous?.cancel()
        val owner = ++generation
        mutableState.value = record
        // Must be called synchronously while the submitting Activity is still foreground.
        val startError = try { startService(); null } catch (e: Exception) { e }
        job = scope.launch {
            try {
                // Install replacement before waiting for a non-cancellable old commit.
                journal.replace(record)
                previous?.join()
                recovery.join()
                if (startError != null) throw startError
                val running = record.copy(status = RequestStatus.RUNNING)
                journal.update(record.id) { if (it.startedAt == record.startedAt) running else it }
                publish(owner, running)
                val result = withTimeout(8 * 60 * 1000L) { work() }
                currentCoroutineContext().ensureActive()
                // A completed response is committed exactly once before replacement can begin.
                withContext(NonCancellable) {
                    if (owns(owner)) {
                        val resultId = commit(record.id, result)
                        val done = running.copy(status = RequestStatus.SUCCEEDED,
                            resultSolutionId = resultId, unread = true)
                        if (owns(owner)) {
                            journal.update(record.id) {
                                if (it.startedAt == record.startedAt && it.active) done else it
                            }
                            publish(owner, done)
                            if (owns(owner)) cleanUp()
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                fail(owner, record, "Превышено время ожидания ответа. Можно повторить запрос.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(owner, record, describeError(e))
            }
        }
    }

    fun dismiss() = replaceWith(null)

    fun interrupt(message: String) {
        val current = state.value ?: return
        if (current.active) replaceWith(current.copy(status = RequestStatus.INTERRUPTED, error = message, unread = true))
    }

    private fun replaceWith(record: RequestRecord?) = synchronized(lock) {
        val previous = job
        previous?.cancel()
        val owner = ++generation
        mutableState.value = record
        scope.launch {
            if (owns(owner)) try { journal.replace(state.value) } catch (_: Exception) { /* Keep visible status. */ }
            previous?.join()
            recovery.join()
            if (record == null && owns(owner)) cleanUp()
        }.also { job = it }
    }

    fun acknowledge() {
        val record = synchronized(lock) {
            mutableState.value?.takeIf { it.unread }?.also { mutableState.value = it.copy(unread = false) }
        } ?: return
        scope.launch {
            try { journal.update(record.id) {
                if (it.startedAt == record.startedAt) it.copy(unread = false) else it
            } } catch (_: Exception) { /* Retry on next view. */ }
        }
    }

    private suspend fun fail(owner: Long, record: RequestRecord, message: String) {
        if (!owns(owner)) return
        val failed = record.copy(status = RequestStatus.FAILED, error = message, unread = true)
        try { journal.update(record.id) {
            if (it.startedAt == record.startedAt && it.active) failed else it
        } } catch (_: Exception) {
            publish(owner, failed.copy(error = "Не удалось сохранить запрос. Проверьте свободное место."))
            return
        }
        publish(owner, failed)
    }

    private suspend fun cleanUp() {
        // The durable success record/history is already authoritative. Retry cleanup on next launch.
        try { cleanup() } catch (_: Exception) { }
    }

    private fun owns(owner: Long) = synchronized(lock) { generation == owner }
    private fun publish(owner: Long, record: RequestRecord?) = synchronized(lock) {
        if (generation == owner) mutableState.value = record
    }
}
