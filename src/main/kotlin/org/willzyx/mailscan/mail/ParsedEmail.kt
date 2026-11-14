package org.willzyx.mailscan.mail

/** What the scanner reads from an email. A header the email does not have is an empty string. */
data class ParsedEmail(
    /** Who the email says it is from: `Name <address>`, or the address alone when it gives no name. */
    val sender: String,
    val subject: String,
    /** The Date header as the sender wrote it. */
    val date: String,
    /** The text of the email, or an empty string when it has none. */
    val body: String,
)
