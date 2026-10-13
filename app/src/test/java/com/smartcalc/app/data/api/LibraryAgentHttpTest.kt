package com.smartcalc.app.data.api

import com.smartcalc.app.data.settings.ApiSettings
import java.net.ServerSocket
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

private class AgentServer(replies: List<Pair<Int, JsonObject>>) : AutoCloseable {
    val requests = CopyOnWriteArrayList<JsonObject>()
    private val pending = ArrayDeque(replies)
    private val server = ServerSocket(0)
    private val worker = thread(isDaemon = true, name = "library-agent-http-test") {
        while (!server.isClosed) try { server.accept().use { socket ->
            socket.soTimeout = 10000
            val input = socket.getInputStream()
            val headers = ByteArrayOutputStream()
            var tail = 0
            while (tail != 0x0d0a0d0a) {
                val next = input.read(); check(next >= 0 && headers.size() < 32000)
                headers.write(next); tail = (tail shl 8) or next
            }
            val length = headers.toString("ISO-8859-1").lineSequence().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
            check(length in 0..4000000)
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) { val count = input.read(body, offset, length - offset); check(count > 0); offset += count }
            requests += Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject
            val (code, message) = if (pending.isEmpty()) 500 to buildJsonObject { } else pending.removeFirst()
            val response = if (code == 200) buildJsonObject { putJsonArray("choices") { add(buildJsonObject { put("message", message) }) } } else message
            val bytes = response.toString().toByteArray(Charsets.UTF_8)
            socket.getOutputStream().use { output ->
                output.write("HTTP/1.1 $code Response\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.write(bytes)
            }
        } } catch (e: Exception) { if (!server.isClosed) throw e }
    }
    val settings get() = ApiSettings(baseUrl = "http://127.0.0.1:${server.localPort}", apiKey = "fixture-token", model = "fixture-model")
    override fun close() { server.close(); worker.join(1000) }
}

class LibraryAgentHttpTest {
    @Test fun providerEchoedCredentialNeverEntersResultOrProgress() = runBlocking {
        val echoed = buildJsonObject { put("role", "assistant"); put("content", """{"problem":"fixture-token","answer":"fixture-token","steps":[{"title":"fixture-token","content":"fixture-token"}]}""") }
        AgentServer(listOf(200 to echoed)).use { server ->
            val progress = mutableListOf<com.smartcalc.app.data.library.agent.LibraryAgentReport>()
            val result = OpenAiClient().solve(server.settings, emptyList(), "Задача", libraryTools = agentTools(), agentProgress = { progress += it })
            assertFalse(result.toString().contains("fixture-token"))
            assertEquals("[скрыто]", result.answer)
            assertFalse(progress.toString().contains("fixture-token"))
        }
    }

    @Test fun actualHttpPreservesFormatAndTemplateAndToolReplyContract() = runBlocking {
        AgentServer(listOf(200 to toolTurn(), 200 to toolTurn("read_example", "read-1", """{"id":"$AGENT_EXAMPLE_ID","version":1}"""), 200 to finalTurn())).use { server ->
            val result = OpenAiClient().solve(server.settings, emptyList(), "Найди похожую задачу про резистор",
                formatText = "По шагам", template = TemplatePrompt("Образец", "Использовать закон Ома", emptyList()), libraryTools = agentTools())
            assertEquals("4 A", result.answer)
            assertEquals(3, result.agentReport!!.posts)
            assertEquals(AGENT_EXAMPLE_ID, result.agentReport!!.examples.single().id)
            assertTrue(server.requests.first().containsKey("tools"))
            val user = server.requests.first()["messages"].toString()
            assertTrue(user.contains("По шагам")); assertTrue(user.contains("Использовать закон Ома"))
            assertEquals("search-1", server.requests[1]["messages"]!!.jsonArray.last().jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        }
    }

    @Test fun unsupportedToolsAreExplicitAndNoSilentSingleAnswerFallback() = runBlocking {
        AgentServer(listOf(422 to buildJsonObject { }, 400 to buildJsonObject { })).use { server ->
            try { OpenAiClient().solve(server.settings, emptyList(), "Найди похожую", libraryTools = agentTools()); fail() }
            catch (e: LibraryAgentException) { assertTrue(e.message!!.contains("инструмент")) }
            assertEquals(2, server.requests.size)
            assertTrue(server.requests.all { it.containsKey("tools") })
            assertFalse(server.requests.last().containsKey("response_format"))
        }
    }

    @Test fun legacySingleRequestRemainsWithoutTools() = runBlocking {
        AgentServer(listOf(200 to finalTurn())).use { server ->
            val result = OpenAiClient().solve(server.settings, emptyList(), "Обычная задача")
            assertNull(result.agentReport)
            assertEquals(1, server.requests.size)
            assertFalse(server.requests.single().containsKey("tools"))
        }
    }
}
