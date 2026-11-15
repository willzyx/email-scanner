package org.willzyx.mailscan.llm.impl

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.willzyx.mailscan.http.FakeJsonHttp
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
    private fun analyzer(http: FakeJsonHttp, language: String = "Russian") = OllamaAnalyzer(chat(http), language)

    @Test
    fun `asks the model about the email and reads its answer`() =
        runTest {
            val http = ollama(ANSWER)

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
            val request = http.calls.single().body.jsonObject
            assertEquals(ANSWER_SCHEMA, request["format"])

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
            val http = ollama(ANSWER)
            analyzer(http, language = "Greek").analyze(MAIL)
            val system = http.calls.single().body.jsonObject.getValue("messages").jsonArray.first().jsonObject
            assertTrue("in Greek" in system.text("content"))
            assertFalse("in Russian" in system.text("content"))
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
            assertEquals(expected, analyzer(ollama("not json")).analyze(MAIL))
            assertEquals(expected, analyzer(ollama("""["a", "list"]""")).analyze(MAIL))
            // No message in the response at all.
            assertEquals(expected, analyzer(FakeJsonHttp { _, _ -> buildJsonObject { } }).analyze(MAIL))
        }

    @Test
    fun `Ollama that is unavailable is an LlmUnavailableException, the email left for the next pass`() =
        runTest {
            val down = FakeJsonHttp { _, _ -> throw IOException("Failed to connect to ollama:11434") }
            assertFailsWith<LlmUnavailableException> { analyzer(down).analyze(MAIL) }
        }
}

private fun Map<String, JsonElement>.text(name: String): String = getValue(name).jsonPrimitive.content
