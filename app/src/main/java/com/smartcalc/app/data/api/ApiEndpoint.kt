package com.smartcalc.app.data.api

import com.smartcalc.app.data.settings.ApiSettings
import com.smartcalc.app.data.settings.ReasoningFormat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Messages originate locally and can be shown without exposing a server response or credentials. */
internal class ApiConfigurationException(message: String) : ApiException(message)

/** Official DeepSeek uses auto tool choice and requires reasoning_content in thinking tool turns. */
internal fun officialDeepSeekTools(baseUrl: String): Boolean = apiEndpoint(baseUrl).host == "api.deepseek.com"

internal data class ReasoningCapability(
    val format: ReasoningFormat,
    val offEffort: String = "none",
    val canDisable: Boolean = true,
    val requireReasoning: Boolean = false,
    val autoToolsOnly: Boolean = false,
    val chatToolsSupported: Boolean = true,
) {
    val controlled get() = format !in setOf(ReasoningFormat.AUTO, ReasoningFormat.SERVER_DEFAULT)
}

/** Conservative auto detection; custom gateways can explicitly select their documented format. */
internal fun reasoningCapability(settings: ApiSettings): ReasoningCapability {
    val host = apiEndpoint(settings.baseUrl).host
    val model = settings.model.trim().lowercase()
    val glm = host in setOf("api.z.ai", "open.bigmodel.cn") || model.startsWith("glm-")
    val openaiModel = model.startsWith("gpt-5") || model.startsWith("gpt-6") ||
        model.matches(Regex("o[134](?:-.*)?"))
    val format = if (settings.reasoningFormat != ReasoningFormat.AUTO) settings.reasoningFormat else when {
        host == "api.deepseek.com" -> ReasoningFormat.THINKING_TYPE
        host in setOf("api.moonshot.ai", "api.moonshot.cn", "api.kimi.ai") && model.startsWith("kimi-k2.5") -> ReasoningFormat.THINKING_TYPE
        host in setOf("api.z.ai", "open.bigmodel.cn") && model.matches(Regex("glm-(?:4\\.[567]|5(?:\\.[123])?)(?:-.*)?")) -> ReasoningFormat.THINKING_TYPE
        (host in setOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com", "dashscope-us.aliyuncs.com") || host.endsWith(".maas.aliyuncs.com")) &&
            model in setOf("qwen-plus", "qwen-flash", "qwen3.8-max") -> ReasoningFormat.ENABLE_THINKING
        host == "api.openai.com" && openaiModel && !model.contains("chat") && !model.contains("pro") -> ReasoningFormat.REASONING_EFFORT
        else -> ReasoningFormat.SERVER_DEFAULT
    }
    val glmAlways = glm && model.startsWith("glm-5.3")
    val off = when {
        model.matches(Regex("gpt-5(?:-(?:mini|nano))?(?:-20\\d.*)?")) -> "minimal"
        model.startsWith("gpt-6") || model.matches(Regex("o[134](?:-.*)?")) || glmAlways -> "low"
        else -> "none"
    }
    return ReasoningCapability(format, off, canDisable = off == "none",
        requireReasoning = host == "api.deepseek.com" || model.startsWith("deepseek-"),
        autoToolsOnly = glm, chatToolsSupported = !model.startsWith("gpt-6"))
}

/** Shared by solution/follow-up/test requests and document recognition. */
internal fun configuredApiBody(settings: ApiSettings, body: JsonObject): JsonObject {
    val capability = reasoningCapability(settings)
    val fields = body.toMutableMap()
    when (capability.format) {
        ReasoningFormat.THINKING_TYPE -> {
            fields["thinking"] = buildJsonObject { put("type", if (settings.thinkingEnabled || !capability.canDisable) "enabled" else "disabled") }
            if (!capability.canDisable) fields["reasoning_effort"] = kotlinx.serialization.json.JsonPrimitive(if (settings.thinkingEnabled) "high" else capability.offEffort)
        }
        ReasoningFormat.ENABLE_THINKING -> fields["enable_thinking"] = kotlinx.serialization.json.JsonPrimitive(settings.thinkingEnabled)
        ReasoningFormat.REASONING_EFFORT -> fields["reasoning_effort"] = kotlinx.serialization.json.JsonPrimitive(if (settings.thinkingEnabled) "high" else capability.offEffort)
        else -> Unit
    }
    // GLM documents only auto. Finalization without tools preserves our local round/POST guards.
    if (capability.autoToolsOnly && fields["tool_choice"] == kotlinx.serialization.json.JsonPrimitive("none")) {
        fields.remove("tools"); fields.remove("tool_choice")
    }
    return JsonObject(fields)
}

internal fun apiEndpoint(baseUrl: String): HttpUrl {
    val url = (baseUrl.trim().trimEnd('/') + "/chat/completions").toHttpUrlOrNull()
        ?: throw ApiConfigurationException("Некорректный адрес API. Проверьте Base URL в настройках.")
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
        throw ApiConfigurationException("Укажите адрес API без логина и пароля; ключ вводится отдельно.")
    }
    // Never resolve a remote hostname to decide whether it may receive a key over HTTP.
    val local = url.host in setOf("localhost", "127.0.0.1", "::1")
    if (!url.isHttps && !local) {
        throw ApiConfigurationException("Для внешнего API нужен адрес HTTPS, чтобы защитить API-ключ.")
    }
    return url
}
