package org.willzyx.mailscan.telegram.impl

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.willzyx.mailscan.http.FakeJsonHttp
import org.willzyx.mailscan.http.HttpStatusException
import org.willzyx.mailscan.telegram.TelegramException

private const val TOKEN = "123456:SECRET-token"
private val OK: JsonElement = Json.parseToJsonElement("""{"ok": true, "result": {"message_id": 7}}""")

class HttpTelegramClientTest {
    private fun client(http: FakeJsonHttp) = HttpTelegramClient(http, TOKEN, "42")

    /** Telegram refusing with the HTTP [status] and [body], the way the HTTP layer reports it. */
    private fun refusing(status: Int, body: String) =
        FakeJsonHttp { _, _ -> throw HttpStatusException("Telegram sendMessage", status, body) }

    @Test
    fun `sends the text as HTML to the chat, without link previews`() =
        runTest {
            val http = FakeJsonHttp { _, _ -> OK }

            client(http).sendMessage("<b>hi</b>")

            val call = http.calls.single()
            assertEquals("https://api.telegram.org/bot$TOKEN/sendMessage", call.url.toString())
            assertEquals(
                Json.parseToJsonElement(
                    """
                    {"chat_id": "42", "text": "<b>hi</b>", "parse_mode": "HTML",
                     "link_preview_options": {"is_disabled": true}}
                    """,
                ),
                call.body,
            )
        }

    @Test
    fun `a refusal is a TelegramException with the status and Telegram's own words`() =
        runTest {
            val http = refusing(400, """{"ok": false, "description": "Bad Request: chat not found"}""")
            val failure = assertFailsWith<TelegramException> { client(http).sendMessage("x") }
            assertEquals("Telegram sendMessage: 400 Bad Request: chat not found", failure.message)
        }

    @Test
    fun `a refusal without an explanation is a TelegramException with the status alone`() =
        runTest {
            val failure =
                assertFailsWith<TelegramException> {
                    client(refusing(502, "<html>Bad Gateway</html>")).sendMessage("x")
                }
            assertEquals("Telegram sendMessage: 502", failure.message)
        }

    @Test
    fun `an answer that does not say ok is a TelegramException`() =
        runTest {
            val answer = Json.parseToJsonElement("""{"ok": false, "description": "Unauthorized"}""")
            val http = FakeJsonHttp { _, _ -> answer }
            val failure = assertFailsWith<TelegramException> { client(http).sendMessage("x") }
            assertEquals("Telegram sendMessage: 200 Unauthorized", failure.message)
        }

    /** Telegram giving [answers] one after another. */
    private fun answering(vararg answers: () -> JsonElement) =
        ArrayDeque(answers.toList()).let { left -> FakeJsonHttp { _, _ -> left.removeFirst()() } }

    private val ok: () -> JsonElement = { OK }

    /** How long the test has been waiting; the clock of `runTest` jumps over a wait instead of sitting it out. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.waited(): Duration = currentTime.milliseconds

    /** "Too many requests", asking to wait [retryAfter] seconds, or not saying how long. */
    private fun busy(retryAfter: Int? = null): () -> JsonElement {
        val parameters = if (retryAfter == null) "" else """, "parameters": {"retry_after": $retryAfter}"""
        val body = """{"ok": false, "error_code": 429, "description": "Too Many Requests: retry later"$parameters}"""
        return { throw HttpStatusException("Telegram sendMessage", 429, body) }
    }

    @Test
    fun `when Telegram says too many requests, waits as long as it asks and a second more, then sends again`() =
        runTest {
            val http = answering(busy(3), ok)

            client(http).sendMessage("x")

            assertEquals(2, http.calls.size)
            assertEquals(http.calls[0], http.calls[1])
            assertEquals(4.seconds, waited())
        }

    @Test
    fun `gives up after the third refusal in a row`() =
        runTest {
            val http = answering(busy(3), busy(3), busy(3))

            val failure = assertFailsWith<TelegramException> { client(http).sendMessage("x") }

            assertEquals("Telegram sendMessage: 429 Too Many Requests: retry later", failure.message)
            assertEquals(3, http.calls.size)
            // No wait after the last attempt.
            assertEquals(8.seconds, waited())
        }

    @Test
    fun `the wait is a minute at most and five seconds when Telegram names none`() =
        runTest {
            client(answering(busy(3600), ok)).sendMessage("x")
            assertEquals(61.seconds, waited())

            client(answering(busy(), ok)).sendMessage("x")
            assertEquals((61 + 6).seconds, waited())
        }

    @Test
    fun `another refusal is not sent again`() =
        runTest {
            val http = refusing(400, """{"ok": false, "description": "Bad Request: chat not found"}""")
            assertFailsWith<TelegramException> { client(http).sendMessage("x") }
            assertEquals(1, http.calls.size)
            assertEquals(0.seconds, waited())
        }

    @Test
    fun `the bot token never gets into an error, even when the network error quotes the address`() =
        runTest {
            val http = FakeJsonHttp { url, _ -> throw IOException("cannot reach $url") }

            val failure = assertFailsWith<TelegramException> { client(http).sendMessage("x") }

            assertEquals(
                "Telegram sendMessage: no connection (cannot reach https://api.telegram.org/bot***/sendMessage)",
                failure.message,
            )
            assertFalse("SECRET" in failure.stackTraceToString())
            assertNull(failure.cause)
        }
}
