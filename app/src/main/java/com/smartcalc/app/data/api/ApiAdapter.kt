package com.smartcalc.app.data.api

import com.smartcalc.app.data.settings.ApiConnection
import com.smartcalc.app.data.settings.ApiFormat
import com.smartcalc.app.data.settings.ApiSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val MIMO_THINKING_MODELS = Regex("mimo-v2\\.5(?:-pro)?|mimo-v2\\.6-(?:flash|pro|pro-ultraspeed)")
private val GLM_HOSTS = setOf("api.z.ai", "open.bigmodel.cn")

/**
 * One provider per row; a host starting with "." matches subdomains. A null model pattern accepts any model.
 * [suggest]: a new model on this host most likely takes the same reasoning field.
 */
private class KnownProvider(val name: String, val hosts: Set<String>, val models: Regex?, val format: ApiFormat,
    val suggest: Boolean = true) {
    fun hasHost(host: String) = hosts.any { if (it.startsWith('.')) host.endsWith(it) else host == it }
    fun matches(host: String, model: String) = hasHost(host) && models?.matches(model) != false
}

/**
 * Automatic format: only documented hosts and models. An unknown model on a known host keeps the server mode;
 * the user can still pick the suggested format for it, without a new release.
 */
private val KNOWN_PROVIDERS = listOf(
    KnownProvider("DeepSeek", setOf("api.deepseek.com"), null, ApiFormat.THINKING_TYPE),
    KnownProvider("Xiaomi MiMo", setOf("api.xiaomimimo.com", "token-plan-cn.xiaomimimo.com"), MIMO_THINKING_MODELS, ApiFormat.THINKING_TYPE),
    KnownProvider("Kimi", setOf("api.moonshot.ai", "api.moonshot.cn", "api.kimi.ai"), Regex("kimi-k2\\.5.*"), ApiFormat.THINKING_TYPE),
    KnownProvider("GLM", GLM_HOSTS, Regex("glm-(?:4\\.[567]|5(?:\\.[123])?)(?:-.*)?"), ApiFormat.THINKING_TYPE),
    KnownProvider("Qwen", setOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com", "dashscope-us.aliyuncs.com", ".maas.aliyuncs.com"),
        Regex("qwen-plus|qwen-flash|qwen3\\.8-max"), ApiFormat.ENABLE_THINKING),
    // Most OpenAI models, the default gpt-4o-mini among them, reject reasoning_effort.
    KnownProvider("OpenAI", setOf("api.openai.com"), Regex("(?!.*(?:chat|pro))(?:gpt-[56].*|o[134](?:-.*)?)"), ApiFormat.REASONING_EFFORT,
        suggest = false),
)

/** Models that always reason: «off» sends their lowest documented effort instead of an invalid "none"/"disabled". */
private val ALWAYS_REASONING = listOf(
    Regex("gpt-5(?:-(?:mini|nano))?(?:-20\\d.*)?") to "minimal",
    Regex("gpt-6.*|o[134](?:-.*)?|glm-5\\.3.*") to "low",
)

/**
 * Provider differences behind one OpenAI-compatible chat request: the reasoning field and the tool-call rules.
 * Solving, library search and history stay shared; only these answers differ per provider.
 */
internal data class ApiAdapter(
    val format: ApiFormat,
    /** Detected by address and model; null when the format was chosen manually or nothing matched. */
    val provider: String? = null,
    /** In automatic mode: the format the address points to when its model is unknown. */
    val suggestedFormat: ApiFormat? = null,
    /** Request fields for the reasoning switch; null when the app leaves reasoning to the server. */
    val reasoningOn: JsonObject? = null,
    val reasoningOff: JsonObject? = null,
    val canDisableReasoning: Boolean = true,
    val requireReasoning: Boolean = false,
    val autoToolsOnly: Boolean = false,
    val chatToolsSupported: Boolean = true,
) {
    val controlsReasoning get() = reasoningOn != null

    /** Tool turns follow the thinking protocol: replayed reasoning_content and auto tool choice. */
    fun thinkingToolMode(settings: ApiSettings) =
        if (controlsReasoning) settings.thinkingEnabled || !canDisableReasoning else requireReasoning

    fun configure(settings: ApiSettings, body: JsonObject): JsonObject {
        val fields = body.toMutableMap()
        (if (settings.thinkingEnabled) reasoningOn else reasoningOff)?.let { fields.putAll(it) }
        // GLM and MiMo support only auto. Finalization without tools preserves local round/POST guards.
        if (autoToolsOnly && fields["tool_choice"] == JsonPrimitive("none")) {
            fields.remove("tools"); fields.remove("tool_choice")
        }
        return JsonObject(fields)
    }
}

internal fun apiAdapter(settings: ApiSettings): ApiAdapter {
    val host = apiEndpoint(settings.baseUrl).host
    val model = settings.model.trim().lowercase()
    val mimo = MIMO_THINKING_MODELS.matches(model)
    val detected = KNOWN_PROVIDERS.firstOrNull { it.matches(host, model) }
    val auto = settings.apiFormat == ApiFormat.AUTO
    val format = if (auto) detected?.format ?: ApiFormat.SERVER_DEFAULT else settings.apiFormat
    val minEffort = ALWAYS_REASONING.firstOrNull { it.first.matches(model) }?.second
    val reasoning = reasoningFields(format, minEffort)
    return ApiAdapter(format,
        provider = detected?.name?.takeIf { auto },
        suggestedFormat = if (auto && detected == null) KNOWN_PROVIDERS.firstOrNull { it.hasHost(host) && it.suggest }?.format else null,
        reasoningOn = reasoning?.first, reasoningOff = reasoning?.second,
        canDisableReasoning = minEffort == null,
        requireReasoning = host == "api.deepseek.com" || model.startsWith("deepseek-") || mimo,
        autoToolsOnly = host in GLM_HOSTS || model.startsWith("glm-") || mimo,
        chatToolsSupported = !model.startsWith("gpt-6"))
}

/** Request fields for switch on/off. [minEffort] marks a model that keeps reasoning when switched off. */
private fun reasoningFields(format: ApiFormat, minEffort: String?): Pair<JsonObject, JsonObject>? {
    fun thinking(type: String, effort: String? = null) = buildJsonObject {
        put("thinking", buildJsonObject { put("type", type) })
        effort?.let { put("reasoning_effort", it) }
    }
    return when (format) {
        ApiFormat.THINKING_TYPE ->
            if (minEffort == null) thinking("enabled") to thinking("disabled")
            else thinking("enabled", "high") to thinking("enabled", minEffort)
        ApiFormat.ENABLE_THINKING -> buildJsonObject { put("enable_thinking", true) } to buildJsonObject { put("enable_thinking", false) }
        ApiFormat.REASONING_EFFORT -> buildJsonObject { put("reasoning_effort", "high") } to buildJsonObject { put("reasoning_effort", minEffort ?: "none") }
        ApiFormat.AUTO, ApiFormat.SERVER_DEFAULT -> null
    }
}

/** Shared by solution/follow-up/test requests and document recognition. */
internal fun configuredApiBody(settings: ApiSettings, body: JsonObject): JsonObject = apiAdapter(settings).configure(settings, body)

/**
 * Photos of the task, samples or a library source go only to a connection marked as reading them.
 * Otherwise nothing is sent: the user picks another connection and retries.
 */
internal fun requireImageSupport(settings: ApiSettings, messages: JsonArray) {
    if (settings.supportsImages || messages.none(::hasImage)) return
    val connection = settings.connectionName.takeIf { it.isNotBlank() }?.let { "Подключение «$it»" } ?: "Выбранное подключение"
    // Fits the calculator display; the switcher above it lists connections that take photos.
    throw ApiConfigurationException("$connection не принимает фото. Выберите другое или включите фото в его настройках.")
}

private fun hasImage(message: JsonElement): Boolean =
    ((message as? JsonObject)?.get("content") as? JsonArray).orEmpty().any {
        (it as? JsonObject)?.get("type") == JsonPrimitive("image_url")
    }

/** Name for lists: the user's own, else the provider by address, else the host. */
val ApiConnection.displayName: String
    get() = name.trim().ifBlank { providerName(baseUrl) ?: "Подключение" }

internal fun providerName(baseUrl: String): String? {
    val host = baseUrl.trim().toHttpUrlOrNull()?.host ?: return null
    return KNOWN_PROVIDERS.firstOrNull { it.hasHost(host) }?.name ?: host
}
