package org.willzyx.mailscan.llm

import org.willzyx.mailscan.db.AnalyzedEmail

/** Retells the emails of a period as one text. An interface so the digest can be tested without a model. */
fun interface DigestWriter {
    /**
     * The digest of [emails], the rated emails of one mailbox, of which there is at least one: plain
     * text, a paragraph of prose, not yet made safe for Telegram.
     *
     * @throws LlmUnavailableException when the model cannot be asked or writes nothing
     */
    suspend fun write(emails: List<AnalyzedEmail>): String
}
