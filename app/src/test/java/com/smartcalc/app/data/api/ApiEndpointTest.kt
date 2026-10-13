package com.smartcalc.app.data.api

import com.smartcalc.app.data.settings.ApiSettings
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ApiEndpointTest {
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
