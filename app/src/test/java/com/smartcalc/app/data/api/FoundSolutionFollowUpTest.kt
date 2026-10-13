package com.smartcalc.app.data.api

import com.smartcalc.app.data.history.Solution
import com.smartcalc.app.data.history.SolutionStep
import com.smartcalc.app.data.settings.ApiSettings
import com.smartcalc.app.data.request.requestError
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class FoundSolutionFollowUpTest {
    @get:Rule val tmp = TemporaryFolder()
    private val client = OpenAiClient()

    @Test fun followUpAlsoRequestsOnlySubjectStepsWithCustomPrompt() {
        val messages = client.followUpMessages(ApiSettings(systemPrompt = "Custom prompt"), Solution(problem = "Условие", answer = "42"), "Почему?")
        val system = messages.first().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(system.contains("только содержательные этапы решения задачи"))
        assertTrue(system.contains("Не добавляй в problem, answer и steps"))
    }

    @Test fun photoOnlyLibraryFollowUpIncludesConditionAndSolutionImagesOnce() {
        val condition = tmp.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val solution = tmp.newFile().apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val base = Solution(problem = "Задача с фото", answer = "Решение на фото", source = "База",
            imagePath = condition.path, steps = listOf(
                SolutionStep("Условие на фото", "", images = listOf(condition.path)),
                SolutionStep("Решение", "", images = listOf(solution.path)),
            ))
        val messages = client.followUpMessages(ApiSettings(), base, "Объясни второй шаг")
        val content = messages[1].jsonObject["content"]!!
        assertTrue("Photo context must be sent as multimodal user content", content is JsonArray)
        val urls = content.jsonArray.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "image_url" }
            .map { it.jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content }
        assertEquals(listOf("data:image/jpeg;base64,AQID", "data:image/jpeg;base64,BAUG"), urls)
        assertFalse(messages.toString().contains(condition.path))
    }

    @Test fun missingLibraryImageStopsRequestInsteadOfDroppingContext() {
        val base = Solution(problem = "Фото", answer = "Фото", source = "База", imagePath = File(tmp.root, "missing.jpg").path)
        val error = assertThrows(Exception::class.java) { client.followUpMessages(ApiSettings(), base, "Почему?") }
        assertTrue(requestError(error).contains("фотографии"))
        assertFalse(requestError(error).contains("missing.jpg"))
    }

    @Test fun oversizedLibraryImageStopsBeforeEncoding() {
        val photo = tmp.newFile()
        RandomAccessFile(photo, "rw").use { it.setLength(21L * 1024 * 1024) }
        val base = Solution(problem = "Фото", answer = "Фото", source = "База", imagePath = photo.path)
        val error = assertThrows(Exception::class.java) { client.followUpMessages(ApiSettings(), base, "Почему?") }
        assertTrue(requestError(error).contains("20 МБ"))
    }

    @Test fun recognizedAiSolutionKeepsTextOnlyFollowUp() {
        val base = Solution(problem = "Распознанное условие", answer = "42", imagePath = "obsolete-thumbnail.jpg")
        val content = client.followUpMessages(ApiSettings(), base, "Почему?")[1].jsonObject["content"]!!
        assertEquals("Задача: Распознанное условие", content.jsonPrimitive.content)
    }
}
