package org.willzyx.mailscan.llm.impl

import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.willzyx.mailscan.db.AnalyzedEmail
import org.willzyx.mailscan.http.FakeJsonHttp
import org.willzyx.mailscan.llm.Analysis
import org.willzyx.mailscan.llm.Importance
import org.willzyx.mailscan.llm.LlmUnavailableException
import org.willzyx.mailscan.llm.digestEntry

private val AT: Instant = Instant.parse("2026-11-03T22:15:00Z")

/** The email with [uid], rated that many seconds after [AT]. */
private fun email(uid: Long, importance: Importance, summary: String = "Summary $uid") =
    AnalyzedEmail(
        uidValidity = 7,
        uid = uid,
        analyzedAt = AT.plusSeconds(uid),
        sender = "Sender $uid <s$uid@x.example>",
        subject = "Letter $uid",
        analysis = Analysis(importance, summary, "Reason $uid", importance == Importance.HIGH),
    )

/** As many emails as [count], each with a summary of the greatest length an entry takes: 12 of them fit one request. */
private fun longEmails(count: Int) = (1L..count).map { email(it, Importance.LOW, summary = "x".repeat(400)) }

/** The response of Ollama in which the model says [content]. */
private fun answer(content: String) = buildJsonObject { putJsonObject("message") { put("content", content) } }

class OllamaDigestWriterTest {
    private fun writer(http: FakeJsonHttp, language: String = "Russian") = OllamaDigestWriter(chat(http), language)

    /** The system and the user message of the request [call] made. */
    private fun messages(call: FakeJsonHttp.Call): List<String> =
        call.body.jsonObject.getValue("messages").jsonArray.map {
            it.jsonObject.getValue("content").jsonPrimitive.content
        }

    @Test
    fun `asks the model to retell the emails and returns its text`() =
        runTest {
            val http = ollama("A digest in two sentences. This is the second.")
            val emails = listOf(email(1, Importance.HIGH), email(2, Importance.LOW))

            val digest = writer(http).write(emails)

            assertEquals("A digest in two sentences. This is the second.", digest)
            val request = http.calls.single().body.jsonObject
            // Free text: no schema to hold the answer to.
            assertFalse("format" in request)
            assertEquals(
                Json.parseToJsonElement("""{"temperature": 0, "num_ctx": 4096, "num_predict": 700}"""),
                request["options"],
            )
            val (system, user) = messages(http.calls.single())
            assertTrue("Retell them in Russian as one short paragraph" in system)
            assertEquals(digestEntry(emails[0]) + "\n\n" + digestEntry(emails[1]), user)
        }

    @Test
    fun `the language of the digest goes into the system prompt`() =
        runTest {
            val http = ollama("A digest")
            writer(http, language = "Greek").write(listOf(email(1, Importance.LOW)))
            assertTrue("Retell them in Greek as one short paragraph" in messages(http.calls.single())[0])
        }

    @Test
    fun `the model gets the emails most important first, then in the order they were rated`() =
        runTest {
            val http = ollama("A digest")
            val emails =
                listOf(
                    email(1, Importance.LOW),
                    email(2, Importance.MEDIUM),
                    email(3, Importance.HIGH),
                    email(4, Importance.LOW),
                    email(5, Importance.HIGH),
                )

            writer(http).write(emails)

            val subjects = messages(http.calls.single())[1].lines().filter { it.startsWith("Subject: ") }
            assertEquals(listOf(3, 5, 2, 1, 4).map { "Subject: Letter $it" }, subjects)
        }

    @Test
    fun `emails that do not fit one request are retold in portions, and the model then joins the parts`() =
        runTest {
            var asked = 0
            val http = FakeJsonHttp { _, _ -> answer("Answer ${++asked}") }
            val emails = longEmails(30)

            val digest = writer(http).write(emails)

            val retold = http.calls.dropLast(1).map { messages(it) }
            assertEquals(3, retold.size)
            for ((system, user) in retold) {
                assertTrue(system.startsWith("You write a digest"), system)
                assertTrue(user.length <= 6000, "${user.length} characters")
            }
            // Every email is in one portion, and the portions keep the order.
            assertEquals(emails.joinToString("\n\n") { digestEntry(it) }, retold.joinToString("\n\n") { it[1] })

            val (system, user) = messages(http.calls.last())
            assertTrue(system.startsWith("You get several parts of one digest"), system)
            assertTrue("Join the parts in Russian into one short paragraph" in system)
            assertEquals("Part 1:\nAnswer 1\n\nPart 2:\nAnswer 2\n\nPart 3:\nAnswer 3", user)
            assertEquals("Answer 4", digest)
        }

    @Test
    fun `parts that do not fit one request either are joined in rounds, until one text is left`() =
        runTest {
            // Every answer is as long as an answer gets, so a request takes three parts and no more.
            val http = ollama("a".repeat(1500))

            val digest = writer(http).write(longEmails(40))

            assertEquals("a".repeat(1500), digest)
            val (retold, joined) = http.calls.map { messages(it) }.partition { it[0].startsWith("You write a digest") }
            assertEquals(4, retold.size)
            // Three of the four parts first, the fourth waiting; then their text with the fourth.
            val heading = Regex("^Part \\d+:$", RegexOption.MULTILINE)
            assertEquals(listOf(3, 2), joined.map { (_, user) -> heading.findAll(user).count() })
            for ((_, user) in joined) assertTrue(user.length <= 6100, "${user.length} characters")
        }

    @Test
    fun `a part the model writes nothing for fails the whole digest`() =
        runTest {
            var asked = 0
            val http = FakeJsonHttp { _, _ -> answer(if (++asked == 2) "" else "Answer $asked") }
            assertFailsWith<LlmUnavailableException> { writer(http).write(longEmails(30)) }
        }

    @Test
    fun `the space around the model's text is dropped`() =
        runTest {
            assertEquals("A digest", writer(ollama("\n\n  A digest \n")).write(listOf(email(1, Importance.LOW))))
        }

    @Test
    fun `a text that is too long is cut, the last character being an ellipsis`() =
        runTest {
            val digest = writer(ollama("a".repeat(1501))).write(listOf(email(1, Importance.LOW)))
            assertEquals("a".repeat(1499) + "…", digest)

            val fits = "a".repeat(1500)
            assertEquals(fits, writer(ollama(fits)).write(listOf(email(1, Importance.LOW))))
        }

    @Test
    fun `a model that writes nothing is an LlmUnavailableException, so the digest is tried again`() =
        runTest {
            for (http in listOf(ollama(""), ollama(" \n "), FakeJsonHttp { _, _ -> buildJsonObject { } })) {
                val failure =
                    assertFailsWith<LlmUnavailableException> { writer(http).write(listOf(email(1, Importance.LOW))) }
                assertEquals("Ollama wrote an empty digest", failure.message)
            }
        }

    @Test
    fun `Ollama that is unavailable is an LlmUnavailableException`() =
        runTest {
            val down = FakeJsonHttp { _, _ -> throw IOException("Failed to connect to ollama:11434") }
            assertFailsWith<LlmUnavailableException> { writer(down).write(listOf(email(1, Importance.LOW))) }
        }
}
