package com.smartcalc.app.data.api

import com.smartcalc.app.data.settings.ApiFormat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TaskDescriptionTest {
    private val client = OpenAiClient()

    @Test fun lenientJsonWithUnescapedLatexAndStringLists() {
        val raw = "```json\n" + """{"condition": "Найти ${'$'}\frac{1}{s+2}${'$'}", "topic": "обратное преобразование Лапласа",
            "keywords": "оригинал, вычеты; таблица", "sections": ["Практика. Обратное преобразование Лапласа", "", 7]}""" + "\n```"
        val found = client.parseTaskDescription(raw)
        assertEquals("Найти ${'$'}\\frac{1}{s+2}${'$'}", found.condition)
        assertEquals(listOf("оригинал", "вычеты", "таблица"), found.keywords)
        assertEquals(listOf("Практика. Обратное преобразование Лапласа", "7"), found.sections)
    }

    @Test fun missingFieldsAreEmptyAndListsAreBounded() {
        val found = client.parseTaskDescription("""{"keywords": [${(1..40).joinToString { "\"k$it\"" }}]}""")
        assertEquals("", found.condition)
        assertEquals(20, found.keywords.size)
        assertTrue(found.sections.isEmpty())
        try { client.parseTaskDescription("не JSON"); fail() } catch (_: ApiException) { }
    }

    @Test fun photoIsSentWithLibrarySectionsWithoutReasoning() = runBlocking {
        val reply = buildJsonObject {
            put("role", "assistant")
            put("content", """{"condition": "Задача fixture-token", "topic": "т", "keywords": [], "sections": ["Раздел А"]}""")
        }
        AgentServer(listOf(200 to reply)).use { server ->
            val settings = server.settings.copy(supportsImages = true, apiFormat = ApiFormat.THINKING_TYPE, thinkingEnabled = true)
            val found = client.describeTask(settings, listOf("PHOTO"), null, listOf("Раздел А", "Раздел Б"))
            val body = server.requests.single()
            assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            val text = body["messages"].toString()
            assertTrue(text.contains("- Раздел А") && text.contains("- Раздел Б"))
            assertTrue(text.contains("data:image/jpeg;base64,PHOTO"))
            assertFalse("The task is only read, never solved", text.contains("\"steps\""))
            assertEquals("Задача [скрыто]", found.condition)
            assertEquals(listOf("Раздел А"), found.sections)
        }
    }

    @Test fun photoNeverGoesToConnectionWithoutImages() = runBlocking {
        AgentServer(emptyList()).use { server ->
            try {
                client.describeTask(server.settings, listOf("PHOTO"), null, listOf("Раздел"))
                fail("A text-only connection must not receive the photo")
            } catch (_: ApiConfigurationException) { }
            assertTrue(server.requests.isEmpty())
        }
    }
}
