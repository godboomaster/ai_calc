package com.smartcalc.app.data.session

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun roundTripsUnicodeDraftsAndReadingPositions() = runBlocking {
        val file = temporaryFolder.newFile("session.json")
        val expected = SessionState(
            calculator = CalculatorSession(
                expression = "sqrt(ёж²) + 𝜋",
                evaluated = true,
                taskDraft = "Решите: ∫₀¹ x² dx",
                followUpDraft = "Почему ответ равен ⅓?",
                followUpSolutionId = "решение-42",
            ),
            reading = ReadingSession(
                solutionId = "решение-42",
                followUpCount = 3,
                target = ReadingTarget.SOLUTION,
                selectedStep = 2,
                stepScroll = mapOf(0 to 14, 2 to 91),
                solutionScroll = ListPosition(index = 7, offset = 23),
                answerScroll = 48,
            ),
            unread = true,
        )

        val writeScope = testScope()
        try {
            val store = SessionStore(file, writeScope)
            store.update { expected }
            store.flush()
        } finally {
            writeScope.cancel()
        }

        val readScope = testScope()
        try {
            assertEquals(expected, SessionStore(file, readScope).state.value)
        } finally {
            readScope.cancel()
        }
    }

    @Test
    fun corruptOrUnknownSchemaLoadsDefaults() {
        val corrupt = temporaryFolder.newFile("corrupt.json").apply {
            writeText("{ definitely-not-json")
        }
        val unknown = temporaryFolder.newFile("unknown.json").apply {
            writeText("""{"version":99,"unread":true}""")
        }
        val scope = testScope()
        try {
            assertEquals(SessionState(), SessionStore(corrupt, scope).state.value)
            assertEquals(SessionState(), SessionStore(unknown, scope).state.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun normalizationRepairsNegativePositionsWithoutChangingDrafts() {
        val malformed = SessionState(
            calculator = CalculatorSession(taskDraft = "keep me"),
            reading = ReadingSession(
                followUpCount = -2,
                target = ReadingTarget.STEP,
                selectedStep = -5,
                stepScroll = mapOf(-1 to 8, 0 to -3, 2 to 11),
                solutionScroll = ListPosition(index = -4, offset = -9),
                answerScroll = -7,
            ),
            unread = true,
        )

        assertEquals(
            SessionState(
                calculator = CalculatorSession(taskDraft = "keep me"),
                reading = ReadingSession(
                    followUpCount = 0,
                    target = ReadingTarget.STEP,
                    selectedStep = 0,
                    stepScroll = mapOf(0 to 0, 2 to 11),
                    solutionScroll = ListPosition(index = 0, offset = 0),
                    answerScroll = 0,
                ),
                unread = true,
            ),
            normalizeSessionState(malformed),
        )
        assertEquals(SessionState(), normalizeSessionState(SessionState(version = 0, unread = true)))
    }

    @Test
    fun successiveFlushesAtomicallyReplaceTheStoredSnapshot() = runBlocking {
        val directory = temporaryFolder.newFolder("atomic")
        val file = File(directory, "session.json")
        val scope = testScope()
        try {
            val store = SessionStore(file, scope)
            store.update { it.copy(calculator = it.calculator.copy(expression = "first")) }
            store.flush()
            val firstBytes = file.readBytes()

            store.update {
                it.copy(
                    calculator = it.calculator.copy(expression = "second"),
                    unread = true,
                )
            }
            store.flush()

            assertNotEquals(firstBytes.toList(), file.readBytes().toList())
            assertEquals(listOf("session.json"), directory.list()?.sorted())

            val readScope = testScope()
            try {
                val restored = SessionStore(file, readScope).state.value
                assertEquals("second", restored.calculator.expression)
                assertTrue(restored.unread)
            } finally {
                readScope.cancel()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun concurrentUpdatesAndFlushPersistTheCurrentSnapshotWithoutLostChanges() = runBlocking {
        val file = File(temporaryFolder.newFolder("concurrent"), "session.json")
        val scope = testScope()
        try {
            val store = SessionStore(file, scope)
            val updates = List(400) {
                launch(Dispatchers.Default) {
                    store.update { state ->
                        state.copy(reading = state.reading.copy(followUpCount = state.reading.followUpCount + 1))
                    }
                }
            }
            updates.joinAll()
            store.update { it.copy(calculator = it.calculator.copy(taskDraft = "latest draft")) }
            store.flush()

            assertEquals(400, store.state.value.reading.followUpCount)
            assertEquals("latest draft", store.state.value.calculator.taskDraft)

            val readScope = testScope()
            try {
                assertEquals(store.state.value, SessionStore(file, readScope).state.value)
            } finally {
                readScope.cancel()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedSaveDoesNotThrowAndExposesTheFailure() = runBlocking {
        val parentFile = temporaryFolder.newFile("not-a-directory")
        val scope = testScope()
        try {
            val store = SessionStore(File(parentFile, "session.json"), scope)
            store.update { it.copy(unread = true) }
            store.flush()

            assertTrue(store.state.value.unread)
            assertTrue(store.saveError.value)
            assertFalse(File(parentFile, "session.json").exists())
        } finally {
            scope.cancel()
        }
    }

    private fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
