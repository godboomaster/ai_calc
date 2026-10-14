package com.smartcalc.app.data.api

import com.smartcalc.app.data.settings.ApiConnection
import com.smartcalc.app.data.settings.ApiFormat
import com.smartcalc.app.data.settings.ApiSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ApiAdapterTest {
    private fun photoMessages() = buildJsonArray {
        add(buildJsonObject { put("role", "system"); put("content", "rules") })
        add(buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "Образец") })
                add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,AAAA") }) })
            })
        })
    }

    @Test fun photosGoOnlyToConnectionMarkedForThem() {
        val textOnly = ApiSettings(connectionName = "Только текст")
        val error = assertThrows(ApiConfigurationException::class.java) { requireImageSupport(textOnly, photoMessages()) }
        assertTrue(error.message!!.contains("«Только текст» не принимает фото"))
        requireImageSupport(textOnly.copy(supportsImages = true), photoMessages())
        requireImageSupport(textOnly, buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "только текст") }) })
    }

    @Test fun photoTaskOnTextOnlyConnectionFailsBeforeAnyConnectionAttempt() = runBlocking {
        // Port 1 is closed: reaching the network would surface a connection error instead.
        val settings = ApiSettings(baseUrl = "http://127.0.0.1:1/v1", apiKey = "fixture-key", connectionName = "Текст")
        try {
            OpenAiClient().solve(settings, listOf("AAAA"), "Реши задачу на фото")
            fail("Photo must not be sent")
        } catch (e: ApiConfigurationException) {
            assertTrue(e.message!!.contains("«Текст»"))
        }
    }

    @Test fun newModelOfKnownProviderNeedsOnlyItsIdAndFormat() {
        val body = buildJsonObject { put("model", "kimi-k3"); put("messages", buildJsonArray { }) }
        val auto = ApiSettings(baseUrl = "https://api.moonshot.ai/v1", model = "kimi-k3")
        val adapter = apiAdapter(auto)
        assertEquals(ApiFormat.SERVER_DEFAULT, adapter.format)
        assertNull(adapter.provider)
        assertEquals(ApiFormat.THINKING_TYPE, adapter.suggestedFormat)
        assertEquals(body, configuredApiBody(auto, body))
        val chosen = auto.copy(apiFormat = ApiFormat.THINKING_TYPE, thinkingEnabled = false)
        assertEquals("disabled", configuredApiBody(chosen, body)["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(apiAdapter(chosen).suggestedFormat)
        assertNull(apiAdapter(ApiSettings(baseUrl = "https://gateway.example/v1", model = "kimi-k3")).suggestedFormat)
        assertNull(apiAdapter(ApiSettings(baseUrl = "https://api.openai.com/v1", model = "gpt-4o-mini")).suggestedFormat)
    }

    @Test fun toolProtocolFollowsWhetherReasoningActuallyRuns() {
        val deepSeek = ApiSettings(baseUrl = "https://api.deepseek.com", model = "deepseek-flash")
        assertTrue(apiAdapter(deepSeek).thinkingToolMode(deepSeek))
        val off = deepSeek.copy(thinkingEnabled = false)
        assertFalse(apiAdapter(off).thinkingToolMode(off))
        val alwaysReasoning = ApiSettings(baseUrl = "https://api.openai.com/v1", model = "gpt-5", thinkingEnabled = false)
        assertTrue(apiAdapter(alwaysReasoning).thinkingToolMode(alwaysReasoning))
        val serverMode = ApiSettings(baseUrl = "https://proxy.example/v1", model = "mimo-v2.5")
        assertFalse(apiAdapter(serverMode).controlsReasoning)
        assertTrue(apiAdapter(serverMode).thinkingToolMode(serverMode))
    }

    @Test fun connectionNameFallsBackToProviderThenHost() {
        assertEquals("Моя", ApiConnection(name = " Моя ").displayName)
        assertEquals("DeepSeek", ApiConnection(baseUrl = "https://api.deepseek.com").displayName)
        assertEquals("Qwen", ApiConnection(baseUrl = "https://team.maas.aliyuncs.com/v1").displayName)
        assertEquals("openrouter.ai", ApiConnection(baseUrl = "https://openrouter.ai/api/v1").displayName)
        assertEquals("Подключение", ApiConnection(baseUrl = "not a url").displayName)
    }
}
