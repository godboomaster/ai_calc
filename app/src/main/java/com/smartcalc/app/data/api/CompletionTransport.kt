package com.smartcalc.app.data.api

import com.smartcalc.app.data.settings.ApiSettings
import kotlinx.serialization.json.JsonObject

/** One counted completion; implementations must not retry or expose remote error bodies. */
fun interface CompletionTransport {
    suspend fun execute(settings: ApiSettings, body: JsonObject): JsonObject
}
