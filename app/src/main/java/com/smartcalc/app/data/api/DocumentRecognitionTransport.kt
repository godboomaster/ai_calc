package com.smartcalc.app.data.api

import com.smartcalc.app.data.settings.ApiSettings
import com.smartcalc.app.data.library.extract.TextExtractor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable data class RecognitionHttpResponse(val code: Int, val body: String)
interface RecognitionTransport {
    suspend fun post(settings: ApiSettings, body: JsonObject): RecognitionHttpResponse
}
class DocumentRecognitionTransport : RecognitionTransport {
    private val client = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).connectTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    override suspend fun post(settings: ApiSettings, body: JsonObject): RecognitionHttpResponse {
        require(settings.apiKey.isNotBlank()) { "API-ключ не задан" }
        val request = Request.Builder().url(apiEndpoint(settings.baseUrl))
            .header("Authorization", "Bearer ${settings.apiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val raw = it.body?.byteStream()?.use { stream -> TextExtractor.readLimited(stream, 2 * 1024 * 1024) } ?: ByteArray(0)
                            RecognitionHttpResponse(it.code, raw.toString(Charsets.UTF_8))
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                }
            })
        }
    }
}
