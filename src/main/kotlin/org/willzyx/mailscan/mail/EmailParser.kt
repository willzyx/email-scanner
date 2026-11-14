package org.willzyx.mailscan.mail

import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeUtility
import java.io.UnsupportedEncodingException

/** How much of the body the model gets: the start of an email says what it is about. */
const val BODY_MAX_CHARS = 4000

/**
 * Reads [raw], an email as it comes from the mail server; the body is tidied up and cut to
 * [bodyMaxChars]. Throws when [raw] is not an email at all.
 */
fun parseEmail(raw: ByteArray, bodyMaxChars: Int = BODY_MAX_CHARS): ParsedEmail {
    val message = MimeMessage(null, raw.inputStream())
    return ParsedEmail(
        sender = message.sender(),
        subject = message.headerText("Subject"),
        date = message.headerText("Date"),
        body = tidy(message.body()).cut(bodyMaxChars),
    )
}

/** [text] with each run of spaces made one space, no spaces around a line and no two blank lines in a row. */
private fun tidy(text: String): String =
    text.lines()
        .joinToString("\n") { line -> line.replace(SPACES, " ").trim() }
        .replace(BLANK_LINES, "\n\n")
        .trim()

/** Spaces, tabs and no-break spaces. */
private val SPACES = Regex("[ \t ]+")
private val BLANK_LINES = Regex("\n{3,}")

/** The first [maxChars] characters, without the half of a two-part character (an emoji) the cut would leave. */
private fun String.cut(maxChars: Int): String =
    when {
        length <= maxChars -> this
        this[maxChars - 1].isHighSurrogate() -> take(maxChars - 1)
        else -> take(maxChars)
    }

/** The plain text of the email, or else the text of its HTML; an empty string when it has neither. */
private fun MimeMessage.body(): String {
    findPart("text/plain")?.let { return it.text() }
    return findPart("text/html")?.let { htmlToText(it.text()) }.orEmpty()
}

/**
 * The first part of the given type, looked for through nested multiparts in the order they are
 * written. Attachments are passed over: a text file sent along is not what the email says.
 */
private fun Part.findPart(mimeType: String): Part? =
    when {
        Part.ATTACHMENT.equals(disposition, ignoreCase = true) -> null
        isMimeType(mimeType) -> this
        isMimeType("multipart/*") -> {
            val parts = content as Multipart
            (0 until parts.count).firstNotNullOfOrNull { parts.getBodyPart(it).findPart(mimeType) }
        }
        else -> null
    }

/** The content of a text part, decoded from its transfer encoding (base64, quoted-printable) and its charset. */
private fun Part.text(): String =
    try {
        content as String
    } catch (_: UnsupportedEncodingException) {
        // A charset Java does not know: most likely it is UTF-8 under a wrong name.
        inputStream.use { it.readBytes() }.decodeToString()
    }

/** The first address of the From header, with its name decoded and the address itself in lower case. */
private fun MimeMessage.sender(): String {
    val from = getHeader("From", ",") ?: return ""
    // Not strict: an address a mail program wrote carelessly is still read as far as it can be.
    val first = InternetAddress.parseHeader(from, false).firstOrNull() ?: return ""
    val name = first.personal?.trim().orEmpty()
    val address = first.address?.trim()?.lowercase().orEmpty()
    return if (name.isEmpty()) address else "$name <$address>"
}

/**
 * The header [name] as text: its lines joined and its encoded parts, such as `=?utf-8?b?0J/RgNC40LLQtdGC?=`,
 * decoded. An empty string when the email has no such header.
 */
private fun MimeMessage.headerText(name: String): String {
    val joined = MimeUtility.unfold(getHeader(name, null) ?: return "")
    val text =
        try {
            MimeUtility.decodeText(joined)
        } catch (_: UnsupportedEncodingException) {
            // An encoding Java does not know: better the header as it is than no header.
            joined
        }
    return text.trim()
}
