package org.willzyx.mailscan.telegram

import org.willzyx.mailscan.llm.Analysis
import org.willzyx.mailscan.llm.Importance
import org.willzyx.mailscan.mail.ParsedEmail

// Telegram takes 4096 characters in a message; with these limits a whole message stays under 2500.
private const val SENDER_MAX = 200
private const val SUBJECT_MAX = 200
private const val SUMMARY_MAX = 1000

/** The Telegram message about an email and what the model made of it, in the HTML Telegram understands. */
fun emailText(mail: ParsedEmail, analysis: Analysis): String {
    val notes =
        listOfNotNull(
            "⚡ Action required".takeIf { analysis.actionRequired },
            "Why: ${escape(analysis.reason, SUMMARY_MAX)}".takeIf { analysis.reason.isNotEmpty() },
        )
    val lines =
        listOfNotNull(
            if (analysis.importance == Importance.HIGH) "🔴 <b>Important email</b>" else "🟡 <b>Email</b>",
            "<b>From:</b> ${escape(mail.sender, SENDER_MAX)}",
            "<b>Subject:</b> ${escape(mail.subject, SUBJECT_MAX)}",
            "",
            escape(analysis.summary, SUMMARY_MAX),
            notes.joinToString(" · ").takeIf { notes.isNotEmpty() },
        )
    return lines.joinToString("\n")
}

/**
 * [text] made safe to put into a Telegram HTML message: it comes from an email or from the model,
 * so `<` in it must not start a tag. Text over [maxChars] is cut to that length, the last character being "…".
 */
private fun escape(text: String, maxChars: Int): String {
    val cut = if (text.length > maxChars) text.take(maxChars - 1) + "…" else text
    return cut.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
