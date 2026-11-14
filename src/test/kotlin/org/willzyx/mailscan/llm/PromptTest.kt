package org.willzyx.mailscan.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.willzyx.mailscan.mail.ParsedEmail

class PromptTest {
    @Test
    fun `the answer schema asks for the four fields, with the importance one of three words`() {
        val expected =
            """
            {
              "type": "object",
              "properties": {
                "importance": {"type": "string", "enum": ["high", "medium", "low"]},
                "summary": {"type": "string"},
                "reason": {"type": "string"},
                "action_required": {"type": "boolean"}
              },
              "required": ["importance", "summary", "reason", "action_required"]
            }
            """.trimIndent()
        assertEquals(Json.parseToJsonElement(expected), ANSWER_SCHEMA)
    }

    @Test
    fun `the system prompt names the language of the summary and of the reason`() {
        val prompt = systemPrompt("Greek")
        assertTrue("- summary — 1–3 sentences in Greek, whatever language the email is in." in prompt)
        assertTrue("- reason — briefly, in Greek, why this level was chosen." in prompt)
        assertFalse("Russian" in prompt)
    }

    @Test
    fun `the system prompt is plain text from its first line to its last`() {
        val lines = systemPrompt("Russian").lines()
        assertEquals("You are an assistant that sorts the user's personal (home) email.", lines.first())
        assertEquals("Answer strictly as JSON matching the given schema.", lines.last())
        // The indentation of the source code is not part of the text; the lists keep their own two spaces.
        assertTrue(lines.none { it.startsWith("   ") })
        assertTrue("  banks and account security (charges, sign-ins from new devices, blocked cards);" in lines)
    }

    @Test
    fun `the user message is the email with its headers on top`() {
        val mail =
            ParsedEmail(
                sender = "Anya <anya@x.example>",
                subject = "Photos from the cottage",
                date = "Tue, 03 Nov 2026 20:47:12 +0300",
                body = "Uploaded the photos.\n\nHave a look!",
            )
        assertEquals(
            "From: Anya <anya@x.example>\n" +
                "Subject: Photos from the cottage\n" +
                "Date: Tue, 03 Nov 2026 20:47:12 +0300\n" +
                "\n" +
                "Uploaded the photos.\n\nHave a look!",
            userMessage(mail),
        )
    }
}
