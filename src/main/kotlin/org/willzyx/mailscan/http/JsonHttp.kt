package org.willzyx.mailscan.http

import java.io.IOException
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl

/**
 * An HTTP status outside 2xx from an external API. [body] is the whole answer: an API such as
 * Telegram's explains its refusal there. The message carries only the start of it.
 */
class HttpStatusException(val what: String, val code: Int, val body: String) :
    IOException("$what failed with HTTP $code: ${body.take(ERROR_BODY_EXCERPT)}")

/** How much of an answer goes into an error message. */
internal const val ERROR_BODY_EXCERPT = 200

/**
 * JSON over HTTP. An interface so the clients built on it can be tested against prepared answers
 * instead of the live services.
 */
interface JsonHttp {
    /**
     * Posts [body] to [url] and returns the JSON the server answers with; [what] names the call in
     * error messages. Throws an [HttpStatusException] on a status outside 2xx and another
     * [IOException] when the server cannot be reached, is too slow or answers with something that is not JSON.
     */
    suspend fun post(url: HttpUrl, body: JsonElement, what: String): JsonElement
}
