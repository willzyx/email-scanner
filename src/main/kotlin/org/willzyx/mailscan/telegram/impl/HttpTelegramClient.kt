package org.willzyx.mailscan.telegram.impl

import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.willzyx.mailscan.http.HttpStatusException
import org.willzyx.mailscan.http.JsonHttp
import org.willzyx.mailscan.telegram.TelegramClient
import org.willzyx.mailscan.telegram.TelegramException

/**
 * [TelegramClient] over the Bot API: messages go from the bot with [token] to the chat [chatId].
 * The token is a part of the address of every request, so it is kept out of every error raised here.
 *
 * When Telegram says there are too many requests, the message is sent again after the wait Telegram
 * asks for, three times at most.
 */
class HttpTelegramClient(
    private val http: JsonHttp,
    private val token: String,
    private val chatId: String,
) : TelegramClient {
    private val sendMessageUrl: HttpUrl = "https://api.telegram.org/bot$token/sendMessage".toHttpUrl()

    override suspend fun sendMessage(text: String) {
        val request =
            buildJsonObject {
                put("chat_id", chatId)
                put("text", text)
                put("parse_mode", "HTML")
                // A link in an email summary must not unfold into a preview of the page.
                putJsonObject("link_preview_options") { put("is_disabled", true) }
            }
        val (status, answer) = postWithRetries(request)
        if ((answer["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
            val description = (answer["description"] as? JsonPrimitive)?.content.orEmpty()
            throw TelegramException("Telegram sendMessage: $status $description".trimEnd())
        }
    }

    private suspend fun postWithRetries(request: JsonObject): Pair<Int, JsonObject> {
        repeat(MAX_ATTEMPTS - 1) {
            val result = post(request)
            if (result.first != HTTP_TOO_MANY_REQUESTS) return result
            val answer = result.second
            val asked = ((answer["parameters"] as? JsonObject)?.get("retry_after") as? JsonPrimitive)?.longOrNull
            // A second on top, so that the next request does not come the moment the wait runs out.
            delay(((asked ?: DEFAULT_RETRY_AFTER).coerceIn(0, MAX_RETRY_AFTER) + 1).seconds)
        }
        return post(request)
    }

    /** The HTTP status and the JSON of the answer: Telegram explains a refusal in JSON as well. */
    private suspend fun post(request: JsonObject): Pair<Int, JsonObject> =
        try {
            HTTP_OK to (http.post(sendMessageUrl, request, "Telegram sendMessage") as? JsonObject ?: NO_ANSWER)
        } catch (e: HttpStatusException) {
            e.code to parseObject(e.body)
        } catch (e: IOException) {
            // The cause is dropped along with its message: either may quote the address, and so the token.
            val reason = (e.message ?: e.javaClass.simpleName).replace(token, "***")
            throw TelegramException("Telegram sendMessage: no connection ($reason)")
        }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val MAX_ATTEMPTS = 3

        /** Seconds to wait when Telegram does not say how long. */
        const val DEFAULT_RETRY_AFTER = 5L

        /** Seconds; a longer wait would hold up the whole pass over the mailbox. */
        const val MAX_RETRY_AFTER = 60L
        val NO_ANSWER = JsonObject(emptyMap())

        fun parseObject(text: String): JsonObject =
            try {
                Json.parseToJsonElement(text) as? JsonObject ?: NO_ANSWER
            } catch (_: SerializationException) {
                NO_ANSWER
            }
    }
}
