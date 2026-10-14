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
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class KoogGenerationTest {
    private fun settings(server: MockWebServer) = ApiSettings(
        baseUrl = server.url("/v1").newBuilder().host("127.0.0.1").build().toString(),
        apiKey = "synthetic-key", model = "deepseek-chat", reasoningFormat = ReasoningFormat.THINKING_TYPE,
    )
    private fun reply(message: JsonObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(buildJsonObject {
        put("id", "test"); put("object", "chat.completion"); put("created", 1); put("model", "deepseek-chat")
        put("system_fingerprint", "synthetic")
        put("choices", buildJsonArray { add(buildJsonObject {
            put("index", 0); put("message", message); put("finish_reason", if (message.containsKey("tool_calls")) "tool_calls" else "stop")
        }) })
        put("usage", buildJsonObject { put("prompt_tokens", 1); put("completion_tokens", 1); put("total_tokens", 2) })
    }.toString())
    private fun answer() = reply(buildJsonObject {
        put("role", "assistant"); put("content", """{"problem":"6*7","answer":"42","steps":[{"title":"Умножение","content":"6*7=42"}]}""")
    })
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
        })
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

    @Test fun malformedToolArgumentsFailSafelyWithoutRetry() = runBlocking { serverTest { server ->
        server.enqueue(reply(buildJsonObject {
            put("role", "assistant"); put("content", JsonNull)
            put("tool_calls", buildJsonArray { add(buildJsonObject {
                put("id", "bad-call"); put("type", "function")
                put("function", buildJsonObject { put("name", "search_examples"); put("arguments", "{PRIVATE_INVALID_ARGUMENTS") })
            }) })
        }))
        val error = try { OpenAiClient(generationTransport()).solve(settings(server), emptyList(), "6*7"); null }
            catch (e: ApiException) { e }
        assertNotNull(error)
        assertFalse(error!!.message.orEmpty().contains("PRIVATE_INVALID_ARGUMENTS"))
        assertNull(error.cause)
        assertEquals(1, server.requestCount)
    } }

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
