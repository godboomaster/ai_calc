import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.deepseek.DeepSeekParams
import ai.koog.prompt.executor.clients.openai.base.models.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** Tests the published serializer without credentials or a network transport. */
class DeepSeekContractTest {
    private val transport = Proxy.newProxyInstance(
        KoogHttpClient::class.java.classLoader, arrayOf(KoogHttpClient::class.java),
    ) { _, method, _ ->
        if (method.name == "getClientName") "offline-probe"
        else error("Unexpected transport call: ${method.name}")
    } as KoogHttpClient

    private fun body(params: DeepSeekParams, messages: List<OpenAIMessage> = emptyList(), tools: List<OpenAITool>? = null): JsonObject {
        val client = DeepSeekLLMClient(
            settings = DeepSeekClientSettings(baseUrl = "https://example.invalid"), httpClient = transport,
        )
        val serializer = DeepSeekLLMClient::class.java.declaredMethods.single {
            it.name == "serializeProviderChatRequest"
        }.apply { isAccessible = true }
        return Json.parseToJsonElement(serializer.invoke(
            client, messages, DeepSeekModels.DeepSeekV4Flash,
            tools, null, params, false,
        ) as String).jsonObject
    }

    @Test fun `thinking enabled and disabled require explicit extra fields`() {
        for (mode in listOf("enabled", "disabled")) {
            val request = body(DeepSeekParams(additionalProperties = mapOf(
                "thinking" to buildJsonObject { put("type", mode) },
            )))
            assertEquals(mode, request["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertFalse(request.containsKey("additionalProperties"))
        }
    }

    @Test fun `default client does not choose thinking mode`() {
        assertFalse(body(DeepSeekParams()).containsKey("thinking"))
    }

    @Test fun `JSON response mode is transmitted as a top level parameter`() {
        val request = body(DeepSeekParams(additionalProperties = mapOf(
            "response_format" to buildJsonObject { put("type", "json_object") },
        )))
        assertEquals("json_object", request["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun `library tool definitions survive serialization`() {
        val names = listOf("search_examples", "read_example")
        val request = body(DeepSeekParams(), tools = names.map {
            OpenAITool(OpenAIToolFunction(name = it, parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {})
            }))
        })
        assertEquals(names, request["tools"]!!.jsonArray.map {
            it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
        })
    }

    @Test fun `reasoning and subsequent tool call are merged without dropping reasoning`() {
        val request = body(DeepSeekParams(), messages = listOf(
            OpenAIMessage.Assistant(reasoningContent = "synthetic reasoning"),
            OpenAIMessage.Assistant(toolCalls = listOf(OpenAIToolCall(
                "call-1", OpenAIFunction("search_examples", "{}"),
            ))),
            OpenAIMessage.Tool(Content.Text("[]"), "call-1"),
        ))
        val messages = request["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("synthetic reasoning", messages[0].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("call-1", messages[1].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test fun `image content survives serializer without claiming model vision support`() {
        val url = "data:image/png;base64,c3ludGhldGlj"
        val request = body(DeepSeekParams(), messages = listOf(
            OpenAIMessage.User(Content.Parts(listOf(
                OpenAIContentPart.Image(OpenAIContentPart.ImageUrl(url)),
            ))),
        ))
        val part = request["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals(url, part["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }
}
