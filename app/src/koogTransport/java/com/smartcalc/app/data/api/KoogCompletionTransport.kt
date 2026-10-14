package com.smartcalc.app.data.api

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekParams
import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import com.smartcalc.app.data.settings.ApiSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.RequestBody
import okhttp3.Dns
import okio.BufferedSink
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Experimental DeepSeek transport, compiled only into the isolated opt-in debug build. */
internal class KoogCompletionTransport(
    private val timeoutConfig: ConnectionTimeoutConfig = ConnectionTimeoutConfig(180_000, 30_000, 120_000),
    private val dns: Dns = Dns.SYSTEM,
) : CompletionTransport {
    override suspend fun execute(settings: ApiSettings, body: JsonObject): JsonObject {
        val endpoint = apiEndpoint(settings.baseUrl)
        if (endpoint.host !in setOf("api.deepseek.com", "localhost", "127.0.0.1", "::1")) {
            throw ApiConfigurationException("Эксперимент Koog пока поддерживает только DeepSeek. Используйте обычную сборку для других провайдеров.")
        }
        val hasImages = (body["messages"] as? JsonArray).orEmpty().any { message ->
            ((message as? JsonObject)?.get("content") as? JsonArray).orEmpty().any { part ->
                (part as? JsonObject)?.get("type") == JsonPrimitive("image_url")
            }
        }
        if (hasImages && !settings.supportsImages) {
            throw ApiConfigurationException("Выберите модель с поддержкой фото и включите «Модель поддерживает изображения» в настройках. Запрос не отправлен.")
        }
        val responseBridge = KoogResponseBridge()
        val base = HttpClient(OkHttp) {
            followRedirects = false
            engine {
                config {
                    dns(dns)
                    retryOnConnectionFailure(false)
                    followRedirects(false)
                    followSslRedirects(false)
                    addInterceptor(responseBridge)
                    // OkHttp otherwise replays e.g. a 503 Retry-After: 0, outside agent accounting.
                    addInterceptor { chain ->
                        val request = chain.request()
                        val original = request.body
                        val singleUse = original?.let { delegate -> object : RequestBody() {
                            override fun contentType() = delegate.contentType()
                            override fun contentLength() = delegate.contentLength()
                            override fun isOneShot() = true
                            override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
                        } }
                        chain.proceed(request.newBuilder().method(request.method, singleUse).build())
                    }
                }
            }
        }
        try {
            val definitions = (body["tools"] as? JsonArray).orEmpty().map { it.jsonObject.getValue("function").jsonObject }
            val schemas = definitions.associate { it.getValue("name").jsonPrimitive.content to it.getValue("parameters").jsonObject }
            val descriptors = definitions.map { ToolDescriptor(it.getValue("name").jsonPrimitive.content, it["description"]?.jsonPrimitive?.content.orEmpty()) }
            val client = DeepSeekLLMClient(
                apiKey = settings.apiKey,
                settings = DeepSeekClientSettings(
                    baseUrl = endpoint.newBuilder().removePathSegment(endpoint.pathSegments.lastIndex)
                        .removePathSegment(endpoint.pathSegments.lastIndex - 1).build().toString().trimEnd('/') + "/",
                    timeoutConfig = timeoutConfig,
                ),
                httpClientFactory = KtorKoogHttpClient.Factory(baseClient = base),
                toolsConverter = object : OpenAICompatibleToolDescriptorSchemaGenerator() {
                    override fun generate(toolDescriptor: ToolDescriptor): JsonObject = schemas.getValue(toolDescriptor.name)
                },
            )
            try {
                val choice = when (val value = body["tool_choice"]) {
                    is JsonObject -> LLMParams.ToolChoice.Named(value.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
                    JsonPrimitive("none") -> LLMParams.ToolChoice.None
                    JsonPrimitive("auto") -> LLMParams.ToolChoice.Auto
                    else -> null
                }
                val params = DeepSeekParams(
                    maxTokens = body["max_tokens"]?.jsonPrimitive?.int,
                    toolChoice = choice,
                    additionalProperties = body.filterKeys { it !in setOf("model", "messages", "tools", "tool_choice", "max_tokens") },
                )
                val calls = mutableMapOf<String, String>()
                val input = prompt("smartcalc-generation", params = params) {
                    body.getValue("messages").jsonArray.forEach { raw ->
                        val message = raw.jsonObject
                        val content = message["content"]
                        when (message.getValue("role").jsonPrimitive.content) {
                            "system" -> system(content!!.jsonPrimitive.content)
                            "user" -> user {
                                if (content is JsonPrimitive) text(content.content)
                                else content!!.jsonArray.forEach { item ->
                                    val part = item.jsonObject
                                    when (part.getValue("type").jsonPrimitive.content) {
                                        "text" -> text(part.getValue("text").jsonPrimitive.content)
                                        "image_url" -> {
                                            val url = part.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
                                            require(url.startsWith("data:image/jpeg;base64,"))
                                            image(AttachmentSource.Image(AttachmentContent.Binary.Base64(url.substringAfter(',')), "jpeg"))
                                        }
                                        else -> error("Unsupported content part")
                                    }
                                }
                            }
                            "assistant" -> assistant {
                                (message["reasoning_content"] as? JsonPrimitive)?.contentOrNull?.let { reasoning(content = it) }
                                (content as? JsonPrimitive)?.contentOrNull?.let { text(it) }
                                (message["tool_calls"] as? JsonArray).orEmpty().forEach { rawCall ->
                                    val call = rawCall.jsonObject
                                    val function = call.getValue("function").jsonObject
                                    val id = call.getValue("id").jsonPrimitive.content
                                    val name = function.getValue("name").jsonPrimitive.content
                                    calls[id] = name
                                    toolCall(id = id, tool = name, args = function.getValue("arguments").jsonPrimitive.content)
                                }
                            }
                            "tool" -> {
                                val id = message.getValue("tool_call_id").jsonPrimitive.content
                                toolResult(calls.getValue(id), content!!.jsonPrimitive.content, id)
                            }
                            else -> error("Unsupported message role")
                        }
                    }
                }
                val capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools, LLMCapability.ToolChoice,
                    LLMCapability.Thinking) + if (settings.supportsImages) listOf(LLMCapability.Vision.Image) else emptyList()
                val result = client.execute(input, LLModel(provider = LLMProvider.DeepSeek, id = settings.model,
                    capabilities = capabilities), descriptors)
                return buildJsonObject {
                    put("role", "assistant")
                    put("content", result.textContent())
                    val reasoning = result.parts.filterIsInstance<MessagePart.Reasoning>()
                    if (reasoning.isNotEmpty()) put("reasoning_content", reasoning.flatMap { it.content }.joinToString("\n"))
                    val toolCalls = result.parts.filterIsInstance<MessagePart.Tool.Call>()
                    if (responseBridge.originalCalls != null) put("tool_calls", responseBridge.originalCalls!!)
                    else if (toolCalls.isNotEmpty()) put("tool_calls", buildJsonArray {
                        toolCalls.forEach { call -> add(buildJsonObject {
                            put("id", call.id?.let(::JsonPrimitive) ?: JsonNull)
                            put("type", "function")
                            put("function", buildJsonObject { put("name", call.tool); put("arguments", call.args) })
                        }) }
                    })
                }
            } finally { client.close() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val causes = generateSequence<Throwable>(e) { it.cause }.take(12).toList()
            val status = causes.filterIsInstance<KoogHttpClientException>().firstOrNull()?.statusCode
            if (status == null) {
                // Preserve UI classification, never SDK/remote messages or exception causes.
                if (causes.any { it is UnknownHostException }) throw UnknownHostException()
                if (causes.any { it is SocketTimeoutException || it is HttpRequestTimeoutException }) throw SocketTimeoutException()
                if (causes.any { it is IOException }) throw ConnectException()
            }
            // Do not retain SDK exceptions as causes: their messages can contain server bodies/keys.
            throw ApiException(if (status != null) "Ошибка API Koog (HTTP $status)." else
                "Koog: не удалось получить ответ. Проверьте соединение и совместимость модели.", status)
        } finally { base.close() }
    }
}
