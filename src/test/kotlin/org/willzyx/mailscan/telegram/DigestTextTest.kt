package org.willzyx.mailscan.telegram

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.willzyx.mailscan.db.AnalyzedEmail
import org.willzyx.mailscan.llm.Analysis
import org.willzyx.mailscan.llm.Importance

private const val HOME = "home@example.com"
private val NICOSIA: ZoneId = ZoneId.of("Asia/Nicosia")

/** The moment the clock in Nicosia shows [text], as in `2026-11-15T09:04`. */
private fun at(text: String): ZonedDateTime = LocalDateTime.parse(text).atZone(NICOSIA)

/** As many emails of each importance as asked for. */
private fun emails(high: Int, medium: Int, low: Int): List<AnalyzedEmail> =
    (List(high) { Importance.HIGH } + List(medium) { Importance.MEDIUM } + List(low) { Importance.LOW })
        .mapIndexed { index, importance ->
            AnalyzedEmail(
                uidValidity = 7,
                uid = index.toLong(),
                analyzedAt = Instant.parse("2026-11-15T10:00:00Z"),
                sender = "Sender",
                subject = "Subject",
                analysis = Analysis(importance, "Summary", "Reason", false),
            )
        }

class DigestTextTest {
    @Test
    fun `a digest has the mailbox, the period and the counts on top, and the model's text below`() {
        assertEquals(
            """
            📬 <b>Digest</b> · home@example.com
            15 Nov 09:04 – 21:00
            🔴 1 · 🟡 3 · ⚪ 14

            • The bank asks to confirm a payment today.
            • 14 newsletters and notifications.
            """.trimIndent(),
            digestText(
                HOME,
                at("2026-11-15T09:04"),
                at("2026-11-15T21:00"),
                emails(high = 1, medium = 3, low = 14),
                "• The bank asks to confirm a payment today.\n• 14 newsletters and notifications.",
            ),
        )
    }

    @Test
    fun `a period that goes over midnight names both days`() {
        val text = digestText(HOME, at("2026-11-14T21:02"), at("2026-11-15T09:01"), emails(0, 0, 1), "• Text")
        assertEquals("14 Nov 21:02 – 15 Nov 09:01", text.lines()[1])
    }

    @Test
    fun `the period is told by the clock of the zone it is given in`() {
        val from = Instant.parse("2026-11-15T07:04:00Z").atZone(NICOSIA)
        val until = Instant.parse("2026-11-15T19:00:00Z").atZone(NICOSIA)
        assertEquals("15 Nov 09:04 – 21:00", digestText(HOME, from, until, emails(0, 0, 1), "• Text").lines()[1])
    }

    @Test
    fun `an importance no email has is counted as zero`() {
        val text = digestText(HOME, at("2026-11-15T09:04"), at("2026-11-15T21:00"), emails(0, 2, 0), "• Text")
        assertEquals("🔴 0 · 🟡 2 · ⚪ 0", text.lines()[2])
    }

    @Test
    fun `what the model wrote, and the mailbox, are escaped`() {
        val text =
            digestText(
                "a&b@example.com",
                at("2026-11-15T09:04"),
                at("2026-11-15T21:00"),
                emails(0, 0, 1),
                "• <b>bold</b> & more",
            )
        assertEquals("📬 <b>Digest</b> · a&amp;b@example.com", text.lines().first())
        assertEquals("• &lt;b&gt;bold&lt;/b&gt; &amp; more", text.lines().last())
    }

    @Test
    fun `however long the text, the message fits into one Telegram message`() {
        val text =
            digestText(
                "a".repeat(1000),
                at("2026-11-14T21:02"),
                at("2026-11-15T09:01"),
                emails(1000, 1000, 1000),
                "x".repeat(10_000),
            )
        assertTrue(text.length <= 4096, "${text.length} characters")
        assertTrue(text.endsWith("x…"))
    }
}
