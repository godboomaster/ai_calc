package com.smartcalc.app.data.request

import com.smartcalc.app.data.api.ApiConfigurationException
import com.smartcalc.app.data.api.ApiException
import com.smartcalc.app.data.api.OpenAiClient
import com.smartcalc.app.data.settings.ApiSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RequestErrorTest {
    @Test fun missingKeyHasAnActionableLocalMessage() {
        val error = assertThrows(ApiConfigurationException::class.java) {
            runBlocking { OpenAiClient().solve(ApiSettings(), emptyList(), "test") }
        }
        assertEquals("API-ключ не задан. Откройте настройки и введите ключ.", requestError(error))
    }

    @Test fun remoteErrorDetailsNeverReachTheUserOrJournal() {
        for (code in listOf(null, 401, 429, 500)) {
            assertFalse(requestError(ApiException("server echoed secret-key", code)).contains("secret-key"))
        }
    }
}
