package com.smartcalc.app.data.api

import com.smartcalc.app.data.settings.ApiSettings
import com.smartcalc.app.data.settings.ReasoningFormat
import com.smartcalc.app.data.library.agent.LibraryTools
import com.smartcalc.app.data.library.agent.LibraryCatalog
import com.smartcalc.app.data.library.LibraryMetadata
import com.smartcalc.app.data.template.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import okhttp3.Dns
import com.smartcalc.app.data.request.requestError
import java.util.concurrent.TimeUnit

class KoogGenerationTest {
    @Test fun unconfirmedVisionRejectsTaskAndSampleImagesBeforePost() = runBlocking { serverTest { server ->
        val client = OpenAiClient(generationTransport())
        for (sample in listOf(false, true)) {
            server.enqueue(answer())
            val error = try {
                client.solve(settings(server).copy(supportsImages = false),
                    if (sample) emptyList() else listOf("YWJj"), "6*7",
                    formatSampleBase64 = if (sample) "YWJj" else null)
                null
            } catch (e: ApiException) { e }
            assertEquals(0, server.requestCount)
            assertTrue("Expected actionable configuration error, got $error", error is ApiConfigurationException)
        }
    } }

    @Test fun supportedBasePathsKeepModelAndAuthorization() = runBlocking { serverTest { server ->
        for (path in listOf("/", "/v1", "/v1/")) {
            server.enqueue(answer())
            val config = settings(server).copy(baseUrl = server.url(path).newBuilder().host("127.0.0.1").build().toString(), model = "deepseek-flash")
            assertEquals("42", OpenAiClient(generationTransport()).solve(config, emptyList(), "6*7").answer)
            val sent = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(path.trimEnd('/') + "/chat/completions", sent.path)
            assertEquals("Bearer synthetic-key", sent.getHeader("Authorization"))
            assertEquals("deepseek-flash", Json.parseToJsonElement(sent.body.readUtf8()).jsonObject["model"]!!.jsonPrimitive.content)
        }
        assertEquals(3, server.requestCount)
    } }

    @Test fun unsupportedProvidersFailBeforeDnsWithoutFallback() = runBlocking {
        var lookups = 0
        val client = OpenAiClient(KoogCompletionTransport(dns = Dns { lookups++; error("Must not resolve unsupported providers") }))
        for (url in listOf("https://api.openai.com/v1", "https://openrouter.ai/api/v1", "https://gateway.example/v1", "https://api.deepseek.com.evil.test/v1")) {
            val error = try { client.solve(ApiSettings(baseUrl = url, apiKey = "synthetic-key", model = "test-model"), emptyList(), "6*7"); null }
                catch (e: ApiConfigurationException) { e }
            assertNotNull(error)
            assertTrue(error!!.message.orEmpty().contains("только DeepSeek"))
            assertFalse(error.message.orEmpty().contains("synthetic-key"))
        }
        assertEquals(0, lookups)
    }

    @Test fun taskAndExampleImagesKeepOrderAndLabels() = runBlocking { serverTest { server ->
        server.enqueue(answer())
        val template = TemplatePrompt("Fixture", "Follow the example", listOf(
            TemplatePrompt.Example("EXAMPLE CONDITION", listOf("Y29uZGl0aW9u"), "EXAMPLE SOLUTION", listOf("c29sdXRpb24="))))
        OpenAiClient(generationTransport()).solve(settings(server).copy(supportsImages = true),
            listOf("dGFzazE=", "dGFzazI="), "TASK CONDITION", formatText = "FORMAT", formatSampleBase64 = "Zm9ybWF0", template = template)
        val messages = request(server)["messages"]!!.jsonArray
        val parts = messages.single { it.jsonObject["role"] == JsonPrimitive("user") }.jsonObject["content"]!!.jsonArray
        val images = parts.filter { it.jsonObject["type"] == JsonPrimitive("image_url") }
            .map { it.jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content }
        assertEquals(listOf("Y29uZGl0aW9u", "c29sdXRpb24=", "Zm9ybWF0", "dGFzazE=", "dGFzazI=")
            .map { "data:image/jpeg;base64,$it" }, images)
        assertTrue(parts.toString().contains("EXAMPLE CONDITION"))
        assertTrue(parts.toString().contains("EXAMPLE SOLUTION"))
        assertTrue(parts.toString().contains("TASK CONDITION"))
        assertEquals(1, server.requestCount)
    } }

    @Test fun absentOrNullFingerprintDoesNotDiscardValidAnswer() = runBlocking { serverTest { server ->
        for (fingerprint in listOf<JsonElement?>(null, JsonNull)) {
            server.enqueue(answer(fingerprint))
            assertEquals("42", OpenAiClient(generationTransport()).solve(settings(server), emptyList(), "6*7").answer)
        }
        assertEquals(2, server.requestCount)
    } }

    @Test fun stalledHeadersAndBodyProduceSafeTimeoutWithoutRetry() = runBlocking { serverTest { server ->
        for (timeout in listOf(ConnectionTimeoutConfig(500, 1000, 4000), ConnectionTimeoutConfig(4000, 1000, 500))) {
          for (delayBody in listOf(false, true)) {
            val response = answer()
            if (delayBody) response.setBodyDelay(2, TimeUnit.SECONDS) else response.setHeadersDelay(2, TimeUnit.SECONDS)
            server.enqueue(response)
            val client = OpenAiClient(KoogCompletionTransport(timeoutConfig = timeout))
            val before = server.requestCount
            val error = try { client.solve(settings(server), emptyList(), "6*7"); null }
                catch (e: SocketTimeoutException) { e }
            assertNotNull(error)
            assertSanitized(error!!, SocketTimeoutException::class.java)
            assertTrue(requestError(error).contains("тайм-аут"))
            assertEquals(before + 1, server.requestCount)
          }
        }
    } }

    @Test fun dnsFailureIsSafeAndNeverReachesServer() = runBlocking { serverTest { server ->
        var lookups = 0
        val client = OpenAiClient(KoogCompletionTransport(dns = Dns {
            lookups++
            throw UnknownHostException("PRIVATE_DNS_DETAIL synthetic-key")
        }))
        // A hostname, not an IP literal, ensures the real OkHttp DNS path is exercised.
        val error = try { client.solve(settings(server).copy(baseUrl = "https://api.deepseek.com"), emptyList(), "6*7"); null }
            catch (e: UnknownHostException) { e }
        assertNotNull(error)
        assertSanitized(error!!, UnknownHostException::class.java)
        assertEquals("Нет соединения с интернетом", requestError(error))
        assertEquals(1, lookups)
        assertEquals(0, server.requestCount)
    } }

    @Test fun disconnectDuringBodyDoesNotPublishPartialAnswerOrRetry() = runBlocking { serverTest { server ->
        server.enqueue(answer().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val error = try { OpenAiClient(generationTransport()).solve(settings(server), emptyList(), "6*7"); null }
            catch (e: ConnectException) { e }
        assertNotNull(error)
        assertSanitized(error!!, ConnectException::class.java)
        assertTrue(requestError(error).contains("подключиться"))
        assertEquals(1, server.requestCount)
    } }

    private fun assertSanitized(error: Throwable, type: Class<out Throwable>) {
        generateSequence(error) { it.cause }.take(12).forEach {
            assertTrue(type.isInstance(it))
            assertTrue(it.message.isNullOrBlank())
        }
    }

    @Test fun missingFingerprintDoesNotRepairBrokenEnvelope() = runBlocking { serverTest { server ->
        for (body in listOf("{broken", """{"choices":[]}""")) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
            val before = server.requestCount
            val error = try { OpenAiClient(generationTransport()).solve(settings(server), emptyList(), "6*7"); null }
                catch (e: ApiException) { e }
            assertNotNull(error)
            assertFalse(error!!.message.orEmpty().contains(body))
            assertNull(error.cause)
            assertEquals(before + 1, server.requestCount)
        }
    } }

    private fun settings(server: MockWebServer) = ApiSettings(
        baseUrl = server.url("/v1").newBuilder().host("127.0.0.1").build().toString(),
        apiKey = "synthetic-key", model = "deepseek-chat", reasoningFormat = ReasoningFormat.THINKING_TYPE,
    )
    private fun reply(message: JsonObject, fingerprint: JsonElement? = JsonPrimitive("synthetic")) = MockResponse().setHeader("Content-Type", "application/json").setBody(buildJsonObject {
        put("id", "test"); put("object", "chat.completion"); put("created", 1); put("model", "deepseek-chat")
        if (fingerprint != null) put("system_fingerprint", fingerprint)
        put("choices", buildJsonArray { add(buildJsonObject {
            put("index", 0); put("message", message); put("finish_reason", if (message.containsKey("tool_calls")) "tool_calls" else "stop")
        }) })
        put("usage", buildJsonObject { put("prompt_tokens", 1); put("completion_tokens", 1); put("total_tokens", 2) })
    }.toString())
    private fun answer(fingerprint: JsonElement? = JsonPrimitive("synthetic")) = reply(buildJsonObject {
        put("role", "assistant"); put("content", """{"problem":"6*7","answer":"42","steps":[{"title":"Умножение","content":"6*7=42"}]}""")
    }, fingerprint)
    private suspend fun serverTest(block: suspend (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try { block(server) } finally { server.shutdown() }
    }
    private fun request(server: MockWebServer): JsonObject {
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer synthetic-key", request.getHeader("Authorization"))
        return Json.parseToJsonElement(request.body.readUtf8()).jsonObject
    }

    @Test fun generationPreservesTaskImagesAndThinking() = runBlocking { serverTest { server ->
        for (thinking in listOf(true, false)) {
            server.enqueue(answer())
            val result = OpenAiClient(generationTransport()).solve(settings(server).copy(thinkingEnabled = thinking, supportsImages = true),
                listOf("YWJj"), "6*7")
            assertEquals("42", result.answer)
            assertEquals("Умножение", result.steps.single().title)
            val body = request(server)
            assertEquals("deepseek-chat", body["model"]!!.jsonPrimitive.content)
            assertEquals(if (thinking) "enabled" else "disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertTrue(body["messages"].toString().contains("data:image/jpeg;base64,YWJj"))
        }
    } }

    @Test fun actualLibraryLoopSearchesReadsAndAccountsForEveryPost() = runBlocking { serverTest { server ->
        val exampleId = "11111111-1111-1111-1111-111111111111"
        val tools = LibraryTools { LibraryCatalog(listOf(TaskTemplate(name = "Электричество", examples = listOf(
            TemplateExample("Резистор 2 Ом, напряжение 6 В", solution = "I=U/R", answer = "3 A", library = LibraryMetadata(id = exampleId)))))) }
        fun call(name: String, id: String, args: String) = reply(buildJsonObject {
            put("role", "assistant"); put("content", JsonNull); put("reasoning_content", "private-reason-$id")
            put("tool_calls", buildJsonArray { add(buildJsonObject {
                put("id", id); put("type", "function"); put("function", buildJsonObject { put("name", name); put("arguments", args) })
            }) })
        }, fingerprint = null)
        server.enqueue(call("search_examples", "c1", """{"query":"резистор"}"""))
        server.enqueue(call("read_example", "c2", """{"id":"$exampleId","version":1}"""))
        server.enqueue(answer())
        val result = OpenAiClient(generationTransport()).solve(settings(server), emptyList(), "Найди похожую задачу про резистор", libraryTools = tools)
        assertEquals(3, result.agentReport!!.posts)
        assertEquals(exampleId, result.agentReport.examples.single().id)
        assertFalse(result.toString().contains("private-reason"))
        val first = request(server)
        val parameters = first["tools"]!!.jsonArray.first().jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals(false, parameters["additionalProperties"]!!.jsonPrimitive.boolean)
        assertEquals(2000, parameters["properties"]!!.jsonObject["query"]!!.jsonObject["maxLength"]!!.jsonPrimitive.int)
        val second = request(server)
        assertTrue(second["messages"].toString().contains("private-reason-c1"))
        val third = request(server)
        assertTrue(third["messages"].toString().contains("I=U/R"))
        assertTrue(third["messages"].toString().contains("private-reason-c2"))
        assertEquals("none", third["tool_choice"]!!.jsonPrimitive.content)
    } }

    @Test fun httpFollowupsNeverReplayCountedPost() = runBlocking { serverTest { server ->
        for (code in listOf(503, 307)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After", "0")
                .setHeader("Location", server.url("/redirect")))
            val before = server.requestCount
            val error = try { OpenAiClient(generationTransport()).solve(settings(server), emptyList(), "6*7"); null }
                catch (e: ApiException) { e }
            assertEquals(code, error?.httpCode)
            assertEquals(before + 1, server.requestCount)
            request(server)
        }
    } }

    @Test fun generationCancellationCancelsNetwork() = runBlocking { serverTest { server ->
        server.enqueue(answer().setHeadersDelay(4, TimeUnit.SECONDS))
        val job = launch(Dispatchers.Default) { OpenAiClient(generationTransport()).solve(settings(server), emptyList(), "6*7") }
        withContext(Dispatchers.IO) { requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        val start = System.nanoTime()
        job.cancelAndJoin()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
        assertTrue(job.isCancelled)
        assertEquals(1, server.requestCount)
    } }

    @Test fun malformedArgumentsReachToolsAndModelCanCorrectThem() = runBlocking {
        for (bad in listOf("{bad", "[]", "null", "", "42")) serverTest { server ->
            fun call(id: String, args: String) = reply(buildJsonObject {
                put("role", "assistant"); put("content", JsonNull); put("reasoning_content", "reason-$id")
                put("tool_calls", buildJsonArray { add(buildJsonObject {
                    put("id", id); put("type", "function")
                    put("function", buildJsonObject { put("name", "search_examples"); put("arguments", args) })
                }) })
            })
            server.enqueue(call("bad-call", bad))
            server.enqueue(call("fixed-call", """{"query":"резистор"}"""))
            server.enqueue(answer())
            val result = OpenAiClient(generationTransport()).solve(settings(server), emptyList(), "Найди похожую задачу",
                libraryTools = LibraryTools { LibraryCatalog(emptyList()) })
            assertEquals("42", result.answer)
            assertEquals(3, result.agentReport!!.posts)
            assertEquals(3, server.requestCount)
            request(server)
            val second = request(server)["messages"]!!.jsonArray.map { it.jsonObject }
            val toolResult = second.single { it["role"] == JsonPrimitive("tool") }
            assertEquals("invalid_arguments", Json.parseToJsonElement(toolResult["content"]!!.jsonPrimitive.content).jsonObject["status"]!!.jsonPrimitive.content)
            val assistant = second.single { it["role"] == JsonPrimitive("assistant") }
            assertEquals(bad, assistant["tool_calls"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
            assertEquals("reason-bad-call", assistant["reasoning_content"]!!.jsonPrimitive.content)
            val last = request(server)
            assertEquals("none", last["tool_choice"]!!.jsonPrimitive.content)
            assertTrue(last["messages"].toString().contains("no_match"))
        }
    }

    @Test fun repeatedMalformedCallsStillStopAtFinalRound() = runBlocking { serverTest { server ->
        repeat(3) { round -> server.enqueue(reply(buildJsonObject {
            put("role", "assistant"); put("content", JsonNull); put("reasoning_content", "reason-$round")
            put("tool_calls", buildJsonArray { add(buildJsonObject {
                put("id", "call-$round"); put("type", "function")
                put("function", buildJsonObject { put("name", "search_examples"); put("arguments", "[]") })
            }) })
        })) }
        val error = try {
            OpenAiClient(generationTransport()).solve(settings(server), emptyList(), "Найди похожую",
                libraryTools = LibraryTools { LibraryCatalog(emptyList()) }); null
        } catch (e: LibraryAgentException) { e }
        assertNotNull(error)
        assertEquals(3, server.requestCount)
        request(server); request(server)
        assertEquals("none", request(server)["tool_choice"]!!.jsonPrimitive.content)
    } }

    @Test fun disconnectedServerDoesNotResendOrExposeExceptionCause() = runBlocking { serverTest { server ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val error = try { OpenAiClient(generationTransport()).solve(settings(server), emptyList(), "6*7"); null }
            catch (e: ConnectException) { e }
        assertNotNull(error)
        assertSafeNetworkError(error!!)
        assertEquals(1, server.requestCount)
    } }

    @Test fun refusedConnectionKeepsNetworkClassificationWithoutRemoteDetails() = runBlocking {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val error = try {
            OpenAiClient(generationTransport()).solve(ApiSettings(baseUrl = "https://127.0.0.1:$port",
                apiKey = "synthetic-key", model = "deepseek-chat"), emptyList(), "6*7"); null
        } catch (e: ConnectException) { e }
        assertNotNull(error)
        assertSafeNetworkError(error!!)
    }

    private fun assertSafeNetworkError(error: ConnectException) {
        // Coroutine stack recovery may copy a Java exception and link the sanitized original.
        // No SDK/body/address/cause from the actual failure may survive that boundary.
        generateSequence<Throwable>(error) { it.cause }.take(12).forEach {
            assertTrue(it is ConnectException)
            assertTrue(it.message.isNullOrBlank())
        }
    }

    // A raw server error must never reach the saved error/UI, including its cause.
    @Test fun serverErrorIsSafeAtGenerationBoundary() = runBlocking {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            server.enqueue(MockResponse().setResponseCode(429).setBody("PRIVATE_SERVER_BODY"))
            val settings = ApiSettings(baseUrl = server.url("/v1").newBuilder().host("127.0.0.1").build().toString(), apiKey = "synthetic-key", model = "deepseek-chat")
            val client = OpenAiClient(generationTransport())
            val error = try { client.solve(settings, emptyList(), "6*7"); null } catch (e: ApiException) { e }
            assertNotNull(error)
            assertEquals(error!!.message, 429, error.httpCode)
            assertFalse(error.message.orEmpty().contains("PRIVATE_SERVER_BODY"))
            assertNull(error.cause)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
