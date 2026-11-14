package org.willzyx.mailscan.db

import java.time.Instant
import org.willzyx.mailscan.llm.Analysis

/** An email the model has rated: which one it is, who wrote it about what, and what the model said. */
data class AnalyzedEmail(
    /** With [uid], names the email within its mailbox. */
    val uidValidity: Long,
    val uid: Long,
    /** When the model rated it; kept to the second. */
    val analyzedAt: Instant,
    val sender: String,
    val subject: String,
    val analysis: Analysis,
)

/**
 * Every email the model has rated, of whatever importance, kept across restarts. A mailbox is named
 * by its account, as in [CursorRepository]. The text of an email is not stored.
 */
interface AnalysisRepository {
    /** Keeps [email] of the mailbox of [account]. An email rated a second time replaces its first record. */
    suspend fun save(account: String, email: AnalyzedEmail)
}
