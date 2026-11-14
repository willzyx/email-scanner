package org.willzyx.mailscan.http.impl

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.willzyx.mailscan.http.HttpStatusException

/** [OkHttpJson] against the HTTP server that comes with the JDK, on a port of its own choosing. */
class OkHttpJsonTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val http = OkHttpJson.create(Duration.ofSeconds(5))
    private val request = buildJsonObject { put("text", "Привет, 10 €") }

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.stop(0)

    private fun url(path: String): HttpUrl = "http://127.0.0.1:${server.address.port}$path".toHttpUrl()

    /** Makes the server answer [path] with [status] and [body], after letting [before] look at the request. */
    private fun answer(path: String, status: Int, body: String, before: (HttpExchange) -> Unit = {}) {
        server.createContext(path) { exchange ->
            before(exchange)
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    @Test
    fun `posts the JSON and returns the JSON of the answer`() =
        runTest {
            val seen = mutableListOf<String>()
            answer("/api/echo", 200, """{"ok": true, "result": {"text": "Ответ"}}""") { exchange ->
                seen += exchange.requestMethod
                seen += exchange.requestHeaders.getFirst("Content-Type")
                seen += exchange.requestBody.readBytes().decodeToString()
            }

            val response = http.post(url("/api/echo"), request, "Echo")

            assertEquals(Json.parseToJsonElement("""{"ok": true, "result": {"text": "Ответ"}}"""), response)
            assertEquals(listOf("POST", "application/json; charset=utf-8", """{"text":"Привет, 10 €"}"""), seen)
        }

    @Test
    fun `a status outside 2xx is an HttpStatusException with the code and the whole answer`() =
        runTest {
            val body = """{"ok": false, "description": "Too Many Requests", "padding": "${"x".repeat(300)}"}"""
            answer("/api/busy", 429, body)

            val failure = assertFailsWith<HttpStatusException> { http.post(url("/api/busy"), request, "Busy call") }

            assertEquals(429, failure.code)
            assertEquals(body, failure.body)
            assertTrue(failure.message.orEmpty().startsWith("Busy call failed with HTTP 429: {\"ok\": false"))
            // Only the start of a long answer goes into the message.
            assertFalse("x".repeat(250) in failure.message.orEmpty())
        }

    @Test
    fun `an answer that is not JSON is an IOException naming the call`() =
        runTest {
            answer("/api/html", 200, "<html>Bad gateway</html>")
            val failure = assertFailsWith<IOException> { http.post(url("/api/html"), request, "Html call") }
            assertEquals("Html call returned a body that is not JSON: <html>Bad gateway</html>", failure.message)
        }

    @Test
    fun `a server that is not there is an IOException`() =
        runTest {
            val gone = url("/api/echo")
            server.stop(0)
            assertFailsWith<IOException> { http.post(gone, request, "Echo") }
        }

    @Test
    fun `a server slower than the timeout is an IOException`() =
        runTest {
            answer("/api/slow", 200, "{}") { Thread.sleep(1_000) }
            val impatient = OkHttpJson.create(Duration.ofMillis(200))
            assertFailsWith<IOException> { impatient.post(url("/api/slow"), request, "Slow call") }
        }
}
