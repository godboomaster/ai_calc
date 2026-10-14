package com.smartcalc.app.data.api

import kotlinx.serialization.json.*
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Per completion, never shared between requests. Compatibility for Koog 1.3's response DTOs.
 * Only the SDK's view receives {}. The loop receives the untouched calls and validates them,
 * returning invalid_arguments to the model within its existing POST/round budget.
 * Missing/null fingerprint becomes an empty SDK-only metadata value; it is not used by the app.
 * No network retry, invented tool call, argument repair, or persisted raw response.
 */
internal class KoogResponseBridge : Interceptor {
    var originalCalls: JsonArray? = null
        private set

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return response
        val body = response.body ?: return response
        val type = body.contentType()
        val text = body.string()
        val safeForSdk = adapt(text)
        val replacement = safeForSdk.toResponseBody(type)
        return response.newBuilder().removeHeader("Transfer-Encoding")
            .header("Content-Length", replacement.contentLength().toString()).body(replacement).build()
    }

    private fun adapt(text: String): String {
        // Do not invent choices/content or repair malformed envelopes.
        val root = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: IllegalArgumentException) { null }
            ?: return text
        val choices = root["choices"] as? JsonArray ?: return text
        val firstMessage = (choices.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
        originalCalls = firstMessage?.get("tool_calls") as? JsonArray
        val missingFingerprint = root["system_fingerprint"] == null || root["system_fingerprint"] == JsonNull
        var changed = missingFingerprint
        val adapted = choices.map { element ->
            val choice = element as? JsonObject ?: return@map element
            val message = choice["message"] as? JsonObject ?: return@map element
            val calls = message["tool_calls"] as? JsonArray ?: return@map element
            val safeCalls = calls.map call@{ raw ->
                val call = raw as? JsonObject ?: return@call raw
                val function = call["function"] as? JsonObject ?: return@call raw
                val args = (function["arguments"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@call raw
                val isObject = try { Json.parseToJsonElement(args) is JsonObject } catch (_: IllegalArgumentException) { false }
                if (isObject) return@call raw
                changed = true
                JsonObject(call + ("function" to JsonObject(function + ("arguments" to JsonPrimitive("{}")))))
            }
            JsonObject(choice + ("message" to JsonObject(message + ("tool_calls" to JsonArray(safeCalls)))))
        }
        if (!changed) return text
        val compatible = root.toMutableMap()
        compatible["choices"] = JsonArray(adapted)
        if (missingFingerprint) compatible["system_fingerprint"] = JsonPrimitive("")
        return JsonObject(compatible).toString()
    }
}
