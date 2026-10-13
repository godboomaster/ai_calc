package com.smartcalc.app.data.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Messages originate locally and can be shown without exposing a server response or credentials. */
internal class ApiConfigurationException(message: String) : ApiException(message)

/** Named tool_choice is rejected by DeepSeek's default-enabled thinking mode. */
internal fun officialDeepSeekTools(baseUrl: String): Boolean = apiEndpoint(baseUrl).host == "api.deepseek.com"

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
