package org.willzyx.mailscan.db

import java.time.Instant

/** [AnalysisRepository] that lives as long as the test. While it is [down], nothing can be deleted from it. */
class InMemoryAnalysisRepository : AnalysisRepository {
    private val emails = linkedMapOf<Triple<String, Long, Long>, AnalyzedEmail>()
    var down = false

    /** The emails kept for the mailbox of [account], in the order they were first saved. */
    fun of(account: String): List<AnalyzedEmail> = emails.filterKeys { it.first == account }.values.toList()

    override suspend fun save(account: String, email: AnalyzedEmail) {
        emails[Triple(account, email.uidValidity, email.uid)] = email
    }

    override suspend fun between(account: String, from: Instant, until: Instant): List<AnalyzedEmail> =
        of(account).filter { it.analyzedAt >= from && it.analyzedAt < until }.sortedBy { it.analyzedAt }

    override suspend fun deleteBefore(time: Instant): Int {
        check(!down) { "the database is down" }
        val old = emails.filterValues { it.analyzedAt < time }.keys
        emails.keys.removeAll(old)
        return old.size
    }
}
