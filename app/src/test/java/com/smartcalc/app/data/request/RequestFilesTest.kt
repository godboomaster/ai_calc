package com.smartcalc.app.data.request

import com.smartcalc.app.data.api.TemplatePrompt
import com.smartcalc.app.data.history.FollowUp
import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.history.SolutionStep
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RequestFilesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun roundTripsPreparedImagesTemplateBaseSolutionAndFormatting() = runBlocking {
        val directory = temporaryFolder.newFolder("input-roundtrip")
        val expected = PreparedRequest(
            text = "Решите: ∫₀¹ x² dx",
            images = listOf("aGVsbG8=", "/9j/4AAQSkZJRg=="),
            base = solution(id = "base-решение", answer = "⅓"),
            template = TemplatePrompt(
                name = "Интегралы",
                requirements = "Показывать замену переменной",
                examples = listOf(
                    TemplatePrompt.Example(
                        condition = "∫ x dx",
                        conditionImages = listOf("Y29uZGl0aW9u"),
                        solution = "x²/2 + C",
                        solutionImages = listOf("c29sdXRpb24="),
                    ),
                ),
            ),
            templateId = "template-17",
            formatText = "Краткий ответ",
            formatSample = "Ответ: 42",
            formatSummary = "Одна строка без пояснений",
        )
        val files = RequestFiles(directory)

        files.saveInput("request-1", expected)

        val actual = requireNotNull(files.readInput("request-1"))
        assertEquals(expected.copy(template = null), actual.copy(template = null))
        assertTemplateEquals(expected.template, actual.template)
    }

    @Test
    fun roundTripsACompleteResultCheckpoint() = runBlocking {
        val directory = temporaryFolder.newFolder("result-roundtrip")
        val expected = solution(id = "result-42", answer = "Готово").copy(
            followUps = listOf(
                FollowUp(
                    question = "Почему?",
                    answer = "По определению",
                    steps = listOf(SolutionStep("Пояснение", "Подробный ответ")),
                    timestamp = 987_654_321L,
                    template = "Интегралы",
                ),
            ),
        )
        val files = RequestFiles(directory)

        files.saveResult("request-2", expected)

        assertEquals(expected, files.readResult("request-2"))
    }

    @Test
    fun mismatchedRequestIdsCannotReadCurrentCheckpoints() = runBlocking {
        val directory = temporaryFolder.newFolder("mismatch")
        val files = RequestFiles(directory)
        files.saveInput("current", PreparedRequest(text = "current input"))
        files.saveResult("current", solution(id = "current-result", answer = "current result"))

        assertNull(files.readInput("stale"))
        assertNull(files.readResult("stale"))
        assertEquals("current input", files.readInput("current")?.text)
        assertEquals("current-result", files.readResult("current")?.id)
    }

    @Test
    fun corruptCheckpointFilesReadAsMissing() = runBlocking {
        val directory = temporaryFolder.newFolder("corrupt")
        val files = RequestFiles(directory)
        files.saveInput("request", PreparedRequest(text = "input"))
        files.saveResult("request", solution(id = "result", answer = "answer"))
        directory.listFiles().orEmpty().forEach { it.writeText("{ definitely-not-json") }

        assertNull(files.readInput("request"))
        assertNull(files.readResult("request"))
    }

    @Test
    fun overwritesLeaveNoTemporaryFilesAndClearPreservesUnownedFiles() = runBlocking {
        val directory = temporaryFolder.newFolder("overwrite")
        val keep = File(directory, "keep.txt").apply { writeText("user data") }
        val files = RequestFiles(directory)

        files.saveInput("first", PreparedRequest(text = "old input"))
        files.saveInput("second", PreparedRequest(text = "new input"))
        files.saveResult("first", solution(id = "old-result", answer = "old"))
        files.saveResult("second", solution(id = "new-result", answer = "new"))

        assertEquals("new input", files.readInput("second")?.text)
        assertEquals("new-result", files.readResult("second")?.id)
        val ownedFiles = directory.listFiles().orEmpty().filterNot { it == keep }
        assertEquals(2, ownedFiles.size)
        assertTrue(ownedFiles.all { it.isFile && it.extension == "json" })
        assertFalse(ownedFiles.any { it.name.endsWith(".tmp") })

        files.clear()

        assertEquals("user data", keep.readText())
        assertEquals(listOf("keep.txt"), directory.list()?.sorted())
        assertNull(files.readInput("second"))
        assertNull(files.readResult("second"))
    }

    @Test
    fun requestIdsAreEnvelopeDataAndCannotSelectPaths() = runBlocking {
        val parent = temporaryFolder.newFolder("path-safety")
        val directory = File(parent, "checkpoints")
        val escaped = File(parent, "escaped.json")
        val files = RequestFiles(directory)

        files.saveInput("../escaped.json", PreparedRequest(text = "safe"))

        assertFalse(escaped.exists())
        assertEquals("safe", files.readInput("../escaped.json")?.text)
        assertEquals(1, directory.listFiles().orEmpty().count { it.isFile })
    }

    private fun solution(id: String, answer: String) = Solution(
        id = id,
        problem = "Найдите значение",
        answer = answer,
        steps = listOf(SolutionStep("Шаг 1", "Вычисляем")),
        imagePath = "images/source.jpg",
        timestamp = 123_456_789L,
        format = "Подробно",
        template = "Интегралы",
        templateId = "template-17",
    )

    private fun assertTemplateEquals(expected: TemplatePrompt?, actual: TemplatePrompt?) {
        assertNotNull(expected)
        assertNotNull(actual)
        expected ?: return
        actual ?: return
        assertEquals(expected.name, actual.name)
        assertEquals(expected.requirements, actual.requirements)
        assertEquals(expected.examples.size, actual.examples.size)
        expected.examples.zip(actual.examples).forEach { (expectedExample, actualExample) ->
            assertEquals(expectedExample.condition, actualExample.condition)
            assertEquals(expectedExample.conditionImages, actualExample.conditionImages)
            assertEquals(expectedExample.solution, actualExample.solution)
            assertEquals(expectedExample.solutionImages, actualExample.solutionImages)
        }
    }
}
