package com.smartcalc.app.data.request

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RequestJournalTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test fun recoveryCannotInterruptANewerAttemptOfTheSameLogicalRequest() = runBlocking {
        val file = File(temporaryFolder.root, "request.json")
        val journal = RequestJournal(file)
        val old = RequestRecord("logical-request", RequestStatus.RUNNING, "Task", startedAt = 10)
        val retry = old.copy(status = RequestStatus.PREPARING, startedAt = 11)
        journal.replace(retry)
        journal.recoverInterrupted(old)
        assertEquals(retry, RequestJournal(file).state.value)
    }

    @Test
    fun replacePersistsEveryFieldAndConstructionLoadsItSynchronously() = runBlocking {
        val file = File(temporaryFolder.newFolder("roundtrip"), "request.json")
        val expected = RequestRecord(
            id = "запрос-42",
            status = RequestStatus.SUCCEEDED,
            caption = "Решение: ∫₀¹ x² dx",
            retryText = "Почему ответ равен ⅓?",
            followUp = true,
            baseSolutionId = "решение-41",
            resultSolutionId = "решение-42",
            error = "предыдущее предупреждение",
            unread = true,
            startedAt = 1_729_876_543_210L,
        )

        RequestJournal(file).replace(expected)

        assertEquals(expected, RequestJournal(file).state.value)
        assertEquals(listOf("request.json"), requireNotNull(file.parentFile).list()?.sorted())
    }

    @Test
    fun replaceNullClearsThePersistedRequest() = runBlocking {
        val file = File(temporaryFolder.newFolder("clear"), "request.json")
        val journal = RequestJournal(file)
        journal.replace(record(id = "active", status = RequestStatus.RUNNING))

        journal.replace(null)

        assertNull(journal.state.value)
        assertNull(RequestJournal(file).state.value)
    }

    @Test
    fun corruptFileLoadsAsNoRequest() {
        val file = temporaryFolder.newFile("corrupt-request.json").apply {
            writeText("{ definitely-not-json")
        }

        assertNull(RequestJournal(file).state.value)
    }

    @Test
    fun recoverMarksPreparingAndRunningRequestsInterrupted() = runBlocking {
        for (status in listOf(RequestStatus.PREPARING, RequestStatus.RUNNING)) {
            val file = File(temporaryFolder.newFolder("recover-${status.name}"), "request.json")
            val journal = RequestJournal(file)
            journal.replace(
                record(id = status.name, status = status).copy(
                    resultSolutionId = "keep-result-reference",
                ),
            )

            journal.recoverInterrupted()

            val expected = record(id = status.name, status = RequestStatus.INTERRUPTED).copy(
                resultSolutionId = "keep-result-reference",
                error = "Запрос прерван. Отправьте его повторно.",
                unread = true,
            )
            assertEquals(expected, journal.state.value)
            assertEquals(expected, RequestJournal(file).state.value)
        }
    }

    @Test
    fun recoverLeavesTerminalAndEmptyJournalsUnchanged() = runBlocking {
        for (status in listOf(
            RequestStatus.SUCCEEDED,
            RequestStatus.FAILED,
            RequestStatus.INTERRUPTED,
            RequestStatus.CANCELLED,
        )) {
            val file = File(temporaryFolder.newFolder("terminal-${status.name}"), "request.json")
            val expected = record(id = status.name, status = status).copy(error = "keep me")
            val journal = RequestJournal(file)
            journal.replace(expected)
            val bytesBeforeRecovery = file.readBytes()

            journal.recoverInterrupted()

            assertEquals(expected, journal.state.value)
            assertTrue(bytesBeforeRecovery.contentEquals(file.readBytes()))
        }

        val emptyFile = File(temporaryFolder.newFolder("empty"), "request.json")
        val emptyJournal = RequestJournal(emptyFile)
        emptyJournal.recoverInterrupted()
        assertNull(emptyJournal.state.value)
        assertFalse(emptyFile.exists())
    }

    @Test
    fun staleUpdateCannotOverwriteAReplacementRequest() = runBlocking {
        val file = File(temporaryFolder.newFolder("stale"), "request.json")
        val current = record(id = "new-request", status = RequestStatus.RUNNING)
        val journal = RequestJournal(file)
        journal.replace(current)

        val applied = journal.update("old-request") {
            it.copy(status = RequestStatus.SUCCEEDED, resultSolutionId = "wrong-result")
        }

        assertFalse(applied)
        assertEquals(current, journal.state.value)
        assertEquals(current, RequestJournal(file).state.value)
    }

    @Test
    fun concurrentMatchingUpdatesAreSerializedWithoutLostChanges() = runBlocking {
        val file = File(temporaryFolder.newFolder("concurrent"), "request.json")
        val journal = RequestJournal(file)
        journal.replace(record(id = "same-request", status = RequestStatus.RUNNING).copy(error = ""))

        List(100) {
            launch(Dispatchers.Default) {
                assertTrue(
                    journal.update("same-request") { current ->
                        current.copy(error = current.error + "x")
                    },
                )
            }
        }.joinAll()

        assertEquals("x".repeat(100), journal.state.value?.error)
        assertEquals(journal.state.value, RequestJournal(file).state.value)
    }

    @Test
    fun failedWriteThrowsAndRetainsThePreviouslyPublishedState() = runBlocking {
        val blockedParent = temporaryFolder.newFile("not-a-directory")
        val file = File(blockedParent, "request.json")
        val journal = RequestJournal(file)

        assertThrows(IOException::class.java) {
            runBlocking {
                journal.replace(record(id = "must-not-publish", status = RequestStatus.PREPARING))
            }
        }

        assertNull(journal.state.value)
        assertFalse(file.exists())
    }

    @Test
    fun activeIsTrueOnlyWhilePreparationOrExecutionCanContinue() {
        assertTrue(record(id = "preparing", status = RequestStatus.PREPARING).active)
        assertTrue(record(id = "running", status = RequestStatus.RUNNING).active)
        for (status in listOf(
            RequestStatus.SUCCEEDED,
            RequestStatus.FAILED,
            RequestStatus.INTERRUPTED,
            RequestStatus.CANCELLED,
        )) {
            assertFalse(record(id = status.name, status = status).active)
        }
    }

    private fun record(id: String, status: RequestStatus) = RequestRecord(
        id = id,
        status = status,
        caption = "Фоновое решение",
        retryText = "Повторить задачу",
        followUp = true,
        baseSolutionId = "base-solution",
        startedAt = 123_456_789L,
    )
}
