package org.willzyx.mailscan.http.impl

import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.ConnectionPool
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
        /**
         * How long an idle connection waits for the next request. Requests come minutes apart, and over
         * such a pause a router or a NAT on the way may drop the connection without telling either end:
         * a request sent into it gets no answer, and the call ends in a timeout. OkHttp keeps a
         * connection for five minutes and does not send the request again after a timeout.
         */
        private val KEEP_ALIVE: Duration = Duration.ofSeconds(30)

        /**
         * A client that gives a call [timeout] in all: to connect, to send and to get the whole answer.
         * A connection left idle for longer than [keepAlive] is closed, and the next request opens a new one.
         */
        fun create(timeout: Duration, keepAlive: Duration = KEEP_ALIVE): OkHttpJson =
            OkHttpJson(
                OkHttpClient.Builder()
                    .callTimeout(timeout)
                    .connectTimeout(timeout)
                    .readTimeout(timeout)
                    // Five idle connections at most, as in OkHttp's default pool.
                    .connectionPool(ConnectionPool(5, keepAlive.toMillis(), TimeUnit.MILLISECONDS))
                    .build(),
            )
    }
}
