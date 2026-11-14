package org.willzyx.mailscan.telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.willzyx.mailscan.llm.Analysis
import org.willzyx.mailscan.llm.Importance
import org.willzyx.mailscan.mail.ParsedEmail

class EmailTextTest {
    private val mail =
        ParsedEmail(
            sender = "Bank <North> <a@b.example>",
            subject = "Subject & <tag>",
            date = "Tue, 03 Nov 2026 10:00:00 +0200",
            body = "text",
        )
    private val analysis =
        Analysis(importance = Importance.HIGH, summary = "Summary", reason = "Reason", actionRequired = true)

    @Test
    fun `an important email, with the text from it escaped`() {
        assertEquals(
            """
            🔴 <b>Important email</b>
            <b>From:</b> Bank &lt;North&gt; &lt;a@b.example&gt;
            <b>Subject:</b> Subject &amp; &lt;tag&gt;

            Summary
            ⚡ Action required · Why: Reason
            """.trimIndent(),
            emailText(mail, analysis),
        )
    }

    @Test
    fun `an email of medium importance has a header of its own`() {
        val text = emailText(mail, analysis.copy(importance = Importance.MEDIUM))
        assertEquals("🟡 <b>Email</b>", text.lines().first())
    }

    @Test
    fun `no action and no reason leave no last line`() {
        assertEquals(
            """
            🔴 <b>Important email</b>
            <b>From:</b> a@b.example
            <b>Subject:</b> Hello

            Summary
            """.trimIndent(),
            emailText(
                mail.copy(sender = "a@b.example", subject = "Hello"),
                analysis.copy(reason = "", actionRequired = false),
            ),
        )
    }

    @Test
    fun `the last line holds the action or the reason alone when there is one of them`() {
        assertEquals("⚡ Action required", emailText(mail, analysis.copy(reason = "")).lines().last())
        assertEquals("Why: Reason", emailText(mail, analysis.copy(actionRequired = false)).lines().last())
    }

    @Test
    fun `what the model wrote is escaped too`() {
        val text = emailText(mail, analysis.copy(summary = "Pay <b>now</b> & here", reason = "A <script>"))
        assertTrue("Pay &lt;b&gt;now&lt;/b&gt; &amp; here" in text, text)
        assertTrue("Why: A &lt;script&gt;" in text, text)
    }

    @Test
    fun `a long subject is cut and ends with an ellipsis`() {
        val text = emailText(mail.copy(subject = "я".repeat(500)), analysis)
        assertTrue("<b>Subject:</b> " + "я".repeat(199) + "…\n" in text)
        assertFalse("я".repeat(200) in text)
    }

    @Test
    fun `however long the fields, the message fits into one Telegram message`() {
        val long = "<".repeat(5000)
        val text = emailText(mail.copy(sender = long, subject = long), analysis.copy(summary = long, reason = long))
        // Telegram counts the characters after it has read the HTML entities.
        val counted = text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        assertTrue(counted.length < 4096, "${counted.length}")
    }
}
