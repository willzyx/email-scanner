package org.willzyx.mailscan.llm.impl

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.willzyx.mailscan.http.FakeJsonHttp
import org.willzyx.mailscan.http.HttpStatusException
import org.willzyx.mailscan.llm.LlmUnavailableException

private val FORMAT = buildJsonObject { put("type", "object") }

/** Ollama answering every chat request with the model saying [content]. */
internal fun ollama(content: String) =
    FakeJsonHttp { _, _ -> buildJsonObject { putJsonObject("message") { put("content", content) } } }

/** The model `gemma4:e4b` of an Ollama that is [http]. */
internal fun chat(http: FakeJsonHttp, baseUrl: String = "http://ollama:11434") =
    OllamaChat(baseUrl.toHttpUrl(), "gemma4:e4b", http)

class OllamaChatTest {
    @Test
    fun `asks the model and returns what it wrote`() =
        runTest {
            val http = ollama("An answer")

            val answer = chat(http).ask("Instructions", "A question", FORMAT)

            assertEquals("An answer", answer)
            val call = http.calls.single()
            assertEquals("http://ollama:11434/api/chat", call.url.toString())
            val request = call.body.jsonObject
            assertEquals(
                setOf("model", "stream", "format", "think", "keep_alive", "options", "messages"),
                request.keys,
            )
            assertEquals(JsonPrimitive("gemma4:e4b"), request["model"])
            assertEquals(JsonPrimitive(false), request["stream"])
            assertEquals(FORMAT, request["format"])
            assertEquals(JsonPrimitive(false), request["think"])
            assertEquals(JsonPrimitive("1m"), request["keep_alive"])
            assertEquals(Json.parseToJsonElement("""{"temperature": 0, "num_ctx": 4096}"""), request["options"])
            assertEquals(
                Json.parseToJsonElement(
                    """[{"role": "system", "content": "Instructions"}, {"role": "user", "content": "A question"}]""",
                ),
                request["messages"],
            )
        }

    @Test
    fun `without a schema the answer is free text, and nothing is said of a format`() =
        runTest {
            val http = ollama("An answer")

            assertEquals("An answer", chat(http).ask("Instructions", "A question"))

            val request = http.calls.single().body.jsonObject
            assertEquals(setOf("model", "stream", "think", "keep_alive", "options", "messages"), request.keys)
            assertEquals(Json.parseToJsonElement("""{"temperature": 0, "num_ctx": 4096}"""), request["options"])
        }

    @Test
    fun `the limit of the answer goes to Ollama as num_predict`() =
        runTest {
            val http = ollama("An answer")

            chat(http).ask("Instructions", "A question", maxTokens = 700)

            assertEquals(
                Json.parseToJsonElement("""{"temperature": 0, "num_ctx": 4096, "num_predict": 700}"""),
                http.calls.single().body.jsonObject["options"],
            )
        }

    @Test
    fun `the address of Ollama may end with a slash or have a path`() =
        runTest {
            for ((base, expected) in listOf(
                "http://ollama:11434/" to "http://ollama:11434/api/chat",
                "https://host.example/ollama/" to "https://host.example/ollama/api/chat",
                "https://host.example/ollama" to "https://host.example/ollama/api/chat",
            )) {
                val http = ollama("An answer")
                chat(http, baseUrl = base).ask("Instructions", "A question", FORMAT)
                assertEquals(expected, http.calls.single().url.toString())
            }
        }

    @Test
    fun `a response with no text of the model gives null`() =
        runTest {
            assertNull(chat(FakeJsonHttp { _, _ -> buildJsonObject { } }).ask("Instructions", "A question", FORMAT))
            val noContent = FakeJsonHttp { _, _ -> buildJsonObject { putJsonObject("message") { } } }
            assertNull(chat(noContent).ask("Instructions", "A question", FORMAT))
        }

    @Test
    fun `Ollama that cannot be reached or refuses is an LlmUnavailableException`() =
        runTest {
            val down = FakeJsonHttp { _, _ -> throw IOException("Failed to connect to ollama:11434") }
            val failure =
                assertFailsWith<LlmUnavailableException> { chat(down).ask("Instructions", "A question", FORMAT) }
            assertEquals("Ollama is unavailable: Failed to connect to ollama:11434", failure.message)

            val refusing =
                FakeJsonHttp { _, _ -> throw HttpStatusException("Ollama /api/chat", 404, "model not found") }
            assertFailsWith<LlmUnavailableException> { chat(refusing).ask("Instructions", "A question", FORMAT) }
        }
}
