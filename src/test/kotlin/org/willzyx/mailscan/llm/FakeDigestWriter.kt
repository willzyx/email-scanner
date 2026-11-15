package org.willzyx.mailscan.llm

import org.willzyx.mailscan.db.AnalyzedEmail

/**
 * [DigestWriter] whose digest lists the subjects of the emails it got, and that remembers the emails
 * it was [asked] to retell. Nothing is written while it is [down].
 */
class FakeDigestWriter : DigestWriter {
    val asked = mutableListOf<List<AnalyzedEmail>>()
    var down = false

    override suspend fun write(emails: List<AnalyzedEmail>): String {
        asked += emails
        if (down) throw LlmUnavailableException("Ollama is unavailable: connection refused")
        return "• Digest of " + emails.joinToString { it.subject }
    }
}
