package com.smartcalc.experiments.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.deepseek.*
import ai.koog.prompt.message.MessagePart
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Shared JVM/Android HTTP checks: loopback only, synthetic key and responses. */
class KoogHttpContractTest {
    private val model = DeepSeekModels.DeepSeekV4Flash
    private val tools = listOf("search_examples", "read_example").map { ToolDescriptor(it, "Synthetic tool") }
    private val params = DeepSeekParams(additionalProperties = mapOf(
        "thinking" to buildJsonObject { put("type", "enabled") },
        "response_format" to buildJsonObject { put("type", "json_object") },
    ))
    private fun response(message: JsonObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(
        buildJsonObject {
            put("id", "synthetic-response"); put("object", "chat.completion"); put("created", 1)
            put("model", model.id); put("system_fingerprint", "synthetic")
            put("choices", buildJsonArray { add(buildJsonObject {
                put("index", 0); put("message", message); put("finish_reason", if (message.containsKey("tool_calls")) "tool_calls" else "stop")
            }) })
            put("usage", buildJsonObject { put("prompt_tokens", 1); put("completion_tokens", 1); put("total_tokens", 2) })
        }.toString()
    )
    private fun answer() = response(buildJsonObject { put("role", "assistant"); put("content", "{\"answer\":\"42\"}") })
    private fun call(name: String, id: String) = response(buildJsonObject {
        put("role", "assistant"); put("content", JsonNull); put("reasoning_content", "synthetic reasoning $id")
        put("tool_calls", buildJsonArray { add(buildJsonObject {
            put("id", id); put("type", "function"); put("function", buildJsonObject { put("name", name); put("arguments", "{}") })
        }) })
    })
    private suspend fun checkHttp(block: suspend (MockWebServer, DeepSeekLLMClient) -> Unit) {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val base = HttpClient(OkHttp)
        val client = DeepSeekLLMClient(
            apiKey = "synthetic-test-key",
            settings = DeepSeekClientSettings(baseUrl = server.url("/v1/").toString(),
                timeoutConfig = ConnectionTimeoutConfig(5000, 2000, 5000)),
            httpClientFactory = KtorKoogHttpClient.Factory(baseClient = base),
        )
        try { block(server, client) } finally { client.close(); base.close(); server.shutdown() }
    }
    private fun body(server: MockWebServer): JsonObject {
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer synthetic-test-key", request.getHeader("Authorization"))
        return Json.parseToJsonElement(request.body.readUtf8()).jsonObject
    }

    @Test fun responseAndParameters() = runBlocking {
        checkHttp { server, client ->
            for (enabled in listOf(true, false)) {
                server.enqueue(answer())
                val config = DeepSeekParams(additionalProperties = params.additionalProperties!! +
                    ("thinking" to buildJsonObject { put("type", if (enabled) "enabled" else "disabled") }))
                val result = client.execute(prompt("probe", params = config) { user("Return JSON for 6*7") }, model, emptyList())
                assertEquals("{\"answer\":\"42\"}", result.textContent())
                val request = body(server)
                assertEquals(if (enabled) "enabled" else "disabled", request["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
                assertEquals("json_object", request["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun searchReadAndAnswerKeepReasoning() = runBlocking {
        checkHttp { server, client ->
            server.enqueue(call("search_examples", "c1")); server.enqueue(call("read_example", "c2")); server.enqueue(answer())
            val first = client.execute(prompt("p1", params = params) { user("Find an example") }, model, tools)
            assertEquals("search_examples", first.parts.filterIsInstance<MessagePart.Tool.Call>().single().tool)
            body(server)
            val second = client.execute(prompt("p2", params = params) {
                user("Find an example"); message(first); toolResult("search_examples", "[]", "c1")
            }, model, tools)
            assertEquals("read_example", second.parts.filterIsInstance<MessagePart.Tool.Call>().single().tool)
            val secondBody = body(server)
            assertEquals("synthetic reasoning c1", secondBody["messages"]!!.jsonArray.first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }.jsonObject["reasoning_content"]!!.jsonPrimitive.content)
            val last = client.execute(prompt("p3", params = params) {
                user("Find an example"); message(first); toolResult("search_examples", "[]", "c1")
                message(second); toolResult("read_example", "Synthetic example", "c2")
            }, model, emptyList())
            assertEquals("{\"answer\":\"42\"}", last.textContent())
            val finalBody = body(server)
            assertEquals(2, finalBody["messages"]!!.jsonArray.count { it.jsonObject.containsKey("reasoning_content") })
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun serverErrorDoesNotRetry() = runBlocking {
        checkHttp { server, client ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("synthetic rate limit"))
            var caught: Throwable? = null
            try { client.execute(prompt("error") { user("test") }, model, emptyList()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { caught = e }
            val httpError = generateSequence(caught) { it.cause }.filterIsInstance<KoogHttpClientException>().firstOrNull()
            assertEquals(429, httpError?.statusCode)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cancellingRequestDoesNotWaitForDelayedResponse() = runBlocking {
        checkHttp { server, client ->
            server.enqueue(answer().setHeadersDelay(4, TimeUnit.SECONDS))
            val job = launch(Dispatchers.Default) { client.execute(prompt("cancel") { user("test") }, model, emptyList()) }
            withContext(Dispatchers.IO) { requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            val start = System.nanoTime()
            job.cancelAndJoin()
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("Cancellation took $elapsed ms", elapsed < 1500)
            assertTrue(job.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }
}
