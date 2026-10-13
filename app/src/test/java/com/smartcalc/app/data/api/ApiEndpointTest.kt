package com.smartcalc.app.data.api

import com.smartcalc.app.data.settings.ApiSettings
import com.smartcalc.app.data.settings.ReasoningFormat
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ApiEndpointTest {
    @Test fun qwenThinkingOnlyVariantsAreNotOfferedAnAutomaticOffSwitch() {
        val body = buildJsonObject { put("messages", buildJsonArray { }) }
        for (model in listOf("qwen3.7-max-preview", "qwen3.7-max-2026-05-17")) {
            val settings = ApiSettings(baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", model = model, thinkingEnabled = false)
            assertEquals(body, configuredApiBody(settings, body))
            assertFalse(reasoningCapability(settings).controlled)
        }
    }
    @Test fun selectedFormatsWorkOnCustomEndpointsWithoutMixingVendorParameters() {
        val body = buildJsonObject { put("model", "custom"); put("messages", buildJsonArray { }) }
        for (enabled in listOf(true, false)) {
            val settings = ApiSettings(baseUrl = "https://proxy.example/v1", thinkingEnabled = enabled)
            val thinking = configuredApiBody(settings.copy(reasoningFormat = ReasoningFormat.THINKING_TYPE), body)
            assertEquals(if (enabled) "enabled" else "disabled", thinking["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            val qwen = configuredApiBody(settings.copy(reasoningFormat = ReasoningFormat.ENABLE_THINKING), body)
            assertEquals(enabled, qwen["enable_thinking"]!!.jsonPrimitive.boolean)
            assertFalse(qwen.containsKey("thinking"))
            val openai = configuredApiBody(settings.copy(reasoningFormat = ReasoningFormat.REASONING_EFFORT), body)
            assertEquals(if (enabled) "high" else "none", openai["reasoning_effort"]!!.jsonPrimitive.content)
            assertFalse(openai.containsKey("thinking"))
        }
    }

    @Test fun officialHybridProvidersAreDetectedWithoutMatchingLookalikeHosts() {
        val cases = listOf(
            Triple("https://api.openai.com/v1", "gpt-5.1", "reasoning_effort"),
            Triple("https://dashscope-intl.aliyuncs.com/compatible-mode/v1", "qwen-plus", "enable_thinking"),
            Triple("https://api.moonshot.ai/v1", "kimi-k2.5", "thinking"),
            Triple("https://api.z.ai/api/paas/v4", "glm-4.7", "thinking"),
            Triple("https://open.bigmodel.cn/api/paas/v4", "glm-5.2", "thinking"),
        )
        val body = buildJsonObject { put("messages", buildJsonArray { }) }
        for ((url, model, key) in cases) {
            assertTrue("$model on $url", configuredApiBody(ApiSettings(baseUrl = url, model = model), body).containsKey(key))
            val host = java.net.URI(url).host
            assertEquals(body, configuredApiBody(ApiSettings(baseUrl = url.replace(host, "$host.evil.test"), model = model), body))
        }
    }

    @Test fun openAiAlwaysThinkingModelsUseLowestSupportedEffortInsteadOfInvalidNone() {
        val body = buildJsonObject { put("messages", buildJsonArray { }) }
        for ((model, expected) in listOf("gpt-5" to "minimal", "o3" to "low", "o4-mini" to "low")) {
            val settings = ApiSettings(baseUrl = "https://api.openai.com/v1", model = model, thinkingEnabled = false)
            assertEquals(expected, configuredApiBody(settings, body)["reasoning_effort"]!!.jsonPrimitive.content)
        }
    }

    @Test fun glmAlwaysThinkingModeNeverSendsDisabled() {
        val settings = ApiSettings(baseUrl = "https://api.z.ai/api/paas/v4", model = "glm-5.3", thinkingEnabled = false)
        val body = configuredApiBody(settings, buildJsonObject {})
        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("low", body["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test fun serverDefaultSuppressesExplicitThinkingEvenOnDeepSeek() {
        val body = buildJsonObject { put("messages", buildJsonArray { }) }
        assertEquals(body, configuredApiBody(ApiSettings(baseUrl = "https://api.deepseek.com", reasoningFormat = ReasoningFormat.SERVER_DEFAULT), body))
    }
    @Test fun deepSeekThinkingSettingDefaultsOnAndBothStatesReachRequestBody() {
        val body = buildJsonObject { put("model", "deepseek-flash"); put("messages", buildJsonArray { }) }
        val settings = ApiSettings(baseUrl = "https://api.deepseek.com")
        assertTrue(settings.thinkingEnabled)
        val on = configuredApiBody(settings, body)
        val off = configuredApiBody(settings.copy(thinkingEnabled = false), on)
        assertEquals("enabled", on["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("disabled", off["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(body["messages"], on["messages"])
        assertEquals(body["model"], off["model"])
        assertFalse(body.containsKey("thinking"))
    }

    @Test fun thinkingSettingNeverAddsDeepSeekParametersToOtherProviders() {
        val body = buildJsonObject { put("model", "fixture"); put("messages", buildJsonArray { }) }
        for (enabled in listOf(true, false)) {
            assertEquals(body, configuredApiBody(ApiSettings(baseUrl = "https://api.openai.com/v1", thinkingEnabled = enabled), body))
            assertEquals(body, configuredApiBody(ApiSettings(baseUrl = "http://127.0.0.1/v1", thinkingEnabled = enabled), body))
        }
    }

    @Test fun deepSeekThinkingToolPolicyIsLimitedToOfficialHost() {
        for (url in listOf("https://api.deepseek.com", "https://api.deepseek.com/v1/", "https://API.DEEPSEEK.COM/beta"))
            assertTrue(officialDeepSeekTools(url))
        for (url in listOf("https://api.deepseek.com.evil.test", "https://example.com/api.deepseek.com", "http://127.0.0.1/v1", "https://api.openai.com/v1"))
            assertFalse(officialDeepSeekTools(url))
    }

    @Test fun remoteHttpIsRejectedBeforeConstructingAuthenticatedRequest() {
        for (url in listOf("http://api.example.com/v1", "http://192.168.1.2/v1",
            "http://127.0.0.1.example.com/v1", "http://localhost.example.com/v1")) {
            assertThrows(ApiException::class.java) { apiEndpoint(url) }
        }
    }

    @Test fun httpsCustomEndpointsAndLiteralLoopbackAreSupported() {
        assertEquals("https://api.example.com/v1/chat/completions", apiEndpoint(" https://api.example.com/v1/ ").toString())
        assertEquals("http://127.0.0.1:8080/v1/chat/completions", apiEndpoint("http://127.0.0.1:8080/v1").toString())
        assertEquals("http://[::1]:8080/v1/chat/completions", apiEndpoint("http://[::1]:8080/v1").toString())
    }

    @Test fun malformedAddressesAndEmbeddedCredentialsAreRejected() {
        for (url in listOf("garbage", "file:///tmp/key", "https://user:password@api.example.com/v1")) {
            assertThrows(ApiException::class.java) { apiEndpoint(url) }
        }
    }
}
