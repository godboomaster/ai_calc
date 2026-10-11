package com.smartcalc.app.data.api

import org.junit.Assert.*
import org.junit.Test

class ApiEndpointTest {
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
