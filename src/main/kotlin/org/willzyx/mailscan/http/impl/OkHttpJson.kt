package org.willzyx.mailscan.http.impl

import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.willzyx.mailscan.http.ERROR_BODY_EXCERPT
import org.willzyx.mailscan.http.HttpStatusException
import org.willzyx.mailscan.http.JsonHttp

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * [JsonHttp] on OkHttp. A call blocks an IO thread rather than suspending on a non-blocking
 * transport, which is what the handful of requests per pass needs.
 */
class OkHttpJson(
    private val client: OkHttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : JsonHttp {
    override suspend fun post(url: HttpUrl, body: JsonElement, what: String): JsonElement =
        withContext(dispatcher) {
            val json = Json.encodeToString(JsonElement.serializer(), body)
            val request = Request.Builder().url(url).post(json.toRequestBody(JSON_MEDIA_TYPE)).build()
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw HttpStatusException(what, response.code, text)
                try {
                    Json.parseToJsonElement(text)
                } catch (e: SerializationException) {
                    throw IOException("$what returned a body that is not JSON: ${text.take(ERROR_BODY_EXCERPT)}", e)
                }
            }
        }

    companion object {
        /** A client that gives a call [timeout] in all: to connect, to send and to get the whole answer. */
        fun create(timeout: Duration): OkHttpJson =
            OkHttpJson(
                OkHttpClient.Builder()
                    .callTimeout(timeout)
                    .connectTimeout(timeout)
                    .readTimeout(timeout)
                    .build(),
            )
    }
}
