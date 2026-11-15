package org.willzyx.mailscan.telegram

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.willzyx.mailscan.db.AnalyzedEmail
import org.willzyx.mailscan.llm.Importance

// Telegram takes 4096 characters in a message; with these limits a whole message stays under 3500.
private const val ACCOUNT_MAX = 200
private const val TEXT_MAX = 3000

private val DAY_AND_TIME = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH)
private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

/**
 * The Telegram message with the digest of the mailbox of [account], in the HTML Telegram understands:
 * a heading with the period [from]–[until] and how many of [emails] are of each importance, then
 * [text], which is what the model made of them.
 */
fun digestText(
    account: String,
    from: ZonedDateTime,
    until: ZonedDateTime,
    emails: List<AnalyzedEmail>,
    text: String,
): String {
    val end = if (from.toLocalDate() == until.toLocalDate()) TIME.format(until) else DAY_AND_TIME.format(until)
    val count = emails.groupingBy { it.analysis.importance }.eachCount()
    val lines =
        listOf(
            "📬 <b>Digest</b> · ${escape(account, ACCOUNT_MAX)}",
            "${DAY_AND_TIME.format(from)} – $end",
            "🔴 ${count[Importance.HIGH] ?: 0} · 🟡 ${count[Importance.MEDIUM] ?: 0} · ⚪ ${count[Importance.LOW] ?: 0}",
            "",
            escape(text, TEXT_MAX),
        )
    return lines.joinToString("\n")
}
