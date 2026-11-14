package org.willzyx.mailscan.llm.impl

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.willzyx.mailscan.http.FakeJsonHttp
import org.willzyx.mailscan.http.HttpStatusException
import org.willzyx.mailscan.llm.ANSWER_SCHEMA
import org.willzyx.mailscan.llm.Analysis
import org.willzyx.mailscan.llm.Importance
import org.willzyx.mailscan.llm.LlmUnavailableException
import org.willzyx.mailscan.mail.ParsedEmail

private val MAIL =
    ParsedEmail(
        sender = "Anya <anya@x.example>",
        subject = "Photos from the cottage",
        date = "Tue, 03 Nov 2026 20:47:12 +0300",
        body = "Uploaded the photos.",
    )

private const val ANSWER =
    """{"importance": "medium", "summary": "Anya sent photos from the cottage.",
        "reason": "An email from a person, no reply needed", "action_required": false}"""

class OllamaAnalyzerTest {
    /** Ollama answering every chat request with the model saying [content]. */
    private fun ollama(content: String = ANSWER) =
        FakeJsonHttp { _, _ -> buildJsonObject { putJsonObject("message") { put("content", JsonPrimitive(content)) } } }

    private fun analyzer(http: FakeJsonHttp, baseUrl: String = "http://ollama:11434", language: String = "Russian") =
        OllamaAnalyzer(baseUrl.toHttpUrl(), "gemma4:e4b", language, http)

    @Test
    fun `asks the model about the email and reads its answer`() =
        runTest {
            val http = ollama()

            val analysis = analyzer(http).analyze(MAIL)

            assertEquals(
                Analysis(
                    importance = Importance.MEDIUM,
                    summary = "Anya sent photos from the cottage.",
                    reason = "An email from a person, no reply needed",
                    actionRequired = false,
                ),
                analysis,
            )
            val call = http.calls.single()
            assertEquals("http://ollama:11434/api/chat", call.url.toString())
            val request = call.body.jsonObject
            assertEquals(
                setOf("model", "stream", "format", "think", "keep_alive", "options", "messages"),
                request.keys,
            )
            assertEquals(JsonPrimitive("gemma4:e4b"), request["model"])
            assertEquals(JsonPrimitive(false), request["stream"])
            assertEquals(ANSWER_SCHEMA, request["format"])
            assertEquals(JsonPrimitive(false), request["think"])
            assertEquals(JsonPrimitive("1m"), request["keep_alive"])
            assertEquals(Json.parseToJsonElement("""{"temperature": 0, "num_ctx": 4096}"""), request["options"])

            val (system, user) = request.getValue("messages").jsonArray.map { it.jsonObject }
            assertEquals(JsonPrimitive("system"), system["role"])
            assertTrue("in Russian" in system.text("content"))
            assertEquals(JsonPrimitive("user"), user["role"])
            assertEquals(
                "From: Anya <anya@x.example>\nSubject: Photos from the cottage\n" +
                    "Date: Tue, 03 Nov 2026 20:47:12 +0300\n\nUploaded the photos.",
                user.text("content"),
            )
        }

    @Test
    fun `the language of the summaries goes into the system prompt`() =
        runTest {
            val http = ollama()
            analyzer(http, language = "Greek").analyze(MAIL)
            val system = http.calls.single().body.jsonObject.getValue("messages").jsonArray.first().jsonObject
            assertTrue("in Greek" in system.text("content"))
            assertFalse("in Russian" in system.text("content"))
        }

    @Test
    fun `the address of Ollama may end with a slash or have a path`() =
        runTest {
            for ((base, expected) in listOf(
                "http://ollama:11434/" to "http://ollama:11434/api/chat",
                "https://host.example/ollama/" to "https://host.example/ollama/api/chat",
                "https://host.example/ollama" to "https://host.example/ollama/api/chat",
            )) {
                val http = ollama()
                analyzer(http, baseUrl = base).analyze(MAIL)
                assertEquals(expected, http.calls.single().url.toString())
            }
        }

    @Test
    fun `an answer the model did not write as JSON is rated medium, so the email is not lost`() =
        runTest {
            val expected =
                Analysis(
                    importance = Importance.MEDIUM,
                    summary = "Subject: Photos from the cottage",
                    reason = "Could not parse the model's answer",
                    actionRequired = false,
                )
            assertEquals(expected, analyzer(ollama(content = "not json")).analyze(MAIL))
            assertEquals(expected, analyzer(ollama(content = """["a", "list"]""")).analyze(MAIL))
            // No message in the response at all.
            assertEquals(expected, analyzer(FakeJsonHttp { _, _ -> buildJsonObject { } }).analyze(MAIL))
        }

    @Test
    fun `Ollama that cannot be reached or refuses is an LlmUnavailableException`() =
        runTest {
            val down = FakeJsonHttp { _, _ -> throw IOException("Failed to connect to ollama:11434") }
            val failure = assertFailsWith<LlmUnavailableException> { analyzer(down).analyze(MAIL) }
            assertEquals("Ollama is unavailable: Failed to connect to ollama:11434", failure.message)

            val refusing =
                FakeJsonHttp { _, _ -> throw HttpStatusException("Ollama /api/chat", 404, "model not found") }
            assertFailsWith<LlmUnavailableException> { analyzer(refusing).analyze(MAIL) }
        }
}

private fun Map<String, JsonElement>.text(name: String): String = getValue(name).jsonPrimitive.content
