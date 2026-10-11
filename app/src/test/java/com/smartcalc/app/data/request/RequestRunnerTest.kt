package com.smartcalc.app.data.request

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RequestRunnerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun completionSurvivesDisposalOfObserverAndIsPersisted() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val file = File(temporary.root, "request.json")
            val journal = RequestJournal(file)
            val release = CompletableDeferred<String>()
            val runner = RequestRunner<String>(scope, journal, { _, result -> result })
            runner.start(record("one"), {}, { release.await() })
            withTimeout(2000) { runner.state.first { it?.status == RequestStatus.RUNNING } }
            // No screen collector is alive while work completes.
            release.complete("saved-answer")
            val done = withTimeout(2000) { runner.state.first { it?.status == RequestStatus.SUCCEEDED } }!!
            assertEquals("saved-answer", done.resultSolutionId)
            assertTrue(done.unread)
            assertEquals(done, RequestJournal(file).state.value)
        } finally { scope.cancel() }
    }

    @Test fun replacementCancelsOldWorkAndNeverPublishesItsAnswer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val journal = RequestJournal(File(temporary.root, "request.json"))
            val oldStarted = CompletableDeferred<Unit>()
            val oldCancelled = CompletableDeferred<Unit>()
            val commits = mutableListOf<String>()
            val runner = RequestRunner<String>(scope, journal, { _, result -> commits.add(result); result })
            runner.start(record("old"), {}, {
                oldStarted.complete(Unit)
                try { CompletableDeferred<String>().await() } finally { oldCancelled.complete(Unit) }
            })
            withTimeout(2000) { oldStarted.await() }
            runner.start(record("new"), {}, { "new-answer" })
            withTimeout(2000) { runner.state.first { it?.status == RequestStatus.SUCCEEDED } }
            withTimeout(2000) { oldCancelled.await() }
            assertEquals(listOf("new-answer"), commits)
            assertEquals("new", journal.state.value?.id)
        } finally { scope.cancel() }
    }

    @Test fun coldRecoveryDoesNotResendInterruptedRequest() = runBlocking {
        val file = File(temporary.root, "request.json")
        RequestJournal(file).replace(record("old").copy(status = RequestStatus.RUNNING))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runner = RequestRunner<String>(scope, RequestJournal(file), { _, _ -> error("Must not send or commit") })
            val interrupted = withTimeout(2000) { runner.state.first { it?.status == RequestStatus.INTERRUPTED } }!!
            assertTrue(interrupted.unread)
            assertNotNull(interrupted.error)
        } finally { scope.cancel() }
    }

    @Test fun savedResultCheckpointFinishesRecoveryWithoutNewRequest() = runBlocking {
        val file = File(temporary.root, "request.json")
        RequestJournal(file).replace(record("old").copy(status = RequestStatus.RUNNING))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runner = RequestRunner<String>(scope, RequestJournal(file),
                commit = { _, _ -> error("Recovery must use the checkpoint") },
                recoverResult = { "existing-solution" })
            val done = withTimeout(2000) { runner.state.first { it?.status == RequestStatus.SUCCEEDED } }!!
            assertEquals("existing-solution", done.resultSolutionId)
        } finally { scope.cancel() }
    }

    @Test fun refusedServiceStartPersistsErrorWithoutSending() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val journal = RequestJournal(File(temporary.root, "request.json"))
            val runner = RequestRunner<String>(scope, journal, { _, result -> result })
            runner.start(record("one"), { throw IllegalStateException("background forbidden") }, { error("Must not send") })
            val failed = withTimeout(2000) { runner.state.first { it?.status == RequestStatus.FAILED } }!!
            assertNotNull(failed.error)
            assertEquals(failed, journal.state.value)
        } finally { scope.cancel() }
    }

    @Test fun checkpointIsRecoveredAfterHistoryWriteFailsWithoutSendingAgain() = runBlocking {
        val file = File(temporary.root, "request.json")
        var checkpoint: String? = null
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runner = RequestRunner<String>(firstScope, RequestJournal(file), { _, result ->
                checkpoint = result
                throw java.io.IOException("History disk write failed after checkpoint")
            })
            runner.start(record("one"), {}, { "paid-answer" })
            withTimeout(2000) { runner.state.first { it?.status == RequestStatus.FAILED } }
        } finally { firstScope.cancel() }
        val restoredScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val restored = RequestRunner<String>(restoredScope, RequestJournal(file),
                commit = { _, _ -> error("No new request expected") }, recoverResult = { checkpoint })
            val result = withTimeout(2000) { restored.state.first { it?.status == RequestStatus.SUCCEEDED } }!!
            assertEquals("paid-answer", result.resultSolutionId)
        } finally { restoredScope.cancel() }
    }

    @Test fun replacementIsDurableWhileOlderAnswerIsStillBeingCommitted() = runBlocking {
        val file = File(temporary.root, "request.json")
        val journal = RequestJournal(file)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val oldCommitting = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        try {
            val runner = RequestRunner<String>(scope, journal, { id, result ->
                if (id == "old") { oldCommitting.complete(Unit); releaseCommit.await() }
                result
            })
            runner.start(record("old"), {}, { "old-answer" })
            withTimeout(2000) { oldCommitting.await() }
            runner.start(record("new"), {}, { newStarted.complete(Unit); "new-answer" })
            withTimeout(2000) { journal.state.first { it?.id == "new" } }
            assertEquals("new", RequestJournal(file).state.value?.id)
            assertFalse(newStarted.isCompleted)
            releaseCommit.complete(Unit)
            withTimeout(2000) { runner.state.first { it?.status == RequestStatus.SUCCEEDED } }
            assertEquals("new", RequestJournal(file).state.value?.id)
        } finally { releaseCommit.complete(Unit); scope.cancel() }
    }

    @Test fun dismissIsDurableBeforeOldCommitFinishes() = runBlocking {
        val file = File(temporary.root, "request.json")
        val journal = RequestJournal(file)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val committing = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        try {
            val runner = RequestRunner<String>(scope, journal, { _, result ->
                committing.complete(Unit); releaseCommit.await(); result
            })
            runner.start(record("old"), {}, { "old-answer" })
            withTimeout(2000) { committing.await() }
            runner.dismiss()
            withTimeout(2000) { journal.state.first { it == null } }
            assertNull(RequestJournal(file).state.value)
            releaseCommit.complete(Unit)
            Unit
        } finally { releaseCommit.complete(Unit); scope.cancel() }
    }

    @Test fun recoveredSuccessIsVisibleBeforeRetryPayloadIsCleanedUp() = runBlocking {
        val journal = RequestJournal(File(temporary.root, "request.json"))
        journal.replace(record("one").copy(status = RequestStatus.FAILED, error = "disk failure"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val releaseRecovery = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        try {
            val runner = RequestRunner<String>(scope, journal, commit = { _, result -> result },
                recoverResult = { releaseRecovery.await(); "paid-answer" },
                cleanup = { cleaning.complete(Unit); releaseCleanup.await() })
            releaseRecovery.complete(Unit)
            withTimeout(2000) { cleaning.await() }
            assertEquals(RequestStatus.SUCCEEDED, runner.state.value?.status)
            assertNull(runner.state.value?.error)
        } finally { releaseCleanup.complete(Unit); scope.cancel() }
    }

    private fun record(id: String) = RequestRecord(id, RequestStatus.PREPARING, "Test", retryText = "2+2")
}
